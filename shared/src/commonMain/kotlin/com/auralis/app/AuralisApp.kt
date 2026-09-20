package com.auralis.app

import com.auralis.core.Clock
import com.auralis.cost.CostEstimator
import com.auralis.cost.PriceTable
import com.auralis.di.createHttpClient
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.model.BuiltInTemplates
import com.auralis.model.EditOverlay
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PostProcessResult
import com.auralis.model.PromptTemplate
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionMode
import com.auralis.pipeline.PipelineConfig
import com.auralis.pipeline.PostProcessor
import com.auralis.pipeline.SessionPipeline
import com.auralis.pipeline.SessionTitle
import com.auralis.pipeline.TranslationOrchestrator
import com.auralis.platform.AudioCapture
import com.auralis.platform.BackgroundKeepAlive
import com.auralis.platform.SystemShare
import com.auralis.provider.ConnectivityResult
import com.auralis.provider.ConnectivityTester
import com.auralis.provider.InMemorySecureStore
import com.auralis.provider.Presets
import com.auralis.provider.ProviderFactory
import com.auralis.provider.ProviderKind
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SecureStore
import com.auralis.provider.secretAlias
import com.auralis.store.AppSettings
import com.auralis.store.InMemorySessionRepository
import com.auralis.store.InMemorySettingsStore
import com.auralis.store.SessionRepository
import com.auralis.store.SettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
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
) {
    private val factory = ProviderFactory(http)
    val tester = ConnectivityTester(http)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            println("AuralisApp: ${throwable.message ?: throwable.toString()}")
        },
    )
    private var audioJob: Job? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        _settings.value = settingsStore.load()
    }

    suspend fun persist(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    suspend fun saveKey(endpointId: String, key: String): ConnectivityResult {
        val trimmed = key.trim()
        val stored = secrets.get(secretAlias(endpointId)).orEmpty()
        if (trimmed.isNotBlank()) {
            runCatching { secrets.put(secretAlias(endpointId), trimmed) }
                .onFailure {
                    return ConnectivityResult(false, it.message ?: "Keychain write failed.")
                }
        }
        val endpoint = _settings.value.endpoints.first { it.id == endpointId }
        val toUse = trimmed.ifBlank { stored }
        return tester.test(ResolvedEndpoint(endpoint, toUse))
    }

    suspend fun hasKey(endpointId: String): Boolean {
        val endpoint = _settings.value.endpoints.firstOrNull { it.id == endpointId } ?: return false
        if (endpoint.kind == ProviderKind.DEMO) return true
        return runCatching { !secrets.get(secretAlias(endpointId)).isNullOrBlank() }.getOrDefault(false)
    }

    suspend fun configuredEndpointIds(): Set<String> =
        runCatching {
            _settings.value.endpoints.mapNotNull { endpoint ->
                if (hasKey(endpoint.id)) endpoint.id else null
            }.toSet()
        }.getOrDefault(emptySet())

    suspend fun probeModels(endpointId: String, keyDraft: String = ""): ConnectivityResult {
        val endpoint = _settings.value.endpoints.firstOrNull { it.id == endpointId }
            ?: return ConnectivityResult(false, "Unknown endpoint.")
        val key = keyDraft.trim().ifBlank { secrets.get(secretAlias(endpointId)).orEmpty() }
        val result = tester.test(ResolvedEndpoint(endpoint, key))
        val models = (result.models + listOfNotNull(endpoint.model.takeIf { it.isNotBlank() }))
            .distinct()
        return result.copy(models = models)
    }

    suspend fun setProfileSlots(
        profileId: String,
        sttId: String,
        translationId: String,
        postProcessId: String,
    ) {
        persist { settings ->
            settings.copy(
                profiles = settings.profiles.map { profile ->
                    if (profile.id != profileId) profile
                    else profile.copy(
                        sttId = sttId,
                        translationId = translationId,
                        postProcessId = postProcessId,
                    )
                },
            )
        }
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
        audioJob?.cancel()
        val capture = audio
        if (capture != null) {
            capture.start(pipeline.state.value.session.id, settings.keepAudioDefault)
            audioJob = scope.launch {
                capture.chunks.collect { pipeline.pushAudio(it) }
            }
        }
        return pipeline
    }

    suspend fun finishLive(pipeline: SessionPipeline, title: String? = null): SessionBundle {
        audioJob?.cancel()
        audioJob = null
        audio?.stop()
        keepAlive?.stop()
        pipeline.stop()
        val bundle = pipeline.snapshotBundle()
        val audioPath = audio?.lastFilePath()
        val withAudio = if (audioPath.isNullOrBlank()) bundle
        else bundle.copy(session = bundle.session.copy(audioPath = audioPath))
        val named = if (title.isNullOrBlank()) {
            val auto = autoTitle(withAudio)
            withAudio.copy(session = withAudio.session.copy(title = auto))
        } else {
            withAudio.copy(session = withAudio.session.copy(title = title))
        }
        val stored = named.copy(usage = estimateUsage(named))
        sessions.upsert(stored)
        return stored
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
