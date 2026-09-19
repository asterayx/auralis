package com.auralis.pipeline

import com.auralis.core.Clock
import com.auralis.error.ProviderError
import com.auralis.model.ConversationSide
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageDetect
import com.auralis.model.TranscriptSegment
import com.auralis.model.TranslatedSegment
import com.auralis.provider.llm.ChatMessage
import com.auralis.provider.llm.ChatRequest
import com.auralis.provider.llm.LlmProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationOrchestrator(
    private val llm: LlmProvider,
    private val targetLanguage: String,
    private val glossary: List<GlossaryEntry> = emptyList(),
    private val contextSentences: Int = 4,
    private val clock: Clock = Clock.System,
    private val bidirectional: Boolean = false,
    private val localLanguage: String = "zh",
    private val remoteLanguage: String = "en",
) {
    private val done = LinkedHashMap<String, TranslatedSegment>()
    private val mutex = Mutex()
    var inputTokens: Int = 0
        private set
    var outputTokens: Int = 0
        private set

    fun snapshot(): List<TranslatedSegment> = done.values.toList()

    suspend fun translate(
        segment: TranscriptSegment,
        previous: List<TranscriptSegment>,
        isPreview: Boolean = false,
    ): TranslatedSegment {
        val existing = mutex.withLock { done[segment.id] }
        if (existing != null && !existing.isPreview && !isPreview) return existing
        val direction = resolveDirection(segment)
        val context = previous.takeLast(contextSentences)
        val glossaryBlock = if (glossary.isEmpty()) "" else
            "Glossary (must use these translations):\n" +
                glossary.joinToString("\n") { "- ${it.source} → ${it.target}" }
        val contextBlock = context.joinToString("\n") { it.text }
        val prompt = buildString {
            appendLine("Translate the LAST utterance into ${direction.targetLanguage}.")
            appendLine("Keep names, numbers, and glossary terms unchanged except for the specified target.")
            appendLine("Return only the translation, no quotes or labels.")
            if (glossaryBlock.isNotBlank()) {
                appendLine()
                appendLine(glossaryBlock)
            }
            if (contextBlock.isNotBlank()) {
                appendLine()
                appendLine("Previous utterances:")
                appendLine(contextBlock)
            }
            appendLine()
            appendLine("Utterance:")
            appendLine(segment.text)
        }
        val result = try {
            llm.complete(
                ChatRequest(
                    messages = listOf(
                        ChatMessage("system", "You are a low-latency meeting interpreter."),
                        ChatMessage("user", prompt),
                    ),
                    temperature = 0.1,
                    maxTokens = 512,
                ),
            )
        } catch (e: ProviderError) {
            throw e
        }
        inputTokens += result.inputTokens
        outputTokens += result.outputTokens
        val translated = TranslatedSegment(
            segmentId = segment.id,
            sourceText = segment.text,
            translatedText = result.text.trim(),
            isPreview = isPreview,
            providerId = llm.id,
            model = llm.model,
            updatedAtMs = clock.nowMs(),
            sourceLanguage = direction.sourceLanguage,
            targetLanguage = direction.targetLanguage,
            side = direction.side,
        )
        mutex.withLock {
            val current = done[segment.id]
            if (current == null || current.isPreview || !isPreview) {
                done[segment.id] = translated
            }
        }
        return translated
    }

    fun ingestNative(segmentId: String, source: String, translated: String) {
        val sourceLang = LanguageDetect.detect(source)
        done[segmentId] = TranslatedSegment(
            segmentId = segmentId,
            sourceText = source,
            translatedText = translated,
            isPreview = false,
            providerId = "native",
            model = "stt-native",
            updatedAtMs = clock.nowMs(),
            sourceLanguage = sourceLang,
            targetLanguage = LanguageDetect.counterpart(sourceLang, localLanguage, remoteLanguage),
            side = if (LanguageDetect.sameFamily(sourceLang, localLanguage)) {
                ConversationSide.LOCAL
            } else {
                ConversationSide.REMOTE
            },
        )
    }

    internal fun resolveDirection(segment: TranscriptSegment): Direction {
        val detected = segment.language?.takeIf { it.isNotBlank() } ?: LanguageDetect.detect(segment.text)
        if (!bidirectional) {
            return Direction(
                sourceLanguage = detected,
                targetLanguage = targetLanguage,
                side = ConversationSide.LOCAL,
            )
        }
        val local = LanguageDetect.sameFamily(detected, localLanguage)
        return Direction(
            sourceLanguage = detected,
            targetLanguage = if (local) remoteLanguage else localLanguage,
            side = if (local) ConversationSide.LOCAL else ConversationSide.REMOTE,
        )
    }

    data class Direction(
        val sourceLanguage: String,
        val targetLanguage: String,
        val side: ConversationSide,
    )
}
