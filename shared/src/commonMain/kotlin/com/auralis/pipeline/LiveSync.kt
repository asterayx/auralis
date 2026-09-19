package com.auralis.pipeline

import com.auralis.model.Speaker
import com.auralis.model.TranscriptSegment
import com.auralis.model.TranslatedSegment

data class SyncedCaption(
    val segment: TranscriptSegment,
    val translation: TranslatedSegment?,
    val speaker: Speaker?,
)

object LiveSync {
    fun lines(
        segments: List<TranscriptSegment>,
        translations: List<TranslatedSegment>,
        speakers: List<Speaker>,
    ): List<SyncedCaption> {
        val bySegment = translations.associateBy { it.segmentId }
        val bySpeaker = speakers.associateBy { it.id }
        return segments
            .filter { it.isFinal }
            .sortedBy { it.startMs }
            .map { segment ->
                SyncedCaption(
                    segment = segment,
                    translation = bySegment[segment.id],
                    speaker = segment.speakerId?.let { bySpeaker[it] },
                )
            }
    }

    fun neighborId(lines: List<SyncedCaption>, focusedId: String?, delta: Int): String? {
        if (lines.isEmpty()) return null
        val current = lines.indexOfFirst { it.segment.id == focusedId }.let { if (it < 0) 0 else it }
        val next = (current + delta).coerceIn(0, lines.lastIndex)
        return lines[next].segment.id
    }
}
