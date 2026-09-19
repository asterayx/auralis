package com.auralis.apple

import com.auralis.app.AuralisApp
import com.auralis.app.LibraryController
import com.auralis.core.newId
import com.auralis.export.ExportFormat
import com.auralis.export.formatTimestamp
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PromptTemplate
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionMode
import com.auralis.model.TranslationLayout
import com.auralis.pipeline.LivePipelineState
import com.auralis.pipeline.LiveSync
import com.auralis.pipeline.SessionPipeline
import com.auralis.provider.KeychainSecureStore
import com.auralis.provider.Presets
import com.auralis.provider.ProviderKind
import com.auralis.store.AppSettings
import com.auralis.store.FileTextStore
import com.auralis.store.JsonSessionRepository
import com.auralis.store.JsonSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Apple host around [AuralisApp]. Constructs the kernel the same way
 * Android [com.auralis.android.AuralisApplication] does, and exposes
 * callback APIs so Swift does not have to collect Flows or call suspend
 * functions directly.
 */
class AppleAuralis internal constructor(
    val app: AuralisApp,
    val audio: AppleAudioBridge,
) {
    private val scope = MainScope()
    private val library = LibraryController(app)
    private var live: SessionPipeline? = null
    private var liveWatch: Job? = null

    fun watchSettings(onChange: (AppleSettingsSnapshot) -> Unit): Job =
        scope.launch { app.settings.collect { onChange(it.toSnapshot()) } }

    fun load(onDone: (String?) -> Unit) {
        scope.launch {
            runCatching { app.load() }.fold(
                onSuccess = { onDone(null) },
                onFailure = { onDone(it.message ?: "load failed") },
            )
        }
    }

    fun startLive(
        translator: Boolean,
        onSnapshot: (AppleLiveSnapshot) -> Unit,
        onReady: (String?) -> Unit,
    ) {
        scope.launch {
            runCatching {
                liveWatch?.cancel()
                val mode = if (translator) SessionMode.TRANSLATOR else SessionMode.SCRIBE
                val pipe = app.startLive(mode)
                live = pipe
                liveWatch = scope.launch {
                    pipe.state.collect { onSnapshot(it.toLiveSnapshot()) }
                }
            }.fold(
                onSuccess = { onReady(null) },
                onFailure = { onReady(it.message ?: "startLive failed") },
            )
        }
    }

    fun finishLive(onDone: (String?) -> Unit) {
        scope.launch {
            val pipe = live
            liveWatch?.cancel()
            liveWatch = null
            live = null
            if (pipe == null) {
                onDone(null)
                return@launch
            }
            runCatching { app.finishLive(pipe) }.report(onDone)
        }
    }

    fun saveKey(endpointId: String, key: String, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val result = app.saveKey(endpointId, key)
            onDone(result.ok, result.message)
        }
    }

    fun updateEndpoint(endpointId: String, baseUrl: String, model: String, onDone: (String?) -> Unit) {
        scope.launch {
            runCatching { app.updateEndpoint(endpointId, baseUrl = baseUrl, model = model) }.report(onDone)
        }
    }

    fun setActiveProfile(id: String, onDone: (String?) -> Unit) =
        persist({ it.copy(activeProfileId = id) }, onDone)

    fun setLanguage(label: String, onDone: (String?) -> Unit) {
        val hint = app.languagePresets().firstOrNull { it.label == label } ?: LanguageHint.ChineseEnglish
        persist({ it.copy(language = hint) }, onDone)
    }

    fun setVocabulary(csv: String, onDone: (String?) -> Unit) {
        scope.launch {
            runCatching { app.setVocabulary(csv.split(',', '，', '\n')) }.report(onDone)
        }
    }

    fun setGlossary(text: String, onDone: (String?) -> Unit) {
        scope.launch {
            runCatching {
                app.setGlossary(
                    text.lineSequence().mapNotNull { line ->
                        val parts = line.split("→", "->", limit = 2).map { it.trim() }
                        if (parts.size == 2) GlossaryEntry(parts[0], parts[1]) else null
                    }.toList(),
                )
            }.report(onDone)
        }
    }

    fun setBidirectional(value: Boolean, onDone: (String?) -> Unit) =
        persist({ it.copy(bidirectional = value) }, onDone)

    fun setDiarization(value: Boolean, onDone: (String?) -> Unit) =
        persist({ it.copy(diarization = value) }, onDone)

    fun setKeepAudio(value: Boolean, onDone: (String?) -> Unit) =
        persist({ it.copy(keepAudioDefault = value) }, onDone)

    fun setTranslationLayout(sideBySide: Boolean, onDone: (String?) -> Unit) =
        persist(
            {
                it.copy(
                    translationLayout = if (sideBySide) TranslationLayout.SIDE_BY_SIDE
                    else TranslationLayout.STACKED,
                )
            },
            onDone,
        )

    fun setFontScale(value: Float, onDone: (String?) -> Unit) =
        persist({ it.copy(fontScale = value.coerceIn(1f, 2f)) }, onDone)

    fun setRecordingConsent(value: Boolean, onDone: (String?) -> Unit) =
        persist({ it.copy(recordingConsent = value) }, onDone)

    fun saveTemplate(name: String, prompt: String, onDone: (String?) -> Unit) {
        scope.launch {
            runCatching {
                app.saveTemplate(
                    PromptTemplate(
                        id = newId("tpl"),
                        name = name,
                        description = "custom",
                        prompt = prompt,
                    ),
                )
            }.report(onDone)
        }
    }

    fun deleteTemplate(id: String, onDone: (String?) -> Unit) {
        scope.launch { runCatching { app.deleteTemplate(id) }.report(onDone) }
    }

    fun setDefaultTemplate(id: String, onDone: (String?) -> Unit) {
        scope.launch { runCatching { app.setDefaultTemplate(id) }.report(onDone) }
    }

    fun listSessions(query: String, onDone: (List<AppleSessionRow>) -> Unit) {
        scope.launch {
            library.refresh(query)
            onDone(library.state.value.sessions.map { it.toRow() })
        }
    }

    fun getSession(id: String, onDone: (AppleSessionDetail?) -> Unit) {
        scope.launch { onDone(app.sessions.get(id)?.toDetail()) }
    }

    fun postProcess(sessionId: String, templateId: String, onDone: (String?, String?) -> Unit) {
        scope.launch {
            val template = app.settings.value.templates.firstOrNull { it.id == templateId }
                ?: app.defaultTemplate()
            runCatching { app.postProcess(sessionId, template) }.fold(
                onSuccess = { onDone(it.content, null) },
                onFailure = { onDone(null, it.message ?: "postProcess failed") },
            )
        }
    }

    fun edit(sessionId: String, segmentId: String, text: String, onDone: (String?) -> Unit) {
        scope.launch { runCatching { app.edit(sessionId, segmentId, text) }.report(onDone) }
    }

    fun renameSpeaker(sessionId: String, speakerId: String, name: String, onDone: (String?) -> Unit) {
        scope.launch { runCatching { app.renameSpeaker(sessionId, speakerId, name) }.report(onDone) }
    }

    fun exportOpen(sessionId: String, markdown: Boolean, onDone: (String) -> Unit) {
        scope.launch {
            val bundle = app.sessions.get(sessionId)
            onDone(
                if (bundle == null) ""
                else app.export(bundle, if (markdown) ExportFormat.MARKDOWN else ExportFormat.TXT),
            )
        }
    }

    fun seekLabel(sessionId: String, segmentId: String, onDone: (String?) -> Unit) {
        scope.launch {
            val bundle = app.sessions.get(sessionId) ?: return@launch onDone(null)
            val segment = bundle.segments.firstOrNull { it.id == segmentId } ?: return@launch onDone(null)
            val stamp = formatTimestamp(segment.startMs)
            onDone(
                if (bundle.session.audioPath.isNullOrBlank()) {
                    "跳转到 $stamp · 接入平台播放后生效"
                } else {
                    "跳转到 $stamp · 本地音频已就绪"
                },
            )
        }
    }

    fun keyLinks(): List<KeyLink> =
        Presets.applyKeyLinks.map { KeyLink(it.key, it.value) }

    fun grokLanguageNote(): String = Presets.grokSttLanguageNote

    fun demoProfileId(): String = Presets.demo.id

    fun qualityProfileId(): String = Presets.qualityMeeting.id

    private fun persist(transform: (AppSettings) -> AppSettings, onDone: (String?) -> Unit) {
        scope.launch { runCatching { app.persist(transform) }.report(onDone) }
    }

    companion object {
        fun create(documentsPath: String): AppleAuralis {
            val disk = FileTextStore(documentsPath)
            val audio = AppleAudioBridge()
            val app = AuralisApp(
                sessions = JsonSessionRepository(disk),
                settingsStore = JsonSettingsStore(disk),
                secrets = KeychainSecureStore(),
                audio = audio,
                keepAlive = AppleKeepAlive(),
            )
            return AppleAuralis(app, audio)
        }
    }
}

