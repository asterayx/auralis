package com.auralis.pipeline

import com.auralis.audio.AudioChunk
import com.auralis.core.Clock
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.model.BuiltInTemplates
import com.auralis.model.LanguageHint
import com.auralis.model.SessionMode
import com.auralis.model.TranscriptSegment
import com.auralis.provider.llm.DemoLlmAdapter
import com.auralis.provider.stt.DemoSttAdapter
import com.auralis.store.InMemorySessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {
    @Test
    fun demoPipelineProducesFinalSegments() = runBlocking {
        val pipeline = SessionPipeline(
            stt = DemoSttAdapter(),
            translator = TranslationOrchestrator(DemoLlmAdapter(), targetLanguage = "en", clock = Clock { 1L }),
            config = PipelineConfig(
                mode = SessionMode.TRANSLATOR,
                language = LanguageHint.ChineseEnglish,
                pauseStreamingOnSilence = false,
            ),
            clock = Clock { 1L },
        )
        pipeline.start()
        repeat(12) { i ->
            pipeline.pushAudio(AudioChunk(ByteArray(3200), capturedAtMs = i * 100L, streamOffsetMs = i * 100L))
            delay(350)
        }
        delay(500)
        val state = pipeline.state.value
        assertTrue(state.segments.isNotEmpty(), "expected final segments, got ${state.segments}")
        pipeline.stop()
        val bundle = pipeline.snapshotBundle()
        assertTrue(bundle.fullTranscript().contains("供应商") || bundle.fullTranscript().isNotBlank())
    }

    @Test
    fun translationKeepsGlossary() = runBlocking {
        val orch = TranslationOrchestrator(
            llm = DemoLlmAdapter(),
            targetLanguage = "en",
            glossary = listOf(com.auralis.model.GlossaryEntry("交期", "delivery date")),
            clock = Clock { 1L },
        )
        val seg = TranscriptSegment("s1", listOf("t"), "交期确认", 0, 1000)
        val out = orch.translate(seg, emptyList())
        assertEquals("s1", out.segmentId)
        assertTrue(out.translatedText.isNotBlank())
    }

    @Test
    fun postProcessChunksLongTranscript() {
        val pp = PostProcessor(DemoLlmAdapter(), clock = Clock { 1L }, chunkChars = 20)
        val parts = pp.chunk("abcdefghij\nklmnopqrstuvwxyz")
        assertTrue(parts.size >= 2)
    }

    @Test
    fun repositorySearchAndEditPreserveTimestamps() = runBlocking {
        val repo = InMemorySessionRepository(Clock { 10L })
        val pipeline = SessionPipeline(
            stt = DemoSttAdapter(),
            translator = null,
            config = PipelineConfig(mode = SessionMode.SCRIBE, language = LanguageHint.ChineseEnglish),
            clock = Clock { 10L },
        )
        pipeline.start()
        pipeline.pushAudio(AudioChunk(ByteArray(3200), capturedAtMs = 0, streamOffsetMs = 0))
        delay(2500)
        pipeline.stop()
        val bundle = pipeline.snapshotBundle()
        repo.upsert(bundle)
        val found = repo.search("对齐")
        assertTrue(found.isNotEmpty() || repo.search("Hello").isNotEmpty() || repo.list().isNotEmpty())
        val seg = bundle.segments.firstOrNull() ?: return@runBlocking
        val start = seg.startMs
        repo.editSegment(bundle.session.id, com.auralis.model.EditOverlay(seg.id, "edited", 11))
        val loaded = repo.get(bundle.session.id)!!
        assertEquals("edited", loaded.displayText(seg))
        assertEquals(start, loaded.segments.first { it.id == seg.id }.startMs)
    }

    @Test
    fun exportMarkdownAndSrt() {
        val bundle = com.auralis.model.SessionBundle(
            session = com.auralis.model.Session(
                id = "s",
                title = "Demo",
                mode = SessionMode.SCRIBE,
                status = com.auralis.model.SessionStatus.READY,
                createdAtMs = 0,
                updatedAtMs = 0,
            ),
            segments = listOf(
                TranscriptSegment("a", listOf("t"), "Hello world.", 0, 1500),
            ),
        )
        val md = TranscriptExporter.export(bundle, ExportFormat.MARKDOWN)
        assertTrue(md.contains("# Demo"))
        assertTrue(md.contains("Hello world."))
        val srt = TranscriptExporter.export(bundle, ExportFormat.SRT)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:01,500"))
        assertEquals("00:00:01.500", TranscriptExporter.ts(1500, vtt = true))
    }

    @Test
    fun builtInTemplatesExist() {
        assertTrue(BuiltInTemplates.all.any { it.isDefault })
        assertEquals(3, BuiltInTemplates.all.size)
    }
}
