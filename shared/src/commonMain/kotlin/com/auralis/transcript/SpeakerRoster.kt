package com.auralis.transcript

import com.auralis.model.Speaker
import com.auralis.model.TranscriptSegment
import kotlin.math.absoluteValue

object SpeakerPalette {
    val hex = listOf("#7C9CFF", "#7DDBB6", "#F5C26B", "#F2A0B5", "#B4A3FF", "#8CD0F5")

    fun hexFor(id: String): String {
        val idx = (id.fold(0) { acc, c -> acc * 31 + c.code }.absoluteValue) % hex.size
        return hex[idx]
    }
}

object SpeakerRoster {
    fun fromSegments(
        segments: List<TranscriptSegment>,
        existing: List<Speaker> = emptyList(),
    ): List<Speaker> {
        val known = existing.associateBy { it.id }.toMutableMap()
        var nextIndex = existing.size
        for (id in segments.mapNotNull { it.speakerId }.filter { it.isNotBlank() }.distinct()) {
            if (id !in known) {
                nextIndex += 1
                known[id] = Speaker(
                    id = id,
                    displayName = "说话人 $nextIndex",
                    colorHex = SpeakerPalette.hexFor(id),
                )
            }
        }
        return known.values.toList()
    }

    fun rename(speakers: List<Speaker>, id: String, name: String): List<Speaker> =
        speakers.map { speaker ->
            if (speaker.id == id) speaker.copy(displayName = name.trim().ifBlank { speaker.displayName })
            else speaker
        }
}