data class KeyLink(
    val id: String,
    val url: String,
)

data class AppleProfileRow(
    val id: String,
    val name: String,
    val detail: String,
)

data class AppleEndpointRow(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
)

data class AppleTemplateRow(
    val id: String,
    val name: String,
    val prompt: String,
    val isBuiltIn: Boolean,
    val isDefault: Boolean,
)

data class AppleSettingsSnapshot(
    val activeProfileId: String,
    val profiles: List<AppleProfileRow>,
    val endpoints: List<AppleEndpointRow>,
    val languageLabel: String,
    val vocabulary: String,
    val glossary: String,
    val bidirectional: Boolean,
    val diarization: Boolean,
    val keepAudio: Boolean,
    val consent: Boolean,
    val fontScale: Double,
    val sideBySide: Boolean,
    val templates: List<AppleTemplateRow>,
    val defaultTemplateId: String,
)

data class AppleCaptionRow(
    val id: String,
    val text: String,
    val speaker: String,
    val isFinal: Boolean,
    val direction: String,
    val translation: String?,
)

data class AppleSpeakerRow(
    val id: String,
    val name: String,
    val color: String,
)

data class AppleLiveSnapshot(
    val captions: List<AppleCaptionRow>,
    val interim: String,
    val speakers: List<AppleSpeakerRow>,
    val focusedId: String?,
    val error: String?,
    val status: String?,
)

