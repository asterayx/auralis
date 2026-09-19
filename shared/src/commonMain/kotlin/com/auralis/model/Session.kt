package com.auralis.model

import kotlinx.serialization.Serializable

enum class SessionMode {
    SCRIBE,
    TRANSLATOR,
}

enum class SessionStatus {
    RECORDING,
    PAUSED,
    OFFLINE_PENDING,
    PROCESSING,
    READY,
    FAILED,
}

@Serializable
data class Session(
    val id: String,
    val title: String,
    val mode: SessionMode,
    val status: SessionStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long? = null,
    val language: LanguageHint = LanguageHint.ChineseEnglish,
    val keepAudio: Boolean = true,
    val audioPath: String? = null,
    val audioDurationMs: Long = 0,
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val profileId: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
)

@Serializable
data class SessionBundle(
    val session: Session,
    val tokens: List<TranscriptToken> = emptyList(),
    val segments: List<TranscriptSegment> = emptyList(),
    val edits: List<EditOverlay> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val translations: List<TranslatedSegment> = emptyList(),
    val postProcess: List<PostProcessResult> = emptyList(),
    val usage: UsageRecord? = null,
) {
    fun displayText(segment: TranscriptSegment): String =
        edits.firstOrNull { it.segmentId == segment.id }?.editedText ?: segment.text

    fun fullTranscript(finalOnly: Boolean = true): String =
        segments
            .filter { !finalOnly || it.isFinal }
            .sortedBy { it.startMs }
            .joinToString("\n") { displayText(it) }
}
