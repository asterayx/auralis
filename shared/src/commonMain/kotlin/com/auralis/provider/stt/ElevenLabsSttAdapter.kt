package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.core.newId
import com.auralis.error.ProviderError
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SttCapabilities
import com.auralis.transcript.newToken
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ElevenLabsSttAdapter(
    private val client: HttpClient,
    private val resolved: ResolvedEndpoint,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SttProvider {
    override val id: String = resolved.endpoint.id
    override val capabilities = SttCapabilities(
        streaming = true,
        interimResults = true,
        wordTimestamps = true,
        diarizationBatch = true,
        languageAuto = true,
        codeSwitching = true,
        customVocabulary = true,
        nativeTranslation = false,
        audioFormats = listOf("pcm_16000"),
        sampleRates = listOf(16_000),
        languages = emptyList(),
        notes = "Lowest-latency captions (~150ms). Diarization is batch-only.",
    )

    override suspend fun connect(config: SttSessionConfig): SttSession =
        LiveSession(client, resolved, config, json)

    @OptIn(ExperimentalEncodingApi::class)
    internal class LiveSession(
        private val client: HttpClient,
        private val resolved: ResolvedEndpoint,
        private val config: SttSessionConfig,
        private val json: Json,
    ) : SttSession {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val toServer = Channel<Frame>(Channel.BUFFERED)
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
            try {
                client.webSocket(
                    urlString = resolved.endpoint.baseUrl,
                    request = {
                        header("xi-api-key", resolved.apiKey)
                        parameter("model_id", resolved.endpoint.model)
                        parameter("audio_format", "pcm_16000")
                        parameter("commit_strategy", "vad")
                        parameter("include_timestamps", true)
                        parameter("include_language_detection", config.language.autoDetect)
                        config.language.codes.firstOrNull()?.let { parameter("language_code", it) }
                        config.language.codes.drop(1).forEach { parameter("secondary_languages", it) }
                        config.vocabulary.forEach { parameter("keyterms", it) }
                    },
                ) {
                    val sender = launch { drainOutgoing(::send, toServer) }
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
                            t.message ?: "ElevenLabs websocket failed",
                        ),
                    ),
                )
            }
        }

        private suspend fun handleText(raw: String) {
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
            when (obj["message_type"]?.jsonPrimitive?.contentOrNull) {
                "session_started" -> _events.emit(SttEvent.Ready())
                "partial_transcript", "committed_transcript", "committed_transcript_with_timestamps" -> {
                    val committed = obj["message_type"]?.jsonPrimitive?.contentOrNull?.startsWith("committed") == true
                    val words = obj["words"]?.jsonArray
                    val tokens = if (words != null && words.isNotEmpty()) {
                        words.mapNotNull { el ->
                            val w = el.jsonObject
                            val text = w["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val start = ((w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                            val end = ((w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                            newToken(text, start, end, committed, id = newId("el"))
                        }
                    } else {
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isBlank()) emptyList()
                        else listOf(newToken(text, 0, 0, committed))
                    }
                    if (tokens.isNotEmpty()) _events.emit(SttEvent.Tokens(tokens))
                    if (committed) _events.emit(SttEvent.SpeechEnded(tokens.maxOfOrNull { it.endMs } ?: 0))
                }
                "error" -> _events.emit(
                    SttEvent.Failed(
                        ProviderError.Unknown(
                            ProviderError.Slot.STT,
                            resolved.endpoint.id,
                            obj["error"]?.jsonPrimitive?.contentOrNull ?: raw,
                        ),
                    ),
                )
            }
        }

        override suspend fun send(chunk: AudioChunk) {
            val encoded = Base64.encode(chunk.pcm16le)
            val msg = buildJsonObject {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", encoded)
                put("commit", false)
                put("sample_rate", 16_000)
            }.toString()
            toServer.send(Frame.Text(msg))
        }

        override suspend fun finalizeUtterance() {
            toServer.send(Frame.Text("""{"message_type":"commit"}"""))
        }

        override suspend fun close() {
            toServer.send(Frame.Text("""{"message_type":"commit"}"""))
            toServer.close()
            job?.cancel()
        }
    }
}
