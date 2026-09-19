package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.audio.EnergyVad
import com.auralis.audio.Pcm
import com.auralis.audio.Vad
import com.auralis.core.newId
import com.auralis.error.ProviderError
import com.auralis.model.TranscriptToken
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SttCapabilities
import com.auralis.transcript.newToken
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Generic OpenAI-compatible `/v1/audio/transcriptions` (or `/v1/stt`) adapter.
 * Live sessions are pseudo-streaming: VAD slices + one request per utterance.
 */
class OpenAiCompatibleBatchSttAdapter(
    private val client: HttpClient,
    private val resolved: ResolvedEndpoint,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val path: String = "/audio/transcriptions",
    private val vadFactory: () -> Vad = { EnergyVad() },
) : SttProvider {
    override val id: String = resolved.endpoint.id
    override val capabilities = SttCapabilities(
        streaming = false,
        interimResults = false,
        wordTimestamps = true,
        diarizationBatch = false,
        languageAuto = true,
        customVocabulary = true,
        audioFormats = listOf("wav", "mp3", "m4a", "pcm_s16le"),
        sampleRates = listOf(16_000),
        notes = "Pseudo-streaming via local VAD. Expected subtitle lag 2–4s.",
    )

    override suspend fun connect(config: SttSessionConfig): SttSession =
        VadSession(client, resolved, config, json, path, vadFactory())

    override suspend fun transcribeFile(
        bytes: ByteArray,
        fileName: String,
        config: SttSessionConfig,
    ): List<TranscriptToken> = transcribeOnce(client, resolved, json, path, bytes, fileName, config, offsetMs = 0)

    internal class VadSession(
        private val client: HttpClient,
        private val resolved: ResolvedEndpoint,
        private val config: SttSessionConfig,
        private val json: Json,
        private val path: String,
        private val vad: Vad,
    ) : SttSession {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val _events = MutableSharedFlow<SttEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val events = _events.asSharedFlow()
        private val pcm = ArrayList<Byte>()
        private var speech = false
        private var sliceStartMs = 0L
        private var lastOffset = 0L
        private var job: Job? = null

        init {
            job = scope.launch { _events.emit(SttEvent.Ready()) }
        }

        override suspend fun send(chunk: AudioChunk) {
            lastOffset = chunk.streamOffsetMs
            val voiced = vad.isSpeech(chunk)
            if (voiced) {
                if (!speech) {
                    speech = true
                    sliceStartMs = chunk.streamOffsetMs
                    pcm.clear()
                }
                chunk.pcm16le.forEach { pcm.add(it) }
            } else if (speech) {
                flush()
            }
        }

        override suspend fun finalizeUtterance() = flush()

        override suspend fun close() {
            flush()
            job?.cancel()
            _events.emit(SttEvent.Closed(expected = true))
        }

        private suspend fun flush() {
            if (!speech || pcm.isEmpty()) {
                speech = false
                return
            }
            speech = false
            val bytes = pcm.toByteArray()
            pcm.clear()
            val wav = Pcm.toWav(bytes, 16_000)
            try {
                val tokens = transcribeOnce(
                    client, resolved, json, path, wav, "slice.wav", config, sliceStartMs,
                )
                if (tokens.isNotEmpty()) _events.emit(SttEvent.Tokens(tokens))
                _events.emit(SttEvent.SpeechEnded(lastOffset))
            } catch (e: ProviderError) {
                _events.emit(SttEvent.Failed(e))
            }
        }
    }

    companion object {
        suspend fun transcribeOnce(
            client: HttpClient,
            resolved: ResolvedEndpoint,
            json: Json,
            path: String,
            bytes: ByteArray,
            fileName: String,
            config: SttSessionConfig,
            offsetMs: Long,
        ): List<TranscriptToken> {
            val url = resolved.endpoint.baseUrl.trimEnd('/') +
                if (path.startsWith("/")) path else "/$path"
            val response = client.submitFormWithBinaryData(
                url = url,
                formData = formData {
                    append("model", resolved.endpoint.model)
                    config.language.codes.firstOrNull()?.let { append("language", it) }
                    if (config.vocabulary.isNotEmpty()) {
                        append("prompt", config.vocabulary.joinToString(", "))
                    }
                    append("response_format", "verbose_json")
                    append("timestamp_granularities[]", "word")
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "audio/wav")
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
                resolved.endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw ProviderError.fromHttp(
                    ProviderError.Slot.STT,
                    resolved.endpoint.id,
                    response.status.value,
                    body.take(400),
                )
            }
            return parseVerboseJson(json, body, offsetMs)
        }

        fun parseVerboseJson(json: Json, body: String, offsetMs: Long): List<TranscriptToken> {
            val obj = json.parseToJsonElement(body).jsonObject
            val words = obj["words"]?.jsonArray
            if (words != null && words.isNotEmpty()) {
                return words.map { el ->
                    val w = el.jsonObject
                    val start = ((w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                    val end = ((w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                    newToken(
                        text = w["text"]?.jsonPrimitive?.contentOrNull
                            ?: w["word"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        startMs = offsetMs + start,
                        endMs = offsetMs + end,
                        isFinal = true,
                        language = obj["language"]?.jsonPrimitive?.contentOrNull,
                        id = newId("oa"),
                    )
                }
            }
            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isBlank()) return emptyList()
            return listOf(
                newToken(
                    text = text,
                    startMs = offsetMs,
                    endMs = offsetMs,
                    isFinal = true,
                    language = obj["language"]?.jsonPrimitive?.contentOrNull,
                ),
            )
        }

        fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray =
            Pcm.toWav(pcm, sampleRate, channels)
    }
}