data class AppleSessionRow(
    val id: String,
    val title: String,
    val translator: Boolean,
    val status: String,
    val updatedAtMs: Long,
)

data class AppleSessionDetail(
    val id: String,
    val title: String,
    val translator: Boolean,
    val status: String,
    val captions: List<AppleCaptionRow>,
    val speakers: List<AppleSpeakerRow>,
    val audioMinutes: Double,
    val tokens: Int,
    val estimate: Double,
    val hasUsage: Boolean,
    val notes: String,
    val audioReady: Boolean,
)

private fun <T> Result<T>.report(onDone: (String?) -> Unit) {
    fold(onSuccess = { onDone(null) }, onFailure = { onDone(it.message ?: it.toString()) })
}

private fun AppSettings.toSnapshot(): AppleSettingsSnapshot {
    val defaultId = templates.firstOrNull { it.isDefault }?.id ?: templates.firstOrNull()?.id.orEmpty()
    return AppleSettingsSnapshot(
        activeProfileId = activeProfileId,
        profiles = profiles.map { AppleProfileRow(it.id, it.name, it.description) },
        endpoints = endpoints.filter { it.kind != ProviderKind.DEMO }.map {
            AppleEndpointRow(it.id, it.displayName, it.baseUrl, it.model)
        },
        languageLabel = language.label,
        vocabulary = vocabulary.joinToString(", "),
        glossary = glossary.joinToString("\n") { "${it.source} → ${it.target}" },
        bidirectional = bidirectional,
        diarization = diarization,
        keepAudio = keepAudioDefault,
        consent = recordingConsent,
        fontScale = fontScale.toDouble(),
        sideBySide = translationLayout == TranslationLayout.SIDE_BY_SIDE,
        templates = templates.map {
            AppleTemplateRow(it.id, it.name, it.prompt, it.isBuiltIn, it.isDefault)
        },
        defaultTemplateId = defaultId,
    )
}

private fun LivePipelineState.toLiveSnapshot(): AppleLiveSnapshot {
    val lines = LiveSync.lines(segments, translations, speakers)
    return AppleLiveSnapshot(
        captions = lines.map { line ->
            AppleCaptionRow(
                id = line.segment.id,
                text = line.segment.text,
                speaker = line.segment.speakerId.orEmpty(),
                isFinal = line.segment.isFinal,
                direction = line.translation?.directionLabel.orEmpty(),
                translation = line.translation?.translatedText,
            )
        },
        interim = interimText,
        speakers = this.speakers.map {
            AppleSpeakerRow(it.id, it.displayName, it.colorHex.removePrefix("#"))
        },
        focusedId = focusedSegmentId,
        error = lastError?.userMessage(),
        status = statusMessage,
    )
}

private fun Session.toRow(): AppleSessionRow = AppleSessionRow(
    id = id,
    title = title,
    translator = mode == SessionMode.TRANSLATOR,
    status = status.name,
    updatedAtMs = updatedAtMs,
)

private fun SessionBundle.toDetail(): AppleSessionDetail {
    val lines = LiveSync.lines(segments, translations, speakers)
    val usage = usage
    return AppleSessionDetail(
        id = session.id,
        title = session.title,
        translator = session.mode == SessionMode.TRANSLATOR,
        status = session.status.name,
        captions = lines.map { line ->
            AppleCaptionRow(
                id = line.segment.id,
                text = displayText(line.segment),
                speaker = line.segment.speakerId.orEmpty(),
                isFinal = line.segment.isFinal,
                direction = line.translation?.directionLabel.orEmpty(),
                translation = line.translation?.translatedText,
            )
        },
        speakers = speakers.map {
            AppleSpeakerRow(it.id, it.displayName, it.colorHex.removePrefix("#"))
        },
        audioMinutes = (usage?.audioMs ?: 0).toDouble() / 60_000.0,
        tokens = (usage?.llmInputTokens ?: 0) + (usage?.llmOutputTokens ?: 0) +
            (usage?.translationInputTokens ?: 0) + (usage?.translationOutputTokens ?: 0),
        estimate = usage?.estimatedUsd ?: 0.0,
        hasUsage = usage != null,
        notes = postProcess.lastOrNull()?.content.orEmpty(),
        audioReady = !session.audioPath.isNullOrBlank(),
    )
}
