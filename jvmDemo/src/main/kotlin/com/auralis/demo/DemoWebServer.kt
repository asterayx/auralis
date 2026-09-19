package com.auralis.demo

import com.auralis.app.AuralisApp
import com.auralis.audio.AudioChunk
import com.auralis.export.ExportFormat
import com.auralis.export.formatTimestamp
import com.auralis.model.BuiltInTemplates
import com.auralis.model.SessionMode
import com.auralis.model.SessionStatus
import com.auralis.pipeline.LiveSync
import com.auralis.pipeline.SessionPipeline
import com.auralis.provider.Presets
import kotlin.math.PI
import kotlin.math.sin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class DemoWebServer(
    private val port: Int = 43173,
) {
    private val app = AuralisApp()
    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pipeline = AtomicReference<SessionPipeline?>(null)
    private val notes = AtomicReference<String?>(null)
    private val title = AtomicReference<String?>(null)
    private val seekLabel = AtomicReference<String?>(null)
    private val lastSessionId = AtomicReference<String?>(null)
    private var pump: Job? = null

    fun start() {
        kotlinx.coroutines.runBlocking { app.load() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { exchange ->
            val body = indexHtml().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/state") { exchange ->
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/start") { exchange ->
            exchange.requestBody.close()
            val mode = if (exchange.requestURI.query?.contains("scribe") == true) {
                SessionMode.SCRIBE
            } else {
                SessionMode.TRANSLATOR
            }
            startLive(mode)
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/edit") { exchange ->
            val params = query(exchange)
            val sessionId = params["sessionId"] ?: lastSessionId.get()
            val segmentId = params["segmentId"]
            val text = params["text"].orEmpty()
            if (sessionId != null && segmentId != null && text.isNotBlank()) {
                kotlinx.coroutines.runBlocking { app.edit(sessionId, segmentId, text) }
            }
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/notes") { exchange ->
            val templateId = query(exchange)["template"] ?: BuiltInTemplates.meetingNotes.id
            val sessionId = lastSessionId.get()
            if (sessionId != null) {
                val template = app.settings.value.templates.firstOrNull { it.id == templateId }
                    ?: BuiltInTemplates.meetingNotes
                notes.set(kotlinx.coroutines.runBlocking { app.postProcess(sessionId, template).content })
            }
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/seek") { exchange ->
            val params = query(exchange)
            val segmentId = params["segmentId"]
            val sessionId = params["sessionId"] ?: lastSessionId.get()
            if (sessionId != null && segmentId != null) {
                val state = kotlinx.coroutines.runBlocking { app.seekToSegment(sessionId, segmentId) }
                seekLabel.set(state.label)
            }
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/crash") { exchange ->
            exchange.requestBody.close()
            crashLive()
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/catchup") { exchange ->
            exchange.requestBody.close()
            kotlinx.coroutines.runBlocking {
                app.pendingSessions().forEach { session ->
                    runCatching { app.catchUp(session.id) }.onSuccess { saved ->
                        lastSessionId.set(saved.session.id)
                        title.set(saved.session.title)
                        notes.set(saved.postProcess.lastOrNull()?.content)
                    }
                }
                lastSessionId.get()?.let { id ->
                    if (notes.get() == null) {
                        notes.set(app.postProcess(id).content)
                    }
                }
            }
            jsonOk(exchange, snapshot())
        }
        server.createContext("/api/audio") { exchange ->
            val sessionId = query(exchange)["sessionId"] ?: lastSessionId.get()
            val wav = sessionId?.let { app.audioWav(it) }
            if (wav == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.responseHeaders.add("Cache-Control", "no-store")
                exchange.sendResponseHeaders(200, wav.size.toLong())
                exchange.responseBody.use { it.write(wav) }
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        startLive(SessionMode.TRANSLATOR)
        println("Auralis demo preview  http://127.0.0.1:$port")
        println("Using built-in demo providers (no API key). Ctrl+C to stop.")
        Thread.currentThread().join()
    }

    private fun startLive(mode: SessionMode) {
        pump?.cancel()
        notes.set(null)
        title.set(null)
        seekLabel.set(null)
        lastSessionId.set(null)
        pump = scope.launch {
            app.persist { it.copy(activeProfileId = Presets.demo.id, bidirectional = true, diarization = true) }
            val live = app.startLive(mode)
            pipeline.set(live)
            lastSessionId.set(live.state.value.session.id)
            repeat(48) { i ->
                app.feed(live, toneChunk(i * 100L))
                delay(220)
            }
            delay(500)
            val bundle = app.finishLive(live)
            pipeline.set(null)
            lastSessionId.set(bundle.session.id)
            title.set(bundle.session.title)
            notes.set(app.postProcess(bundle.session.id).content)
        }
    }

    private fun crashLive() {
        pump?.cancel()
        val live = pipeline.getAndSet(null) ?: return
        val saved = kotlinx.coroutines.runBlocking { app.abandonLive(live) }
        lastSessionId.set(saved.session.id)
        title.set(saved.session.title)
        notes.set(null)
        seekLabel.set("会话已中断，录音仍在本地。点「联网补转写」继续。")
    }

    private fun toneChunk(offsetMs: Long, durationMs: Long = 100): AudioChunk {
        val samples = ((16_000 * durationMs) / 1000).toInt()
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val t = offsetMs / 1000.0 + i / 16_000.0
            val v = (sin(2 * PI * 440.0 * t) * 4200).toInt()
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        return AudioChunk(pcm, capturedAtMs = System.currentTimeMillis(), streamOffsetMs = offsetMs)
    }

    private fun lastBundle() = lastSessionId.get()?.let { kotlinx.coroutines.runBlocking { app.sessions.get(it) } }

    private fun snapshot(): DemoStateDto {
        val live = pipeline.get()?.state?.value
        val lines = if (live != null) {
            LiveSync.lines(live.segments, live.translations, live.speakers).map { it.toDto() }
        } else {
            emptyList()
        }
        val saved = lastBundle()
        val savedLines = saved?.let { LiveSync.lines(it.segments, it.translations, it.speakers).map { line -> line.toDto(it.displayText(line.segment)) } }
        val usage = saved?.usage
        val pending = kotlinx.coroutines.runBlocking { app.pendingSessions() }
        val playback = app.playback.state.value
        return DemoStateDto(
            status = when {
                live != null -> "live"
                saved?.session?.status == SessionStatus.OFFLINE_PENDING -> "pending"
                notes.get() != null || saved != null -> "saved"
                else -> "idle"
            },
            mode = live?.session?.mode?.name ?: saved?.session?.mode?.name ?: "TRANSLATOR",
            interim = live?.interimText.orEmpty(),
            focusedId = live?.focusedSegmentId ?: lines.lastOrNull()?.id,
            speakers = (live?.speakers ?: saved?.speakers).orEmpty().map {
                SpeakerDto(it.id, it.displayName, it.colorHex)
            },
            lines = if (lines.isNotEmpty()) lines else savedLines.orEmpty(),
            notes = notes.get() ?: saved?.postProcess?.lastOrNull()?.content,
            title = title.get() ?: saved?.session?.title,
            export = saved?.let { app.export(it, ExportFormat.MARKDOWN) },
            exportTxt = saved?.let { app.export(it, ExportFormat.TXT) },
            sessionId = saved?.session?.id ?: live?.session?.id ?: lastSessionId.get(),
            seekLabel = playback.label ?: seekLabel.get(),
            seekMs = playback.positionMs,
            pendingCount = pending.size,
            sessionStatus = live?.session?.status?.name ?: saved?.session?.status?.name,
            usage = usage?.let {
                "音频 ${"%.1f".format(it.audioMs / 60000.0)} 分钟 · " +
                    "LLM ${(it.llmInputTokens + it.translationInputTokens)}→${(it.llmOutputTokens + it.translationOutputTokens)} tokens" +
                    (it.estimatedUsd?.let { usd -> " · $$usd（估算）" } ?: "")
            },
            templates = app.settings.value.templates.map { TemplateDto(it.id, it.name, it.isDefault) },
        )
    }

    private fun indexHtml(): String =
        javaClass.classLoader.getResource("demo.html")?.readText()
            ?: error("demo.html missing from classpath")

    private fun query(exchange: HttpExchange): Map<String, String> {
        val raw = exchange.requestURI.query.orEmpty()
        exchange.requestBody.close()
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
            key to value
        }.toMap()
    }

    private fun jsonOk(exchange: HttpExchange, state: DemoStateDto) {
        val body = json.encodeToString(state).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun com.auralis.pipeline.SyncedCaption.toDto(sourceOverride: String? = null) = LineDto(
        id = segment.id,
        speaker = speaker?.displayName ?: "未知",
        color = speaker?.colorHex ?: "#7C9CFF",
        source = sourceOverride ?: segment.text,
        translation = translation?.translatedText,
        direction = translation?.directionLabel,
        side = translation?.side?.name,
        startLabel = formatTimestamp(segment.startMs),
        startMs = segment.startMs,
    )
}

@Serializable
data class DemoStateDto(
    val status: String,
    val mode: String,
    val interim: String,
    val focusedId: String? = null,
    val speakers: List<SpeakerDto> = emptyList(),
    val lines: List<LineDto> = emptyList(),
    val notes: String? = null,
    val title: String? = null,
    val export: String? = null,
    val exportTxt: String? = null,
    val sessionId: String? = null,
    val seekLabel: String? = null,
    val seekMs: Long = 0,
    val pendingCount: Int = 0,
    val sessionStatus: String? = null,
    val usage: String? = null,
    val templates: List<TemplateDto> = emptyList(),
)

@Serializable
data class SpeakerDto(val id: String, val name: String, val color: String)

@Serializable
data class TemplateDto(val id: String, val name: String, val isDefault: Boolean)

@Serializable
data class LineDto(
    val id: String,
    val speaker: String,
    val color: String,
    val source: String,
    val translation: String? = null,
    val direction: String? = null,
    val side: String? = null,
    val startLabel: String? = null,
    val startMs: Long = 0,
)
