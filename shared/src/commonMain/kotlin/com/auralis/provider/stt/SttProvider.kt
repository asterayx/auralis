package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.error.ProviderError
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.TranscriptToken
import com.auralis.provider.SttCapabilities
import kotlinx.coroutines.flow.Flow

data class SttSessionConfig(
    val language: LanguageHint,
    val vocabulary: List<String> = emptyList(),
    val glossary: List<GlossaryEntry> = emptyList(),
    val diarization: Boolean = false,
    val nativeTranslationTarget: String? = null,
    val twoWayLanguages: Pair<String, String>? = null,
    val enableEndpointDetection: Boolean = true,
)

sealed class SttEvent {
    data class Ready(val requestId: String? = null) : SttEvent()
    data class Tokens(val tokens: List<TranscriptToken>, val audioProcessedMs: Long? = null) : SttEvent()
    data class NativeTranslation(val tokens: List<TranscriptToken>) : SttEvent()
    data class SpeechEnded(val atMs: Long) : SttEvent()
    data class Closed(val reason: String? = null, val expected: Boolean = false) : SttEvent()
    data class Failed(val error: ProviderError) : SttEvent()
}

interface SttSession {
    val events: Flow<SttEvent>
    suspend fun send(chunk: AudioChunk)
    suspend fun finalizeUtterance()
    suspend fun close()
}

interface SttProvider {
    val id: String
    val capabilities: SttCapabilities
    suspend fun connect(config: SttSessionConfig): SttSession
    suspend fun transcribeFile(bytes: ByteArray, fileName: String, config: SttSessionConfig): List<TranscriptToken> {
        throw UnsupportedOperationException("$id does not implement batch transcription")
    }
}
