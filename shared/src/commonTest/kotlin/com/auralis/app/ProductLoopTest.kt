package com.auralis.app

import com.auralis.audio.AudioChunk
import com.auralis.core.Clock
import com.auralis.export.ExportFormat
import com.auralis.export.formatTimestamp
import com.auralis.export.seekSegment
import com.auralis.model.BuiltInTemplates
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PromptTemplate
import com.auralis.model.SessionMode
import com.auralis.pipeline.SessionTitle
import com.auralis.provider.Presets
import com.auralis.provider.llm.DemoLlmAdapter
import com.auralis.store.JsonSessionRepository
import com.auralis.store.JsonSettingsStore
import com.auralis.store.MemoryTextStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductLoopTest {
    @Test
    fun jsonStoreSurvivesReload() = runBlocking {
        val disk = MemoryTextStore()
        val first = AuralisApp(
            sessions = JsonSessionRepository(disk, Clock { 1L }),
            settingsStore = JsonSettingsStore(disk),
            clock = Clock { 1L },
        )
        first.load()
        first.persist { it.copy(activeProfileId = Presets.demo.id, vocabulary = listOf("交期")) }
        val live = first.startLive(SessionMode.SCRIBE)
        live.pushAudio(AudioChunk(ByteArray(3200), capturedAtMs = 0, streamOffsetMs = 0))
        delay(2200)
        val saved = first.finishLive(live)
        assertTrue(saved.session.title.isNotBlank())

        val second = AuralisApp(
            sessions = JsonSessionRepository(disk, Clock { 2L }),
            settingsStore = JsonSettingsStore(disk),
            clock = Clock { 2L },
        )
        second.load()
        assertEquals(listOf("交期"), second.settings.value.vocabulary)
        assertNotNull(second.sessions.get(saved.session.id))
        assertTrue(second.sessions.search("对齐").isNotEmpty() || second.sessions.list().isNotEmpty())
    }

    @Test
    fun llmTitleAndUsageAndExport() = runBlocking {
        val app = AuralisApp(clock = Clock { 10L })
        app.load()
        app.persist { it.copy(activeProfileId = Presets.demo.id) }
        val live = app.startLive(SessionMode.TRANSLATOR)
        repeat(12) { i ->
            live.pushAudio(AudioChunk(ByteArray(3200), capturedAtMs = i * 100L, streamOffsetMs = i * 100L))
            delay(350)
        }
        delay(400)
        val bundle = app.finishLive(live)
        assertEquals("供应商对齐会", bundle.session.title)
        assertNotNull(bundle.usage)
        val txt = app.export(bundle, ExportFormat.TXT)
        assertTrue(txt.contains(bundle.session.title))
        val notes = app.postProcess(bundle.session.id, BuiltInTemplates.meetingNotes)
        assertTrue(notes.content.contains("Action") || notes.content.contains("Summary"))
        val after = app.sessions.get(bundle.session.id)!!
        assertTrue((after.usage?.llmInputTokens ?: 0) > 0)
    }

    @Test
    fun editKeepsTimestampsAndSeekFindsSegment() = runBlocking {
        val app = AuralisApp(clock = Clock { 3L })
        app.load()
        val live = app.startLive(SessionMode.SCRIBE)
        live.pushAudio(AudioChunk(ByteArray(3200), capturedAtMs = 0, streamOffsetMs = 0))
        delay(2200)
        val bundle = app.finishLive(live)
        val seg = bundle.segments.first()
        app.edit(bundle.session.id, seg.id, "edited caption")
        val loaded = app.sessions.get(bundle.session.id)!!
        assertEquals("edited caption", loaded.displayText(seg))
        assertEquals(seg.startMs, loaded.segments.first { it.id == seg.id }.startMs)
        val hit = loaded.seekSegment(seg.startMs + 1)
        assertEquals(seg.id, hit?.id)
        assertTrue(formatTimestamp(1500).contains("00:00:01"))
    }

    @Test
    fun templatesVocabularyGlossaryAndEndpoint() = runBlocking {
        val app = AuralisApp()
        app.load()
        app.saveTemplate(
            PromptTemplate(
                id = "tpl-custom",
                name = "Custom",
                description = "user",
                prompt = "Summarize briefly.",
                isDefault = true,
            ),
        )
        assertEquals("tpl-custom", app.defaultTemplate().id)
        app.setVocabulary(listOf("Auralis", "  "))
        app.setGlossary(listOf(GlossaryEntry("交期", "delivery date")))
        app.updateEndpoint(Presets.openaiLlm.id, baseUrl = "https://example.invalid/v1", model = "local-llm")
        val settings = app.settings.value
        assertEquals(listOf("Auralis"), settings.vocabulary)
        assertEquals("delivery date", settings.glossary.first().target)
        val ep = settings.endpoints.first { it.id == Presets.openaiLlm.id }
        assertEquals("https://example.invalid/v1", ep.baseUrl)
        assertEquals("local-llm", ep.model)
        assertEquals(LanguageHint.ChineseEnglish, settings.language)
    }

    @Test
    fun titleFallback() {
        assertEquals("Untitled session", SessionTitle.fallback("   \n"))
        assertEquals("Hello", SessionTitle.fallback("Hello\nWorld"))
    }

    @Test
    fun demoLlmTitlesTranscript() = runBlocking {
        val title = SessionTitle.generate(
            DemoLlmAdapter(),
            "大家好，我们开始今天的供应商对齐会。\nHello everyone",
        )
        assertEquals("供应商对齐会", title)
    }
}
