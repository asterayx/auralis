package com.auralis.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmenterTest {
    @Test
    fun interimThenFinalPromotesSentence() {
        val s = Segmenter()
        s.ingest(listOf(newToken("Hello", 0, 200, isFinal = false)))
        val draft = s.snapshot()
        assertEquals(1, draft.segments.size)
        assertFalse(draft.segments.single().isFinal)

        s.ingest(listOf(newToken("Hello", 0, 200, isFinal = true, id = "t1")))
        val done = s.snapshot()
        assertTrue(done.segments.single().isFinal)
        assertEquals("Hello", done.segments.single().text)
        assertTrue(done.interimTokens.isEmpty())
    }

    @Test
    fun punctuationAndSpeakerSplitSegments() {
        val s = Segmenter()
        s.ingest(
            listOf(
                newToken("Hi.", 0, 200, true, speakerId = "1", id = "a"),
                newToken("Yes", 400, 700, true, speakerId = "2", id = "b"),
                newToken("we can.", 700, 1100, true, speakerId = "2", id = "c"),
            ),
        )
        val segs = s.snapshot().segments.filter { it.isFinal }
        assertEquals(2, segs.size)
        assertEquals("Hi.", segs[0].text)
        assertEquals("Yes we can.", segs[1].text)
    }

    @Test
    fun duplicateTokenIdsAreIgnored() {
        val s = Segmenter()
        val first = newToken("Hello.", 0, 200, true, id = "tok-demo-0")
        s.ingest(listOf(first))
        s.ingest(listOf(first.copy(text = "Hello.")))
        assertEquals(1, s.snapshot().finalTokens.size)
        assertEquals(1, s.snapshot().segments.filter { it.isFinal }.size)
    }

    @Test
    fun chineseDoesNotInsertSpaces() {
        val s = Segmenter()
        s.ingest(
            listOf(
                newToken("大家", 0, 200, true, id = "1"),
                newToken("好。", 200, 400, true, id = "2"),
            ),
        )
        assertEquals("大家好。", s.snapshot().segments.single().text)
    }
}
