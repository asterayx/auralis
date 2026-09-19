package com.auralis.demo

import com.auralis.app.AuralisApp
import com.auralis.audio.AudioChunk
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.model.SessionMode
import com.auralis.pipeline.LiveSync
import com.auralis.provider.Presets
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val web = args.contains("--web") || System.getenv("AURALIS_DEMO_WEB") == "1" || args.isEmpty()
    if (web && !args.contains("--cli")) {
        DemoWebServer(port = System.getenv("AURALIS_DEMO_PORT")?.toIntOrNull() ?: 43173).start()
        return
    }
    runCli()
}

private fun runCli() = runBlocking {
    println("Auralis demo — local-first BYOK voice workbench")
    println("Using the built-in demo providers (no API key).")
    println()

    val app = AuralisApp()
    app.load()
    app.persist { it.copy(activeProfileId = Presets.demo.id, bidirectional = true, diarization = true) }

    val pipeline = app.startLive(SessionMode.TRANSLATOR)
    repeat(40) { i ->
        pipeline.pushAudio(
            AudioChunk(
                pcm16le = ByteArray(3200),
                capturedAtMs = System.currentTimeMillis(),
                streamOffsetMs = i * 100L,
            ),
        )
        delay(220)
        val s = pipeline.state.value
        if (s.interimText.isNotBlank()) {
            print("\r[interim] ${s.interimText.take(80).padEnd(80)}")
        }
        LiveSync.lines(s.segments, s.translations, s.speakers).lastOrNull()?.let { line ->
            val who = line.speaker?.displayName ?: "?"
            val dir = line.translation?.directionLabel ?: ""
            println("\r[$who] ${line.segment.text}")
            line.translation?.let { println("          $dir → ${it.translatedText}") }
        }
    }
    delay(400)
    val bundle = app.finishLive(pipeline)
    println()
    println("Saved session: ${bundle.session.title}")
    println("Speakers: ${bundle.speakers.joinToString { it.displayName }}")
    val notes = app.postProcess(bundle.session.id)
    println()
    println(notes.content)
    println()
    println("--- Markdown export ---")
    println(TranscriptExporter.export(app.sessions.get(bundle.session.id)!!, ExportFormat.MARKDOWN))
    println("Estimated cost: $${bundle.usage?.estimatedUsd ?: 0.0} (estimate)")
}
