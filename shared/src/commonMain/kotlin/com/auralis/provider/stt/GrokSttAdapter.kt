package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.core.newId
import com.auralis.error.ProviderError
import com.auralis.model.TranscriptToken
import com.auralis.provider.Presets
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SttCapabilities
import com.auralis.transcript.newToken
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GrokSttAdapter(
    private val client: HttpClient,
    private val resolved: ResolvedEndpoint,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SttProvider {
    override val id: String = resolved.endpoint.id
    override val capabilities = SttCapabilities(
        streaming = true,
        interimResults = true,
        wordTimestamps = true,
        diarizationStreaming = true,
        diarizationBatch = true,
        languageAuto = false,
        codeSwitching = false,
        customVocabulary = true,
        nativeTranslation = false,
        audioFormats = listOf("pcm", "mulaw", "alaw", "opus"),
        sampleRates = listOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000),
        languages = GROK_LANGUAGES,
        notes = Presets.grokSttLanguageNote,
    )

    override suspend fun connect(config: SttSessionConfig): SttSession =
        LiveSession(client, resolved, config, json)

    override suspend fun transcribeFile(
        bytes: ByteArray,
        fileName: String,
        config: SttSessionConfig,
    ): List<TranscriptToken> {
        // Batch path is implemented by OpenAI-compatible multipart against https://api.x.ai/v1/stt
        throw UnsupportedOperationException("Use OpenAiCompatibleBatchSttAdapter against https://api.x.ai/v1 for files")
    }

    internal class LiveSession(
        private val client: HttpClient,
        private val resolved: ResolvedEndpoint,
        private val config: SttSessionConfig,
        private val json: Json,
    ) : SttSession {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val toServer = Channel<Frame>(Channel.BUFFERED)
        private val ready = CompletableDeferred<Unit>()
        private val _events = MutableSharedFlow<SttEvent>(
            extraBufferCapacity = 128,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val events = _events.asSharedFlow()
        private var job: Job? = null

        init {
            job = scope.launch { run() }
        }

        private suspend fun run() {
            val url = resolved.endpoint.baseUrl
            try {
                client.webSocket(
                    urlString = url,
                    request = {
                        header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
                        parameter("sample_rate", 16_000)
                        parameter("encoding", "pcm")
                        parameter("interim_results", true)
                        parameter("diarize", config.diarization)
                        parameter("model", resolved.endpoint.model)
                        config.language.codes.firstOrNull()?.let { parameter("language", it) }
                        config.vocabulary.forEach { parameter("keyterm", it) }
                    },
                ) {
                    val sender = launch {
                        ready.await()
                        drainOutgoing(::send, toServer)
                    }
                    receiveFrames { frame ->
                        if (frame is Frame.Text) handleText(frame.readText())
                    }
                    sender.cancel()
                }
                _events.emit(SttEvent.Closed())
            } catch (t: Throwable) {
                _events.emit(
                    SttEvent.Failed(
                        ProviderError.Network(
                            ProviderError.Slot.STT,
                            resolved.endpoint.id,
                            t.message ?: "Grok STT websocket failed",
                        ),
                    ),
                )
            }
        }

        private suspend fun handleText(raw: String) {
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "transcript.created" -> {
                    ready.complete(Unit)
                    _events.emit(SttEvent.Ready())
                }
                "error" -> {
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: raw
                    _events.emit(
                        SttEvent.Failed(
                            ProviderError.fromHttp(ProviderError.Slot.STT, resolved.endpoint.id, status, message),
                        ),
                    )
                }
                "transcript.partial", "transcript.done" -> {
                    val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull
                        ?: (obj["type"]?.jsonPrimitive?.contentOrNull == "transcript.done")
                    val speechFinal = obj["speech_final"]?.jsonPrimitive?.booleanOrNull == true
                    val words = obj["words"]?.jsonArray
                    val tokens = if (words != null && words.isNotEmpty()) {
                        words.mapNotNull { el ->
                            val w = el.jsonObject
                            val text = w["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val start = ((w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                            val end = ((w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                            newToken(
                                text = text,
                                startMs = start,
                                endMs = end,
                                isFinal = isFinal,
                                speakerId = w["speaker"]?.jsonPrimitive?.contentOrNull,
                                language = obj["language"]?.jsonPrimitive?.contentOrNull,
                                id = newId("gk"),
                            )
                        }
                    } else {
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isBlank()) emptyList()
                        else listOf(
                            newToken(
                                text = text,
                                startMs = ((obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                                endMs = (((obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) +
                                    (obj["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0)) * 1000).toLong(),
                                isFinal = isFinal,
                                language = obj["language"]?.jsonPrimitive?.contentOrNull,
                            ),
                        )
                    }
                    if (tokens.isNotEmpty()) _events.emit(SttEvent.Tokens(tokens))
                    if (speechFinal) {
                        val at = tokens.maxOfOrNull { it.endMs } ?: 0L
                        _events.emit(SttEvent.SpeechEnded(at))
                    }
                }
            }
        }

        override suspend fun send(chunk: AudioChunk) {
            toServer.send(Frame.Binary(true, chunk.pcm16le))
        }

        override suspend fun finalizeUtterance() {
            toServer.send(Frame.Text("""{"type":"audio.done"}"""))
        }

        override suspend fun close() {
            toServer.send(Frame.Text("""{"type":"audio.done"}"""))
            toServer.close()
            job?.cancel()
        }
    }

    companion object {
        val GROK_LANGUAGES = listOf(
            "ar", "cs", "da", "nl", "en", "fil", "fr", "de", "hi", "id",
            "it", "ja", "ko", "mk", "ms", "fa", "pl", "pt", "ro", "ru",
            "es", "sv", "th", "tr", "vi",
        )
    }
}
