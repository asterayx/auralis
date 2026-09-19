package com.auralis.transcript

import com.auralis.model.TranscriptSegment
import com.auralis.pipeline.LiveSync
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerRosterTest {
    @Test
    fun assignsStableNamesAndColors() {
        val segments = listOf(
            TranscriptSegment("a", listOf("t1"), "Hi", 0, 100, speakerId = "1"),
            TranscriptSegment("b", listOf("t2"), "Hello", 120, 220, speakerId = "2"),
            TranscriptSegment("c", listOf("t3"), "Again", 240, 300, speakerId = "1"),
        )
        val speakers = SpeakerRoster.fromSegments(segments)
        assertEquals(listOf("1", "2"), speakers.map { it.id })
        assertEquals("说话人 1", speakers[0].displayName)
        assertEquals("说话人 2", speakers[1].displayName)
        assertEquals(SpeakerPalette.hexFor("1"), speakers[0].colorHex)
        val renamed = SpeakerRoster.rename(speakers, "1", "采购")
        assertEquals("采购", renamed.first { it.id == "1" }.displayName)
        assertEquals("说话人 2", renamed.first { it.id == "2" }.displayName)
    }

    @Test
    fun liveSyncPairsAndNeighbors() {
        val segments = listOf(
            TranscriptSegment("a", listOf("t1"), "Hi", 0, 100, speakerId = "1"),
            TranscriptSegment("b", listOf("t2"), "Hello", 120, 220, speakerId = "2"),
        )
        val speakers = SpeakerRoster.fromSegments(segments)
        val translations = listOf(
            com.auralis.model.TranslatedSegment(
                segmentId = "a",
                sourceText = "Hi",
                translatedText = "你好",
                providerId = "demo",
                model = "demo",
                updatedAtMs = 1,
            ),
        )
        val lines = LiveSync.lines(segments, translations, speakers)
        assertEquals(2, lines.size)
        assertEquals("你好", lines[0].translation?.translatedText)
        assertEquals("说话人 1", lines[0].speaker?.displayName)
        assertEquals("b", LiveSync.neighborId(lines, "a", 1))
        assertEquals("a", LiveSync.neighborId(lines, "a", -1))
    }
}
