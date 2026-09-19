package com.auralis.provider

import kotlinx.serialization.Serializable

@Serializable
data class SttCapabilities(
    val streaming: Boolean,
    val interimResults: Boolean,
    val wordTimestamps: Boolean,
    val diarizationStreaming: Boolean = false,
    val diarizationBatch: Boolean = false,
    val languageAuto: Boolean = false,
    val codeSwitching: Boolean = false,
    val customVocabulary: Boolean = false,
    val nativeTranslation: Boolean = false,
    val audioFormats: List<String> = listOf("pcm_s16le"),
    val sampleRates: List<Int> = listOf(16_000),
    val languages: List<String> = emptyList(),
    val notes: String? = null,
) {
    val diarization: Boolean get() = diarizationStreaming || diarizationBatch
}

@Serializable
data class LlmCapabilities(
    val streaming: Boolean = true,
    val jsonMode: Boolean = true,
    val longContext: Boolean = true,
)
