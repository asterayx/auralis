package com.auralis.demo

import com.auralis.app.AuralisApp
import com.auralis.audio.AudioChunk
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.model.SessionMode
import com.auralis.pipeline.LiveSync
import com.auralis.pipeline.SessionPipeline
import com.auralis.provider.Presets
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
            val body = json.encodeToString(snapshot()).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/start") { exchange ->
            exchange.requestBody.close()
            val mode = if (exchange.requestURI.query?.contains("scribe") == true) {
                SessionMode.SCRIBE
            } else {
                SessionMode.TRANSLATOR
            }
            startLive(mode)
            val body = json.encodeToString(snapshot()).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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
        pump = scope.launch {
            app.persist { it.copy(activeProfileId = Presets.demo.id, bidirectional = true, diarization = true) }
            val live = app.startLive(mode)
            pipeline.set(live)
            repeat(48) { i ->
                live.pushAudio(
                    AudioChunk(
                        pcm16le = ByteArray(3200),
                        capturedAtMs = System.currentTimeMillis(),
                        streamOffsetMs = i * 100L,
                    ),
                )
                delay(220)
            }
            delay(500)
            val bundle = app.finishLive(live)
            pipeline.set(null)
            title.set(bundle.session.title)
            notes.set(app.postProcess(bundle.session.id).content)
        }
    }

    private fun snapshot(): DemoStateDto {
        val live = pipeline.get()?.state?.value
        val lines = if (live != null) {
            LiveSync.lines(live.segments, live.translations, live.speakers).map { it.toDto() }
        } else {
            emptyList()
        }
        val saved = kotlinx.coroutines.runBlocking { app.sessions.list().firstOrNull() }
        val savedBundle = saved?.let { kotlinx.coroutines.runBlocking { app.sessions.get(it.id) } }
        val savedLines = savedBundle?.let { LiveSync.lines(it.segments, it.translations, it.speakers).map { line -> line.toDto() } }
        return DemoStateDto(
            status = when {
                live != null -> "live"
                notes.get() != null -> "saved"
                else -> "idle"
            },
            mode = live?.session?.mode?.name ?: saved?.mode?.name ?: "TRANSLATOR",
            interim = live?.interimText.orEmpty(),
            focusedId = live?.focusedSegmentId ?: lines.lastOrNull()?.id,
            speakers = (live?.speakers ?: savedBundle?.speakers).orEmpty().map {
                SpeakerDto(it.id, it.displayName, it.colorHex)
            },
            lines = if (lines.isNotEmpty()) lines else savedLines.orEmpty(),
            notes = notes.get() ?: savedBundle?.postProcess?.lastOrNull()?.content,
            title = title.get() ?: saved?.title,
            export = savedBundle?.let { TranscriptExporter.export(it, ExportFormat.MARKDOWN) },
        )
    }

    private fun indexHtml(): String =
        javaClass.classLoader.getResource("demo.html")?.readText()
            ?: error("demo.html missing from classpath")

    private fun com.auralis.pipeline.SyncedCaption.toDto() = LineDto(
        id = segment.id,
        speaker = speaker?.displayName ?: "未知",
        color = speaker?.colorHex ?: "#7C9CFF",
        source = segment.text,
        translation = translation?.translatedText,
        direction = translation?.directionLabel,
        side = translation?.side?.name,
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
)

@Serializable
data class SpeakerDto(val id: String, val name: String, val color: String)

@Serializable
data class LineDto(
    val id: String,
    val speaker: String,
    val color: String,
    val source: String,
    val translation: String? = null,
    val direction: String? = null,
    val side: String? = null,
)
