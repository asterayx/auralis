package com.auralis.cost

import com.auralis.model.PriceRow
import com.auralis.model.UsageRecord
import kotlinx.serialization.Serializable

@Serializable
data class PriceTable(
    val rows: List<PriceRow> = defaults,
) {
    fun row(providerId: String, model: String?): PriceRow? =
        rows.firstOrNull { it.providerId == providerId && (model == null || it.model == model) }
            ?: rows.firstOrNull { it.providerId == providerId }

    companion object {
        val defaults = listOf(
            PriceRow("stt-soniox", "stt-rt-v5", usdPerAudioHour = 0.10),
            PriceRow("stt-elevenlabs", "scribe_v2_realtime", usdPerAudioHour = 0.40),
            PriceRow("stt-grok", "grok-voice-transcribe-2.0", usdPerAudioHour = 0.20),
            PriceRow("stt-openai-batch", "whisper-1", usdPerAudioHour = 0.36),
            PriceRow("llm-grok", "grok-4-1-fast-non-reasoning", usdPerMillionInputTokens = 0.20, usdPerMillionOutputTokens = 0.50),
            PriceRow("llm-gemini", "gemini-2.5-flash", usdPerMillionInputTokens = 0.15, usdPerMillionOutputTokens = 0.60),
            PriceRow("llm-openai", "gpt-4.1-mini", usdPerMillionInputTokens = 0.40, usdPerMillionOutputTokens = 1.60),
            PriceRow("llm-deepseek", "deepseek-chat", usdPerMillionInputTokens = 0.28, usdPerMillionOutputTokens = 0.42),
            PriceRow("llm-siliconflow", "Qwen/Qwen2.5-7B-Instruct", usdPerMillionInputTokens = 0.10, usdPerMillionOutputTokens = 0.10),
            PriceRow("llm-demo", "demo", usdPerMillionInputTokens = 0.0, usdPerMillionOutputTokens = 0.0),
            PriceRow("stt-demo", "demo", usdPerAudioHour = 0.0),
        )
    }
}

object CostEstimator {
    fun estimate(usage: UsageRecord, table: PriceTable): Double {
        var total = 0.0
        usage.sttProviderId?.let { pid ->
            val row = table.row(pid, usage.sttModel)
            val hourly = row?.usdPerAudioHour ?: 0.0
            total += hourly * (usage.audioMs / 3_600_000.0)
        }
        fun tokens(providerHint: String, input: Int, output: Int) {
            val row = table.row(providerHint, null) ?: return
            total += (input / 1_000_000.0) * (row.usdPerMillionInputTokens ?: 0.0)
            total += (output / 1_000_000.0) * (row.usdPerMillionOutputTokens ?: 0.0)
        }
        tokens("llm-translation-slot", usage.translationInputTokens, usage.translationOutputTokens)
        tokens("llm-post-slot", usage.llmInputTokens, usage.llmOutputTokens)
        // Fall back to a generic LLM row if slot-specific rows are absent
        if (usage.llmInputTokens + usage.translationInputTokens > 0) {
            val anyLlm = table.rows.firstOrNull { it.usdPerMillionInputTokens != null }
            if (anyLlm != null) {
                val already = total
                if (already == (table.row(usage.sttProviderId.orEmpty(), usage.sttModel)?.usdPerAudioHour ?: 0.0) *
                    (usage.audioMs / 3_600_000.0)
                ) {
                    total += (usage.llmInputTokens + usage.translationInputTokens) / 1_000_000.0 *
                        (anyLlm.usdPerMillionInputTokens ?: 0.0)
                    total += (usage.llmOutputTokens + usage.translationOutputTokens) / 1_000_000.0 *
                        (anyLlm.usdPerMillionOutputTokens ?: 0.0)
                }
            }
        }
        return (total * 10_000).toLong() / 10_000.0
    }

    fun estimateSimple(
        audioMs: Long,
        sttProviderId: String,
        llmInput: Int,
        llmOutput: Int,
        table: PriceTable = PriceTable(),
        llmProviderId: String = "llm-grok",
    ): Double {
        val stt = (table.row(sttProviderId, null)?.usdPerAudioHour ?: 0.0) * (audioMs / 3_600_000.0)
        val llmRow = table.row(llmProviderId, null)
        val llm = (llmInput / 1_000_000.0) * (llmRow?.usdPerMillionInputTokens ?: 0.0) +
            (llmOutput / 1_000_000.0) * (llmRow?.usdPerMillionOutputTokens ?: 0.0)
        return ((stt + llm) * 10_000).toLong() / 10_000.0
    }
}
