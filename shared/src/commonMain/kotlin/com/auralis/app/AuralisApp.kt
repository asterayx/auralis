package com.auralis.app

import com.auralis.core.Clock
import com.auralis.core.newId
import com.auralis.cost.CostEstimator
import com.auralis.cost.PriceTable
import com.auralis.di.createHttpClient
import com.auralis.model.BuiltInTemplates
import com.auralis.model.EditOverlay
import com.auralis.model.PostProcessResult
import com.auralis.model.PromptTemplate
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionMode
import com.auralis.pipeline.PipelineConfig
import com.auralis.pipeline.PostProcessor
import com.auralis.pipeline.SessionPipeline
import com.auralis.pipeline.TranslationOrchestrator
import com.auralis.provider.ConnectivityResult
import com.auralis.provider.ConnectivityTester
import com.auralis.provider.Presets
import com.auralis.provider.ProviderFactory
import com.auralis.provider.ResolvedEndpoint
import com.auralis.provider.SecureStore
import com.auralis.provider.InMemorySecureStore
import com.auralis.provider.secretAlias
import com.auralis.store.AppSettings
import com.auralis.store.InMemorySessionRepository
import com.auralis.store.InMemorySettingsStore
import com.auralis.store.SessionRepository
import com.auralis.store.SettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AuralisApp(
    val sessions: SessionRepository = InMemorySessionRepository(),
    val settingsStore: SettingsStore = InMemorySettingsStore(),
    val secrets: SecureStore = InMemorySecureStore(),
    val http: HttpClient = createHttpClient(),
    val clock: Clock = Clock.System,
    val prices: PriceTable = PriceTable(),
) {
    private val factory = ProviderFactory(http)
    val tester = ConnectivityTester(http)

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
        secrets.put(secretAlias(endpointId), key)
        val endpoint = _settings.value.endpoints.first { it.id == endpointId }
        return tester.test(ResolvedEndpoint(endpoint, key))
    }

    suspend fun resolve(endpointId: String): ResolvedEndpoint? {
        val endpoint = _settings.value.endpoints.firstOrNull { it.id == endpointId } ?: return null
        val key = secrets.get(secretAlias(endpointId)).orEmpty()
        return ResolvedEndpoint(endpoint, key)
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
                targetLanguage = "en",
                glossary = settings.glossary,
                clock = clock,
            )
        } else null
        val pipeline = SessionPipeline(
            stt = stt,
            translator = translator,
            config = PipelineConfig(
                mode = mode,
                language = com.auralis.model.LanguageHint.ChineseEnglish,
                vocabulary = settings.vocabulary,
                glossary = settings.glossary,
                diarization = stt.capabilities.diarization,
                preferNativeTranslation = profile.preferNativeTranslation,
                previewInterimTranslation = profile.previewInterimTranslation,
                keepAudio = settings.keepAudioDefault,
            ),
            clock = clock,
            fallbackStt = fallback,
        )
        pipeline.start()
        return pipeline
    }

    suspend fun finishLive(pipeline: SessionPipeline, title: String? = null): SessionBundle {
        pipeline.stop()
        val bundle = pipeline.snapshotBundle()
        val named = if (title.isNullOrBlank()) {
            val auto = bundle.fullTranscript().lineSequence().firstOrNull()?.take(40)
                ?: "Untitled session"
            bundle.copy(session = bundle.session.copy(title = auto))
        } else {
            bundle.copy(session = bundle.session.copy(title = title))
        }
        val usage = named.usage?.copy(
            estimatedUsd = named.usage?.let { CostEstimator.estimateSimple(it.audioMs, it.sttProviderId ?: "", 0, 0, prices) },
        )
        val stored = named.copy(usage = usage)
        sessions.upsert(stored)
        return stored
    }

    suspend fun postProcess(sessionId: String, template: PromptTemplate = BuiltInTemplates.meetingNotes): PostProcessResult {
        val bundle = sessions.get(sessionId) ?: error("session not found")
        val settings = _settings.value
        val profile = settings.profiles.first { it.id == settings.activeProfileId }
        val llmResolved = resolve(profile.postProcessId) ?: ResolvedEndpoint(Presets.demoLlm, "")
        val result = PostProcessor(factory.llm(llmResolved), clock).run(bundle, template)
        sessions.upsert(bundle.copy(postProcess = bundle.postProcess + result))
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

    fun fontScale(): Float = _settings.value.fontScale.coerceIn(1.0f, 2.0f)
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
