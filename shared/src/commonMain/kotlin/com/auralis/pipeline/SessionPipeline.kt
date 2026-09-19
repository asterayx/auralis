package com.auralis.pipeline

import com.auralis.audio.AudioChunk
import com.auralis.audio.AudioRingBuffer
import com.auralis.audio.EnergyVad
import com.auralis.audio.Vad
import com.auralis.core.Clock
import com.auralis.core.newId
import com.auralis.error.ProviderError
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionMode
import com.auralis.model.SessionStatus
import com.auralis.model.Speaker
import com.auralis.model.TranscriptSegment
import com.auralis.model.TranscriptToken
import com.auralis.model.TranslatedSegment
import com.auralis.model.UsageRecord
import com.auralis.transcript.SpeakerRoster
import com.auralis.provider.SttCapabilities
import com.auralis.provider.stt.SttEvent
import com.auralis.provider.stt.SttProvider
import com.auralis.provider.stt.SttSession
import com.auralis.provider.stt.SttSessionConfig
import com.auralis.transcript.Segmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PipelineConfig(
    val mode: SessionMode,
    val language: LanguageHint,
    val targetLanguage: String = "en",
    val vocabulary: List<String> = emptyList(),
    val glossary: List<GlossaryEntry> = emptyList(),
    val diarization: Boolean = false,
    val preferNativeTranslation: Boolean = true,
    val previewInterimTranslation: Boolean = false,
    val pauseStreamingOnSilence: Boolean = true,
    val keepAudio: Boolean = true,
    val bidirectional: Boolean = false,
    val localLanguage: String = "zh",
    val remoteLanguage: String = "en",
)

data class LivePipelineState(
    val session: Session,
    val tokens: List<TranscriptToken> = emptyList(),
    val segments: List<TranscriptSegment> = emptyList(),
    val translations: List<TranslatedSegment> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val focusedSegmentId: String? = null,
    val interimText: String = "",
    val statusMessage: String? = null,
    val lastError: ProviderError? = null,
    val connected: Boolean = false,
    val audioMs: Long = 0,
)

/**
 * Single pipeline used by Scribe and Translator. Audio is always written
 * locally by the platform layer; this class only consumes PCM and drives
 * STT / translation.
 */
