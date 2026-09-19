package com.auralis.transcript

import com.auralis.core.newId
import com.auralis.model.TranscriptSegment
import com.auralis.model.TranscriptToken

/**
 * Aggregates token-level events into sentence/clause segments.
 * Final tokens are stable; interim tokens live in a single trailing draft segment.
 */
class Segmenter {
    private val finals = mutableListOf<TranscriptToken>()
    private var interims = listOf<TranscriptToken>()

    fun ingest(tokens: List<TranscriptToken>): Snapshot {
        val incomingFinals = tokens.filter { it.isFinal }
        val incomingInterims = tokens.filter { !it.isFinal }
        if (incomingFinals.isNotEmpty()) {
            finals += incomingFinals
        }
        if (incomingInterims.isNotEmpty() || tokens.any { !it.isFinal }) {
            interims = incomingInterims
        } else if (incomingFinals.isNotEmpty()) {
            interims = emptyList()
        }
        return snapshot()
    }

    fun replaceAll(finalTokens: List<TranscriptToken>) {
        finals.clear()
        finals += finalTokens.filter { it.isFinal }
        interims = emptyList()
    }

    fun snapshot(): Snapshot {
        val finalSegs = group(finals, draft = false)
        val draft = if (interims.isEmpty()) emptyList()
        else group(interims, draft = true)
        return Snapshot(finals.toList(), interims, finalSegs + draft)
    }

    private fun group(tokens: List<TranscriptToken>, draft: Boolean): List<TranscriptSegment> {
        if (tokens.isEmpty()) return emptyList()
        val segments = mutableListOf<TranscriptSegment>()
        var bucket = mutableListOf<TranscriptToken>()
        fun flush() {
            if (bucket.isEmpty()) return
            val text = join(bucket)
            segments += TranscriptSegment(
                id = if (draft) DRAFT_SEGMENT_ID else segmentIdFor(bucket),
                tokenIds = bucket.map { it.id },
                text = text,
                startMs = bucket.minOf { it.startMs },
                endMs = bucket.maxOf { it.endMs },
                speakerId = bucket.first().speakerId,
                language = bucket.first().language,
                isFinal = !draft,
            )
            bucket = mutableListOf()
        }
        for (token in tokens) {
            if (bucket.isNotEmpty() && shouldBreak(bucket.last(), token)) flush()
            bucket += token
        }
        flush()
        return segments
    }

    private fun shouldBreak(prev: TranscriptToken, next: TranscriptToken): Boolean {
        if (prev.speakerId != null && next.speakerId != null && prev.speakerId != next.speakerId) return true
        if (next.startMs - prev.endMs > 900) return true
        val t = prev.text.trimEnd()
        return t.endsWith('.') || t.endsWith('?') || t.endsWith('!') ||
            t.endsWith('。') || t.endsWith('？') || t.endsWith('！') || t.endsWith('\n')
    }

    private fun join(tokens: List<TranscriptToken>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            val text = token.text
            if (sb.isEmpty()) {
                sb.append(text)
            } else if (text.startsWith(' ') || text.startsWith('\n') || noSpaceLang(text) || noSpaceLang(sb.last())) {
                sb.append(text)
            } else {
                sb.append(' ').append(text)
            }
        }
        return sb.toString().trim()
    }

    private fun noSpaceLang(ch: Char): Boolean =
        ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af'

    private fun noSpaceLang(text: String): Boolean =
        text.isNotEmpty() && noSpaceLang(text.first())

    private fun segmentIdFor(tokens: List<TranscriptToken>): String =
        "seg-${tokens.first().id}-${tokens.last().id}"

    data class Snapshot(
        val finalTokens: List<TranscriptToken>,
        val interimTokens: List<TranscriptToken>,
        val segments: List<TranscriptSegment>,
    )

    companion object {
        const val DRAFT_SEGMENT_ID = "seg-draft"
    }
}

fun newToken(
    text: String,
    startMs: Long,
    endMs: Long,
    isFinal: Boolean,
    speakerId: String? = null,
    language: String? = null,
    confidence: Double? = null,
    id: String = newId("tok"),
    isNativeTranslation: Boolean = false,
    sourceLanguage: String? = null,
): TranscriptToken = TranscriptToken(
    id = id,
    text = text,
    startMs = startMs,
    endMs = endMs,
    isFinal = isFinal,
    speakerId = speakerId,
    language = language,
    confidence = confidence,
    sourceLanguage = sourceLanguage,
    isNativeTranslation = isNativeTranslation,
)
