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
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Generic streaming adapter for OpenAI Realtime-style transcription sessions
 * and compatible gateways (Groq, self-hosted). Protocol:
 *   session.update → input_audio_buffer.append (base64 PCM) → transcription events.
 */
class OpenAiCompatibleStreamingSttAdapter(
    private val client: HttpClient,
    private val resolved: ResolvedEndpoint,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SttProvider {
    override val id: String = resolved.endpoint.id
    override val capabilities = SttCapabilities(
        streaming = true,
        interimResults = true,
        wordTimestamps = false,
        languageAuto = true,
        customVocabulary = true,
        audioFormats = listOf("pcm16"),
        sampleRates = listOf(16_000, 24_000),
        notes = "OpenAI Realtime transcription session protocol",
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
                        header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
                        header("OpenAI-Beta", "realtime=v1")
                        resolved.endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
                    },
                ) {
                    toServer.send(Frame.Text(sessionUpdate()))
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
                            t.message ?: "Realtime websocket failed",
                        ),
                    ),
                )
            }
        }

        private fun sessionUpdate(): String = buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "transcription")
                    put("audio", buildJsonObject {
                        put("input", buildJsonObject {
                            put("format", buildJsonObject {
                                put("type", "audio/pcm")
                                put("rate", 16_000)
                            })
                            put("transcription", buildJsonObject {
                                put("model", resolved.endpoint.model)
                                config.language.codes.firstOrNull()?.let { put("language", it) }
                                if (config.vocabulary.isNotEmpty()) {
                                    put("prompt", config.vocabulary.joinToString(", "))
                                }
                            })
                        })
                    })
                },
            )
        }.toString()

        private suspend fun handleText(raw: String) {
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "session.created", "session.updated" -> _events.emit(SttEvent.Ready())
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) {
                        _events.emit(SttEvent.Tokens(listOf(newToken(delta, 0, 0, isFinal = false, id = newId("rt")))))
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val text = obj["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotEmpty()) {
                        _events.emit(SttEvent.Tokens(listOf(newToken(text, 0, 0, isFinal = true))))
                    }
                }
                "error" -> {
                    val message = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: raw
                    _events.emit(
                        SttEvent.Failed(
                            ProviderError.Unknown(ProviderError.Slot.STT, resolved.endpoint.id, message),
                        ),
                    )
                }
            }
        }

        override suspend fun send(chunk: AudioChunk) {
            val msg = buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", Base64.encode(chunk.pcm16le))
            }.toString()
            toServer.send(Frame.Text(msg))
        }

        override suspend fun finalizeUtterance() {
            toServer.send(Frame.Text("""{"type":"input_audio_buffer.commit"}"""))
        }

        override suspend fun close() {
            toServer.close()
            job?.cancel()
        }
    }
}
