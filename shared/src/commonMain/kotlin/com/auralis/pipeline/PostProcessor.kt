package com.auralis.pipeline

import com.auralis.core.Clock
import com.auralis.core.newId
import com.auralis.model.PostProcessResult
import com.auralis.model.PromptTemplate
import com.auralis.model.SessionBundle
import com.auralis.provider.llm.ChatMessage
import com.auralis.provider.llm.ChatRequest
import com.auralis.provider.llm.LlmProvider

class PostProcessor(
    private val llm: LlmProvider,
    private val clock: Clock = Clock.System,
    private val chunkChars: Int = 24_000,
) {
    suspend fun run(bundle: SessionBundle, template: PromptTemplate): PostProcessResult {
        val transcript = bundle.fullTranscript()
        val chunks = chunk(transcript)
        val partials = chunks.mapIndexed { index, chunk ->
            val header = if (chunks.size == 1) "" else "Part ${index + 1}/${chunks.size} of a longer meeting.\n"
            llm.complete(
                ChatRequest(
                    messages = listOf(
                        ChatMessage("system", template.prompt),
                        ChatMessage("user", header + chunk),
                    ),
                    temperature = 0.2,
                ),
            )
        }
        val merged = if (partials.size == 1) {
            partials.first()
        } else {
            llm.complete(
                ChatRequest(
                    messages = listOf(
                        ChatMessage("system", template.prompt + "\nMerge the partial notes. Deduplicate."),
                        ChatMessage("user", partials.joinToString("\n\n---\n\n") { it.text }),
                    ),
                ),
            )
        }
        return PostProcessResult(
            id = newId("pp"),
            templateId = template.id,
            templateName = template.name,
            content = merged.text.trim(),
            createdAtMs = clock.nowMs(),
            providerId = llm.id,
            model = llm.model,
            inputTokens = partials.sumOf { it.inputTokens } + if (partials.size == 1) 0 else merged.inputTokens,
            outputTokens = partials.sumOf { it.outputTokens } + if (partials.size == 1) 0 else merged.outputTokens,
        )
    }

    suspend fun ask(bundle: SessionBundle, question: String): PostProcessResult {
        val result = llm.complete(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        "system",
                        "Answer only from the transcript. Quote the relevant sentence. If unknown, say you cannot find it.",
                    ),
                    ChatMessage("user", "Transcript:\n${bundle.fullTranscript()}\n\nQuestion: $question"),
                ),
            ),
        )
        return PostProcessResult(
            id = newId("qa"),
            templateId = "qa",
            templateName = "Q&A",
            content = result.text.trim(),
            createdAtMs = clock.nowMs(),
            providerId = llm.id,
            model = llm.model,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }

    internal fun chunk(text: String): List<String> {
        if (text.length <= chunkChars) return listOf(text)
        val parts = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = minOf(text.length, i + chunkChars)
            var cut = end
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > i + chunkChars / 2) cut = nl
            }
            parts += text.substring(i, cut)
            i = cut
        }
        return parts
    }
}
