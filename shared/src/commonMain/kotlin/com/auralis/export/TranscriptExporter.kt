package com.auralis.export

import com.auralis.core.formatFixed1
import com.auralis.core.pad2
import com.auralis.core.pad3
import com.auralis.model.SessionBundle
import com.auralis.model.TranscriptSegment

enum class ExportFormat { MARKDOWN, TXT, SRT, VTT, DOCX_SIMPLE }

object TranscriptExporter {
    fun export(bundle: SessionBundle, format: ExportFormat): String = when (format) {
        ExportFormat.MARKDOWN -> markdown(bundle)
        ExportFormat.TXT -> txt(bundle)
        ExportFormat.SRT -> srt(bundle)
        ExportFormat.VTT -> vtt(bundle)
        ExportFormat.DOCX_SIMPLE -> markdown(bundle)
    }

    fun markdown(bundle: SessionBundle): String = buildString {
        appendLine("# ${bundle.session.title}")
        appendLine()
        appendLine("- Mode: ${bundle.session.mode}")
        appendLine("- Created: ${bundle.session.createdAtMs}")
        bundle.usage?.let {
            appendLine("- Audio: ${formatFixed1(it.audioMs / 1000.0 / 60.0)} min")
            it.estimatedUsd?.let { usd -> appendLine("- Estimated cost: $$usd (estimate)") }
        }
        appendLine()
        appendLine("## Transcript")
        appendLine()
        val speakers = bundle.speakers.associateBy { it.id }
        bundle.segments.filter { it.isFinal }.forEach { seg ->
            val speaker = seg.speakerId?.let { id ->
                val name = speakers[id]?.displayName ?: "S$id"
                "**$name:** "
            } ?: ""
            appendLine("$speaker${bundle.displayText(seg)}")
            appendLine()
        }
        if (bundle.translations.isNotEmpty()) {
            appendLine("## Translation")
            appendLine()
            bundle.translations.filter { !it.isPreview }.forEach { tr ->
                appendLine("- [${tr.directionLabel}] ${tr.sourceText}")
                appendLine("  - ${tr.translatedText}")
                appendLine()
            }
        }
        bundle.postProcess.forEach { pp ->
            appendLine("## ${pp.templateName}")
            appendLine()
            appendLine(pp.content)
            appendLine()
        }
    }

    fun txt(bundle: SessionBundle): String = buildString {
        appendLine(bundle.session.title)
        appendLine()
        val speakers = bundle.speakers.associateBy { it.id }
        bundle.segments.filter { it.isFinal }.forEach { seg ->
            val speaker = seg.speakerId?.let { id ->
                "${speakers[id]?.displayName ?: "S$id"}: "
            } ?: ""
            appendLine(speaker + bundle.displayText(seg))
        }
    }

    fun srt(bundle: SessionBundle): String = cues(bundle, vtt = false)

    fun vtt(bundle: SessionBundle): String = "WEBVTT\n\n" + cues(bundle, vtt = true)

    private fun cues(bundle: SessionBundle, vtt: Boolean): String = buildString {
        bundle.segments.filter { it.isFinal }.forEachIndexed { index, seg ->
            appendLine(index + 1)
            appendLine("${ts(seg.startMs, vtt)} --> ${ts(seg.endMs, vtt)}")
            appendLine(bundle.displayText(seg))
            appendLine()
        }
    }

    fun ts(ms: Long, vtt: Boolean): String {
        val clamped = ms.coerceAtLeast(0)
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        val sep = if (vtt) '.' else ','
        return "${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}$sep${pad3(millis)}"
    }
}

fun SessionBundle.seekSegment(atMs: Long): TranscriptSegment? =
    segments.filter { it.isFinal }.lastOrNull { it.startMs <= atMs && atMs <= it.endMs }
        ?: segments.filter { it.isFinal }.minByOrNull { kotlin.math.abs(it.startMs - atMs) }

/** STT-4: format a caption timestamp for "click text → jump to audio". */
fun formatTimestamp(ms: Long): String = TranscriptExporter.ts(ms, vtt = false).dropLast(4)
