package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.core.newId
import com.auralis.error.ProviderError
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SttCapabilities
import com.auralis.transcript.newToken
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class SonioxSttAdapter(
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
        languageAuto = true,
        codeSwitching = true,
        customVocabulary = true,
        nativeTranslation = true,
        audioFormats = listOf("pcm_s16le", "auto"),
        sampleRates = listOf(16_000),
        languages = emptyList(),
        notes = "60+ languages, mid-sentence code switching, native one-way/two-way translation",
    )

    override suspend fun connect(config: SttSessionConfig): SttSession =
        LiveSession(client, resolved, config, json)

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
            val url = resolved.endpoint.baseUrl
            try {
                client.webSocket(urlString = url) {
                    toServer.send(Frame.Text(startMessage()))
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
                            t.message ?: "Soniox websocket failed",
                        ),
                    ),
                )
            }
        }

        private fun startMessage(): String = buildJsonObject {
            put("api_key", resolved.apiKey)
            put("model", resolved.endpoint.model)
            put("audio_format", "pcm_s16le")
            put("num_channels", 1)
            put("sample_rate", 16_000)
            put("enable_endpoint_detection", config.enableEndpointDetection)
            put("enable_speaker_diarization", config.diarization)
            put("enable_language_identification", config.language.autoDetect)
            if (config.language.codes.isNotEmpty()) {
                put("language_hints", buildJsonArray {
                    config.language.codes.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (config.vocabulary.isNotEmpty() || config.glossary.isNotEmpty()) {
                put("context", buildJsonObject {
                    if (config.vocabulary.isNotEmpty()) {
                        put("terms", buildJsonArray {
                            config.vocabulary.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                    if (config.glossary.isNotEmpty()) {
                        put("translation_terms", buildJsonArray {
                            config.glossary.forEach { entry ->
                                add(buildJsonObject {
                                    put("source", entry.source)
                                    put("target", entry.target)
                                })
                            }
                        })
                    }
                })
            }
            when {
                config.twoWayLanguages != null -> put(
                    "translation",
                    buildJsonObject {
                        put("type", "two_way")
                        put("language_a", config.twoWayLanguages.first)
                        put("language_b", config.twoWayLanguages.second)
                    },
                )
                config.nativeTranslationTarget != null -> put(
                    "translation",
                    buildJsonObject {
                        put("type", "one_way")
                        put("target_language", config.nativeTranslationTarget)
                    },
                )
            }
        }.toString()

        private suspend fun handleText(raw: String) {
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
            val errorCode = obj["error_code"]?.jsonPrimitive?.contentOrNull
            if (errorCode != null || obj["error_type"] != null) {
                val status = obj["error_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500
                val message = obj["error_message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["error_type"]?.jsonPrimitive?.contentOrNull
                    ?: raw
                _events.emit(
                    SttEvent.Failed(
                        ProviderError.fromHttp(ProviderError.Slot.STT, resolved.endpoint.id, status, message),
                    ),
                )
                return
            }
            if (obj["finished"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.emit(SttEvent.Closed("finished"))
                return
            }
            val tokens = (obj["tokens"] as? JsonArray)?.mapNotNull { el ->
                val t = el as? JsonObject ?: return@mapNotNull null
                val text = t["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val translationStatus = t["translation_status"]?.jsonPrimitive?.contentOrNull
                newToken(
                    text = text,
                    startMs = t["start_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    endMs = t["end_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    isFinal = t["is_final"]?.jsonPrimitive?.booleanOrNull ?: false,
                    speakerId = t["speaker"]?.jsonPrimitive?.contentOrNull,
                    language = t["language"]?.jsonPrimitive?.contentOrNull,
                    confidence = t["confidence"]?.jsonPrimitive?.doubleOrNull,
                    id = newId("sx"),
                    isNativeTranslation = translationStatus == "translation",
                    sourceLanguage = t["source_language"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
            if (tokens.isEmpty()) return
            val (native, source) = tokens.partition { it.isNativeTranslation }
            if (source.isNotEmpty()) {
                _events.emit(
                    SttEvent.Tokens(
                        source,
                        obj["total_audio_proc_ms"]?.jsonPrimitive?.longOrNull,
                    ),
                )
            }
            if (native.isNotEmpty()) {
                _events.emit(SttEvent.NativeTranslation(native))
            }
        }

        override suspend fun send(chunk: AudioChunk) {
            toServer.send(Frame.Binary(true, chunk.pcm16le))
        }

        override suspend fun finalizeUtterance() {
            toServer.send(Frame.Text("""{"type":"finalize"}"""))
        }

        override suspend fun close() {
            toServer.send(Frame.Binary(true, ByteArray(0)))
            toServer.close()
            job?.cancel()
        }
    }
}