class SessionPipeline(
    private val stt: SttProvider,
    private val translator: TranslationOrchestrator?,
    private val config: PipelineConfig,
    private val clock: Clock = Clock.System,
    private val vad: Vad = EnergyVad(),
    private val fallbackStt: SttProvider? = null,
) {
    val capabilities: SttCapabilities = stt.capabilities
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val segmenter = Segmenter()
    private val ring = AudioRingBuffer()
    private val sendMutex = Mutex()
    private var session: SttSession? = null
    private var collector: Job? = null
    private var reconnects = 0
    private var lastSentOffset = 0L
    private var usingFallback = false
    private val translatedIds = mutableSetOf<String>()
    private val nativeByTime = mutableListOf<TranscriptToken>()

    private val sessionId = newId("ses")
    private val createdAt = clock.nowMs()
    private val _state = MutableStateFlow(
        LivePipelineState(
            session = Session(
                id = sessionId,
                title = "Untitled session",
                mode = config.mode,
                status = SessionStatus.RECORDING,
                createdAtMs = createdAt,
                updatedAtMs = createdAt,
                language = config.language,
                keepAudio = config.keepAudio,
                sourceLanguage = config.language.codes.firstOrNull(),
                targetLanguage = config.targetLanguage,
            ),
        ),
    )
    val state: StateFlow<LivePipelineState> = _state.asStateFlow()

    suspend fun start() {
        openSession(stt, replayFrom = null)
    }

    suspend fun pushAudio(chunk: AudioChunk) {
        ring.write(chunk)
        _state.update { it.copy(audioMs = chunk.streamOffsetMs + chunk.durationMs) }
        val speech = vad.isSpeech(chunk)
        if (config.pauseStreamingOnSilence && !speech && capabilities.streaming) return
        val current = session
        if (current == null) return
        sendMutex.withLock {
            current.send(chunk)
            lastSentOffset = chunk.streamOffsetMs + chunk.durationMs
        }
    }

    suspend fun stop() {
        session?.finalizeUtterance()
        session?.close()
        collector?.cancel()
        _state.update {
            it.copy(
                session = it.session.copy(
                    status = SessionStatus.READY,
                    endedAtMs = clock.nowMs(),
                    updatedAtMs = clock.nowMs(),
                    audioDurationMs = it.audioMs,
                ),
            )
        }
    }

    fun snapshotBundle(): SessionBundle {
        val s = _state.value
        return SessionBundle(
            session = s.session,
            tokens = s.tokens,
            segments = s.segments,
            translations = s.translations,
            speakers = s.speakers,
            usage = UsageRecord(
                sessionId = s.session.id,
                audioMs = s.audioMs,
                sttProviderId = if (usingFallback) fallbackStt?.id else stt.id,
                sttModel = null,
                translationInputTokens = translator?.inputTokens ?: 0,
                translationOutputTokens = translator?.outputTokens ?: 0,
            ),
        )
    }

    private suspend fun openSession(provider: SttProvider, replayFrom: Long?) {
        val live = provider.connect(
            SttSessionConfig(
                language = config.language,
                vocabulary = config.vocabulary,
                glossary = config.glossary,
                diarization = config.diarization && provider.capabilities.diarization,
                nativeTranslationTarget = if (
                    config.mode == SessionMode.TRANSLATOR &&
                    config.preferNativeTranslation &&
                    provider.capabilities.nativeTranslation &&
                    !config.bidirectional
                ) config.targetLanguage else null,
                twoWayLanguages = if (config.bidirectional) {
                    config.localLanguage to config.remoteLanguage
                } else null,
            ),
        )
        session = live
        collector?.cancel()
        collector = scope.launch { collect(live) }
        if (replayFrom != null) {
            val gap = ring.sliceFrom(replayFrom)
            if (gap.isNotEmpty()) {
                live.send(
                    AudioChunk(
                        pcm16le = gap,
                        capturedAtMs = clock.nowMs(),
                        streamOffsetMs = replayFrom,
                    ),
                )
            }
        }
    }

    private suspend fun collect(live: SttSession) {
        live.events.collect { event ->
            when (event) {
                is SttEvent.Ready -> {
                    reconnects = 0
                    _state.update { it.copy(connected = true, lastError = null, statusMessage = null) }
                }
                is SttEvent.Tokens -> onTokens(event.tokens)
                is SttEvent.NativeTranslation -> onNative(event.tokens)
                is SttEvent.SpeechEnded -> Unit
                is SttEvent.Closed -> {
                    _state.update { it.copy(connected = false) }
                    if (_state.value.session.status == SessionStatus.RECORDING) {
                        recover(event.reason ?: "closed")
                    }
                }
                is SttEvent.Failed -> {
                    _state.update { it.copy(lastError = event.error, connected = false) }
                    if (event.error.retryable) recover(event.error.userMessage())
                    else maybeFailover(event.error)
                }
            }
        }
    }

    private suspend fun onTokens(tokens: List<TranscriptToken>) {
        val snap = segmenter.ingest(tokens)
        val interim = snap.interimTokens.joinToString(" ") { it.text }
        val finals = snap.segments.filter { seg -> seg.isFinal }
        _state.update {
            val speakers = SpeakerRoster.fromSegments(finals, it.speakers)
            it.copy(
                tokens = snap.finalTokens,
                segments = finals,
                speakers = speakers,
                focusedSegmentId = finals.lastOrNull()?.id ?: it.focusedSegmentId,
                interimText = interim,
                session = it.session.copy(updatedAtMs = clock.nowMs()),
            )
        }
        if (config.mode != SessionMode.TRANSLATOR || translator == null) return
        val pending = finals.filter { it.id !in translatedIds }
        for (seg in pending) {
            translatedIds += seg.id
            scope.launch { runTranslate(seg, snap.segments.filter { it.isFinal }) }
        }
        if (config.previewInterimTranslation) {
            val draft = snap.segments.firstOrNull { !it.isFinal }
            if (draft != null && draft.text.isNotBlank()) {
                scope.launch { runTranslate(draft, snap.segments.filter { it.isFinal }, preview = true) }
            }
        }
    }

    private fun onNative(tokens: List<TranscriptToken>) {
        nativeByTime += tokens
        val text = tokens.filter { it.isFinal }.joinToString("") { it.text }
        if (text.isBlank()) return
        val host = _state.value.segments.lastOrNull { it.isFinal } ?: return
        translator?.ingestNative(host.id, host.text, text)
        _state.update { it.copy(translations = translator?.snapshot().orEmpty()) }
    }

    private suspend fun runTranslate(
        segment: TranscriptSegment,
        previous: List<TranscriptSegment>,
        preview: Boolean = false,
    ) {
        val orch = translator ?: return
        try {
            orch.translate(segment, previous, isPreview = preview)
            _state.update { it.copy(translations = orch.snapshot()) }
        } catch (e: ProviderError) {
            _state.update { it.copy(lastError = e) }
        }
    }

    private suspend fun recover(reason: String) {
        _state.update {
            it.copy(
                statusMessage = "STT disconnected ($reason). Recording continues; reconnecting…",
                session = it.session.copy(status = SessionStatus.OFFLINE_PENDING),
            )
        }
        val backoff = (500L * (1 shl reconnects.coerceAtMost(5))).coerceAtMost(8_000)
        delay(backoff)
        reconnects += 1
        val provider = if (usingFallback) fallbackStt ?: stt else stt
        runCatching { openSession(provider, replayFrom = lastSentOffset) }
        _state.update { it.copy(session = it.session.copy(status = SessionStatus.RECORDING)) }
    }

    private suspend fun maybeFailover(error: ProviderError) {
        val backup = fallbackStt
        if (backup == null || usingFallback) {
            _state.update {
                it.copy(
                    statusMessage = error.userMessage(),
                    session = it.session.copy(status = SessionStatus.OFFLINE_PENDING),
                )
            }
            return
        }
        usingFallback = true
        _state.update { it.copy(statusMessage = "Primary STT failed. Switching to ${backup.id}.") }
        openSession(backup, replayFrom = lastSentOffset)
    }
}
