package com.auralis.app

import com.auralis.audio.AudioArchive
import com.auralis.audio.AudioChunk
import com.auralis.audio.MemoryAudioArchive
import com.auralis.audio.Pcm
import com.auralis.core.Clock
import com.auralis.cost.CostEstimator
import com.auralis.cost.PriceTable
import com.auralis.di.createHttpClient
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.export.formatTimestamp
import com.auralis.model.BuiltInTemplates
import com.auralis.model.EditOverlay
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PostProcessResult
import com.auralis.model.PromptTemplate
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionMode
import com.auralis.model.SessionStatus
import com.auralis.model.UsageRecord
import com.auralis.pipeline.PipelineConfig
import com.auralis.pipeline.PostProcessor
import com.auralis.pipeline.SessionPipeline
import com.auralis.pipeline.SessionTitle
import com.auralis.pipeline.TranslationOrchestrator
import com.auralis.platform.AudioCapture
import com.auralis.platform.AudioPlayback
import com.auralis.platform.BackgroundKeepAlive
import com.auralis.platform.InMemoryPlayback
import com.auralis.platform.PlaybackState
import com.auralis.platform.SystemShare
import com.auralis.provider.stt.SttSessionConfig
import com.auralis.transcript.SpeakerRoster
import com.auralis.provider.ConnectivityResult
import com.auralis.provider.ConnectivityTester
import com.auralis.provider.InMemorySecureStore
import com.auralis.provider.Presets
import com.auralis.provider.ProviderFactory
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SecureStore
import com.auralis.provider.secretAlias
import com.auralis.store.AppSettings
import com.auralis.store.InMemorySessionRepository
import com.auralis.store.InMemorySettingsStore
import com.auralis.store.SessionRepository
import com.auralis.store.SettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuralisApp(
    val sessions: SessionRepository = InMemorySessionRepository(),
    val settingsStore: SettingsStore = InMemorySettingsStore(),
    val secrets: SecureStore = InMemorySecureStore(),
    val http: HttpClient = createHttpClient(),
    val clock: Clock = Clock.System,
    val prices: PriceTable = PriceTable(),
    val audio: AudioCapture? = null,
    val keepAlive: BackgroundKeepAlive? = null,
    val share: SystemShare? = null,
    val audioArchive: AudioArchive = MemoryAudioArchive(),
    val playback: AudioPlayback = InMemoryPlayback(),
) {
    private val factory = ProviderFactory(http)
    val tester = ConnectivityTester(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioJob: Job? = null
    private var checkpointJob: Job? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        _settings.value = settingsStore.load()
        markAbandonedRecordings()
        pendingSessions().forEach { session ->
            runCatching { catchUp(session.id) }
        }
    }

    suspend fun pendingSessions(): List<Session> =
        sessions.list().filter {
            it.status == SessionStatus.OFFLINE_PENDING ||
                it.status == SessionStatus.RECORDING ||
                it.status == SessionStatus.PROCESSING
        }

    private suspend fun markAbandonedRecordings() {
        sessions.list()
            .filter { it.status == SessionStatus.RECORDING || it.status == SessionStatus.PROCESSING }
            .forEach { session ->
                val bundle = sessions.get(session.id) ?: return@forEach
                sessions.upsert(
                    bundle.copy(session = bundle.session.copy(status = SessionStatus.OFFLINE_PENDING)),
                )
            }
    }

    suspend fun persist(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    suspend fun saveKey(endpointId: String, key: String): ConnectivityResult {
        secrets.put(secretAlias(endpointId), key)
        val endpoint = _settings.value.endpoints.first { it.id == endpointId }
        return tester.test(ResolvedEndpoint(endpoint, key))
    }

    suspend fun updateEndpoint(
        endpointId: String,
        baseUrl: String? = null,
        model: String? = null,
        extraHeaders: Map<String, String>? = null,
    ) {
        persist { settings ->
            settings.copy(
                endpoints = settings.endpoints.map { endpoint ->
                    if (endpoint.id != endpointId) endpoint
                    else endpoint.copy(
                        baseUrl = baseUrl?.ifBlank { endpoint.baseUrl } ?: endpoint.baseUrl,
                        model = model?.ifBlank { endpoint.model } ?: endpoint.model,
                        extraHeaders = extraHeaders ?: endpoint.extraHeaders,
                    )
                },
            )
        }
    }

    suspend fun resolve(endpointId: String): ResolvedEndpoint? {
        val endpoint = _settings.value.endpoints.firstOrNull { it.id == endpointId } ?: return null
        val key = secrets.get(secretAlias(endpointId)).orEmpty()
        return ResolvedEndpoint(endpoint, key)
    }

    fun defaultTemplate(): PromptTemplate =
        _settings.value.templates.firstOrNull { it.isDefault }
            ?: BuiltInTemplates.meetingNotes

    suspend fun saveTemplate(template: PromptTemplate) {
        persist { settings ->
            val rest = settings.templates.filterNot { it.id == template.id }
            val next = if (template.isDefault) {
                rest.map { it.copy(isDefault = false) } + template
            } else {
                rest + template
            }
            settings.copy(templates = next)
        }
    }

    suspend fun deleteTemplate(templateId: String) {
        persist { settings ->
            settings.copy(templates = settings.templates.filterNot { it.id == templateId && !it.isBuiltIn })
        }
    }

    suspend fun setDefaultTemplate(templateId: String) {
        persist { settings ->
            settings.copy(
                templates = settings.templates.map { it.copy(isDefault = it.id == templateId) },
            )
        }
    }

    suspend fun startLive(mode: SessionMode): SessionPipeline {
        val settings = _settings.value
        val profile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
            ?: Presets.demo
        val sttResolved = resolve(profile.sttId) ?: ResolvedEndpoint(Presets.demoStt, "")
        val stt = factory.stt(sttResolved)
        val fallback = profile.fallbackSttId?.let { id -> resolve(id)?.let { factory.stt(it) } }
        val translator = if (mode == SessionMode.TRANSLATOR) {
            val tr = resolve(profile.translationId) ?: ResolvedEndpoint(Presets.demoLlm, "")
            TranslationOrchestrator(
                llm = factory.llm(tr),
                targetLanguage = settings.remoteLanguage,
                glossary = settings.glossary,
                clock = clock,
                bidirectional = settings.bidirectional,
                localLanguage = settings.localLanguage,
                remoteLanguage = settings.remoteLanguage,
            )
        } else null
        val pipeline = SessionPipeline(
            stt = stt,
            translator = translator,
            config = PipelineConfig(
                mode = mode,
                language = settings.language,
                targetLanguage = settings.remoteLanguage,
                vocabulary = settings.vocabulary,
                glossary = settings.glossary,
                diarization = settings.diarization && stt.capabilities.diarization,
                preferNativeTranslation = profile.preferNativeTranslation && !settings.bidirectional,
                previewInterimTranslation = profile.previewInterimTranslation,
                keepAudio = settings.keepAudioDefault,
                bidirectional = settings.bidirectional,
                localLanguage = settings.localLanguage,
                remoteLanguage = settings.remoteLanguage,
            ),
            clock = clock,
            fallbackStt = fallback,
        )
        pipeline.start()
        keepAlive?.start(pipeline.state.value.session.title)
        persistLive(pipeline)
        startCheckpointing(pipeline)
        audioJob?.cancel()
        val capture = audio
        if (capture != null) {
            capture.start(pipeline.state.value.session.id, keepFile = false)
            audioJob = scope.launch {
                capture.chunks.collect { feed(pipeline, it) }
            }
        }
        return pipeline
    }

    suspend fun feed(pipeline: SessionPipeline, chunk: AudioChunk) {
        val id = pipeline.state.value.session.id
        if (pipeline.state.value.session.keepAudio) {
            audioArchive.append(id, chunk.pcm16le)
        }
        pipeline.pushAudio(chunk)
    }

    suspend fun finishLive(pipeline: SessionPipeline, title: String? = null): SessionBundle {
        stopCapture()
        pipeline.stop()
        val bundle = attachAudio(pipeline.snapshotBundle())
        val named = if (title.isNullOrBlank()) {
            val auto = autoTitle(bundle)
            bundle.copy(session = bundle.session.copy(title = auto))
        } else {
            bundle.copy(session = bundle.session.copy(title = title))
        }
        val stored = named.copy(usage = estimateUsage(named))
        sessions.upsert(stored)
        return stored
    }

    suspend fun abandonLive(pipeline: SessionPipeline): SessionBundle {
        stopCapture()
        pipeline.abandon()
        val stored = attachAudio(pipeline.snapshotBundle())
        sessions.upsert(stored)
        return stored
    }

    suspend fun catchUp(sessionId: String): SessionBundle {
        val bundle = sessions.get(sessionId) ?: error("session not found")
        if (
            bundle.session.status != SessionStatus.OFFLINE_PENDING &&
            bundle.session.status != SessionStatus.RECORDING &&
            bundle.session.status != SessionStatus.FAILED
        ) {
            return bundle
        }
        sessions.upsert(bundle.copy(session = bundle.session.copy(status = SessionStatus.PROCESSING)))
        val settings = _settings.value
        val profile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
            ?: Presets.demo
        val sttResolved = resolve(profile.sttId) ?: ResolvedEndpoint(Presets.demoStt, "")
        val pcm = audioArchive.read(sessionId) ?: ByteArray(0)
        val tokens = try {
            factory.stt(sttResolved).transcribeFile(
                if (pcm.isEmpty()) ByteArray(0) else Pcm.toWav(pcm, 16_000),
                "$sessionId.wav",
                SttSessionConfig(
                    language = settings.language,
                    vocabulary = settings.vocabulary,
                    glossary = settings.glossary,
                    diarization = settings.diarization,
                ),
            )
        } catch (e: Exception) {
            sessions.upsert(bundle.copy(session = bundle.session.copy(status = SessionStatus.OFFLINE_PENDING)))
            throw e
        }
        val segmenter = com.auralis.transcript.Segmenter()
        segmenter.replaceAll(bundle.tokens)
        val snap = segmenter.ingest(tokens)
        val finals = snap.segments.filter { it.isFinal }
        val extraTranslations = if (bundle.session.mode == SessionMode.TRANSLATOR) {
            val tr = resolve(profile.translationId) ?: ResolvedEndpoint(Presets.demoLlm, "")
            val orch = TranslationOrchestrator(
                llm = factory.llm(tr),
                targetLanguage = settings.remoteLanguage,
                glossary = settings.glossary,
                clock = clock,
                bidirectional = settings.bidirectional,
                localLanguage = settings.localLanguage,
                remoteLanguage = settings.remoteLanguage,
            )
            val done = bundle.translations.map { it.segmentId }.toSet()
            finals.filter { it.id !in done }.mapNotNull { seg ->
                runCatching { orch.translate(seg, finals) }.getOrNull()
            }
        } else emptyList()
        val merged = bundle.copy(
            tokens = snap.finalTokens,
            segments = finals,
            speakers = SpeakerRoster.fromSegments(finals, bundle.speakers),
            translations = bundle.translations + extraTranslations,
            session = bundle.session.copy(
                status = SessionStatus.READY,
                endedAtMs = clock.nowMs(),
                updatedAtMs = clock.nowMs(),
                audioDurationMs = Pcm.durationMs(pcm).coerceAtLeast(bundle.session.audioDurationMs),
                audioPath = audioArchive.uri(sessionId) ?: bundle.session.audioPath,
            ),
        )
        val named = if (merged.session.title.isBlank() || merged.session.title == "Untitled session") {
            merged.copy(session = merged.session.copy(title = autoTitle(merged)))
        } else {
            merged
        }
        val stored = named.copy(usage = estimateUsage(named))
        sessions.upsert(stored)
        return stored
    }

    suspend fun seekToSegment(sessionId: String, segmentId: String): PlaybackState {
        val bundle = sessions.get(sessionId) ?: return PlaybackState(label = "找不到这场会话")
        val segment = bundle.segments.firstOrNull { it.id == segmentId }
            ?: return PlaybackState(label = "找不到这句话")
        val pcm = audioArchive.read(sessionId)
        val duration = Pcm.durationMs(pcm ?: ByteArray(0)).coerceAtLeast(bundle.session.audioDurationMs)
        val hasAudio = pcm != null || !bundle.session.audioPath.isNullOrBlank()
        val label = "跳转到 ${formatTimestamp(segment.startMs)}" +
            if (hasAudio) " · 正在播放" else " · 无本地音频"
        playback.seek(sessionId, segment.startMs, duration, label)
        return playback.state.value
    }

    fun audioWav(sessionId: String): ByteArray? {
        val pcm = audioArchive.read(sessionId) ?: return null
        if (pcm.isEmpty()) return null
        return Pcm.toWav(pcm, 16_000)
    }

    private fun startCheckpointing(pipeline: SessionPipeline) {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            var lastSig = ""
            pipeline.state.collect { state ->
                val sig = "${state.session.status}|${state.segments.size}|${state.audioMs}|${state.translations.size}"
                if (sig == lastSig) return@collect
                lastSig = sig
                persistLive(pipeline)
            }
        }
    }

    private suspend fun persistLive(pipeline: SessionPipeline) {
        sessions.upsert(attachAudio(pipeline.snapshotBundle()))
    }

    private fun attachAudio(bundle: SessionBundle): SessionBundle {
        val path = audioArchive.uri(bundle.session.id) ?: audio?.lastFilePath()
        return if (path.isNullOrBlank()) bundle
        else bundle.copy(session = bundle.session.copy(audioPath = path))
    }

    private suspend fun stopCapture() {
        checkpointJob?.cancel()
        checkpointJob = null
        audioJob?.cancel()
        audioJob = null
        audio?.stop()
        keepAlive?.stop()
    }

    private suspend fun autoTitle(bundle: SessionBundle): String {
        val transcript = bundle.fullTranscript()
        val settings = _settings.value
        val profile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
            ?: Presets.demo
        val llmResolved = resolve(profile.postProcessId) ?: ResolvedEndpoint(Presets.demoLlm, "")
        return runCatching { SessionTitle.generate(factory.llm(llmResolved), transcript) }
            .getOrElse { SessionTitle.fallback(transcript) }
    }

    suspend fun postProcess(
        sessionId: String,
        template: PromptTemplate = defaultTemplate(),
    ): PostProcessResult {
        val bundle = sessions.get(sessionId) ?: error("session not found")
        val settings = _settings.value
        val profile = settings.profiles.first { it.id == settings.activeProfileId }
        val llmResolved = resolve(profile.postProcessId) ?: ResolvedEndpoint(Presets.demoLlm, "")
        val result = PostProcessor(factory.llm(llmResolved), clock).run(bundle, template)
        val next = bundle.copy(
            postProcess = bundle.postProcess + result,
            usage = estimateUsage(
                bundle.copy(
                    usage = bundle.usage?.copy(
                        llmInputTokens = (bundle.usage?.llmInputTokens ?: 0) + result.inputTokens,
                        llmOutputTokens = (bundle.usage?.llmOutputTokens ?: 0) + result.outputTokens,
                    ) ?: com.auralis.model.UsageRecord(
                        sessionId = sessionId,
                        llmInputTokens = result.inputTokens,
                        llmOutputTokens = result.outputTokens,
                    ),
                ),
            ),
        )
        sessions.upsert(next)
        return result
    }

    suspend fun ask(sessionId: String, question: String): PostProcessResult {
        val bundle = sessions.get(sessionId) ?: error("session not found")
        val settings = _settings.value
        val profile = settings.profiles.first { it.id == settings.activeProfileId }
        val llmResolved = resolve(profile.postProcessId) ?: ResolvedEndpoint(Presets.demoLlm, "")
        val result = PostProcessor(factory.llm(llmResolved), clock).ask(bundle, question)
        sessions.upsert(bundle.copy(postProcess = bundle.postProcess + result))
        return result
    }

    suspend fun edit(sessionId: String, segmentId: String, text: String) {
        sessions.editSegment(
            sessionId,
            EditOverlay(segmentId, text, clock.nowMs()),
        )
    }

    suspend fun renameSpeaker(sessionId: String, speakerId: String, name: String) {
        sessions.renameSpeaker(sessionId, speakerId, name)
    }

    fun export(bundle: SessionBundle, format: ExportFormat): String =
        TranscriptExporter.export(bundle, format)

    fun shareExport(bundle: SessionBundle, format: ExportFormat) {
        val text = export(bundle, format)
        val ext = when (format) {
            ExportFormat.MARKDOWN -> "md"
            ExportFormat.TXT -> "txt"
            ExportFormat.SRT -> "srt"
            ExportFormat.VTT -> "vtt"
            ExportFormat.DOCX_SIMPLE -> "md"
        }
        share?.share(
            "${bundle.session.title}.$ext",
            if (format == ExportFormat.MARKDOWN) "text/markdown" else "text/plain",
            text.encodeToByteArray(),
        )
    }

    fun fontScale(): Float = _settings.value.fontScale.coerceIn(1.0f, 2.0f)

    fun languagePresets(): List<LanguageHint> = listOf(
        LanguageHint.ChineseEnglish,
        LanguageHint.English,
        LanguageHint.PortugueseEnglish,
    )

    suspend fun setVocabulary(words: List<String>) {
        persist { it.copy(vocabulary = words.map { word -> word.trim() }.filter { word -> word.isNotEmpty() }) }
    }

    suspend fun setGlossary(entries: List<GlossaryEntry>) {
        persist { it.copy(glossary = entries.filter { entry -> entry.source.isNotBlank() && entry.target.isNotBlank() }) }
    }

    private fun estimateUsage(bundle: SessionBundle): com.auralis.model.UsageRecord? {
        val usage = bundle.usage ?: return null
        val settings = _settings.value
        val profile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
        return usage.copy(
            estimatedUsd = CostEstimator.estimateSimple(
                audioMs = usage.audioMs,
                sttProviderId = usage.sttProviderId.orEmpty(),
                llmInput = usage.llmInputTokens + usage.translationInputTokens,
                llmOutput = usage.llmOutputTokens + usage.translationOutputTokens,
                table = prices,
                llmProviderId = profile?.postProcessId ?: "llm-grok",
            ),
        )
    }
}

data class LibraryState(
    val sessions: List<Session> = emptyList(),
    val query: String = "",
)

class LibraryController(private val app: AuralisApp) {
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    suspend fun refresh(query: String = _state.value.query) {
        val list = if (query.isBlank()) app.sessions.list() else app.sessions.search(query)
        _state.update { it.copy(sessions = list, query = query) }
    }
}
