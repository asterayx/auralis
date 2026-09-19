import AVFoundation
import Combine
import Foundation
import Shared

enum AppTab: Hashable {
    case library, scribe, translator, settings
}

enum TranslationLayout: String {
    case stacked, sideBySide
}

/// UI-facing store. Wraps KMP `AuralisApp` the same way Android `AuralisRoot` does:
/// live session, settings, and keys all go through the shared kernel.
@MainActor
final class AppStore: ObservableObject {
    @Published var onboarded: Bool
    @Published var tab: AppTab = .library
    @Published var sessions: [SessionSummary] = []
    @Published var live: LiveState = .idle
    @Published var profiles: [Profile] = []
    @Published var activeProfileId: String = "profile-demo"
    @Published var lastStatus: String?
    @Published var fontScale: Double = 1.0
    @Published var consentAcknowledged: Bool = true
    @Published var bidirectional: Bool = true
    @Published var diarization: Bool = true
    @Published var layout: TranslationLayout = .sideBySide
    @Published var focusedCaptionId: String?
    @Published var speakers: [SpeakerTag] = []
    @Published var languageLabel: String = "中+英"
    @Published var vocabulary: String = ""
    @Published var glossary: String = ""
    @Published var keepAudio: Bool = true
    @Published var baseURL: String = ""
    @Published var modelName: String = ""
    @Published var templates: [PromptTemplate] = []
    @Published var defaultTemplateId: String = "tpl-meeting-notes"
    @Published var endpoints: [Endpoint] = []
    @Published var keyLinks: [KeyLinkItem] = []
    @Published var grokNote: String = ""

    private let host: AppleAuralis
    private let capture = AppleAudioCapture()
    private var settingsWatch: Kotlinx_coroutines_coreJob?
    private var applyingSettings = false

    init() {
        onboarded = UserDefaults.standard.bool(forKey: "auralis.onboarded")
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("auralis", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        host = AppleAuralis.companion.create(documentsPath: dir.path)
        grokNote = host.grokLanguageNote()
        keyLinks = rows(host.keyLinks()).map { KeyLinkItem(id: $0.id, url: $0.url) }
        bindAudio()
        settingsWatch = host.watchSettings { [weak self] settings in
            Task { @MainActor in self?.apply(settings) }
        }
        host.load { [weak self] error in
            Task { @MainActor in
                if let error { self?.lastStatus = error }
                self?.refreshLibrary()
            }
        }
    }

    deinit {
        settingsWatch?.cancel()
    }

    func finishOnboarding(useDemo: Bool) {
        onboarded = true
        UserDefaults.standard.set(true, forKey: "auralis.onboarded")
        let id = useDemo ? host.demoProfileId() : host.qualityProfileId()
        host.setActiveProfile(id: id) { _ in }
    }

    func start(mode: SessionMode) {
        focusedCaptionId = nil
        live = .running(mode: mode, captions: [], interim: "", translations: [:], error: nil)
        Task { await startKernel(mode) }
    }

    func stop() {
        host.finishLive { [weak self] error in
            Task { @MainActor in
                self?.live = .idle
                self?.focusedCaptionId = nil
                if let error { self?.lastStatus = error }
                self?.refreshLibrary()
            }
        }
    }

    func setProfile(_ id: String) {
        guard !applyingSettings, id != activeProfileId else { return }
        host.setActiveProfile(id: id) { _ in }
    }

    func setLanguage(_ label: String) {
        guard !applyingSettings else { return }
        host.setLanguage(label: label) { _ in }
    }

    func saveVocabulary() {
        host.setVocabulary(csv: vocabulary) { _ in }
    }

    func saveGlossary() {
        host.setGlossary(text: glossary) { _ in }
    }

    func setBidirectional(_ value: Bool) {
        guard !applyingSettings else { return }
        host.setBidirectional(value: value) { _ in }
    }

    func setDiarization(_ value: Bool) {
        guard !applyingSettings else { return }
        host.setDiarization(value: value) { _ in }
    }

    func setKeepAudio(_ value: Bool) {
        guard !applyingSettings else { return }
        host.setKeepAudio(value: value) { _ in }
    }

    func setLayout(_ value: TranslationLayout) {
        guard !applyingSettings else { return }
        host.setTranslationLayout(sideBySide: value == .sideBySide) { _ in }
    }

    func setFontScale(_ value: Double) {
        guard !applyingSettings else { return }
        host.setFontScale(value: Float(value)) { _ in }
    }

    func setConsent(_ value: Bool) {
        guard !applyingSettings else { return }
        host.setRecordingConsent(value: value) { _ in }
    }

    func saveKey(endpointId: String, key: String) {
        host.updateEndpoint(endpointId: endpointId, baseUrl: baseURL, model: modelName) { [weak self] _ in
            guard let self else { return }
            if key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Task { @MainActor in self.lastStatus = "已更新 Base URL / 模型。" }
                return
            }
            self.host.saveKey(endpointId: endpointId, key: key) { _, message in
                Task { @MainActor in self.lastStatus = message }
            }
        }
    }

    func addTemplate(name: String, prompt: String) {
        host.saveTemplate(name: name, prompt: prompt) { _ in }
    }

    func setDefaultTemplate(_ id: String) {
        guard !applyingSettings else { return }
        host.setDefaultTemplate(id: id) { _ in }
    }

    func search(_ query: String) -> [SessionSummary] {
        let q = query.lowercased()
        guard !q.isEmpty else { return sessions }
        return sessions.filter { $0.title.lowercased().contains(q) }
    }

    func refreshLibrary(query: String = "") {
        host.listSessions(query: query) { [weak self] list in
            Task { @MainActor in
                self?.sessions = rows(list).map { session in
                    SessionSummary(
                        id: session.id,
                        title: session.title,
                        mode: session.translator ? .translator : .scribe,
                        status: session.status,
                        updated: Date(timeIntervalSince1970: Double(session.updatedAtMs) / 1000)
                    )
                }
            }
        }
    }

    func loadDetail(sessionId: String, completion: @escaping (SessionDetail?) -> Void) {
        host.getSession(id: sessionId) { detail in
            Task { @MainActor in
                completion(detail.map(Self.mapDetail))
            }
        }
    }

    func editCaption(sessionId: String, captionId: String, text: String, completion: @escaping (SessionDetail?) -> Void) {
        host.edit(sessionId: sessionId, segmentId: captionId, text: text) { [weak self] _ in
            self?.loadDetail(sessionId: sessionId, completion: completion)
        }
    }

    func renameSpeaker(sessionId: String, id: String, name: String, completion: @escaping (SessionDetail?) -> Void) {
        host.renameSpeaker(sessionId: sessionId, speakerId: id, name: name) { [weak self] _ in
            Task { @MainActor in
                self?.speakers = self?.speakers.map {
                    $0.id == id ? SpeakerTag(id: $0.id, name: name, color: $0.color) : $0
                } ?? []
            }
            self?.loadDetail(sessionId: sessionId, completion: completion)
        }
    }

    func generateNotes(sessionId: String, templateId: String, completion: @escaping (String) -> Void) {
        host.postProcess(sessionId: sessionId, templateId: templateId) { notes, error in
            Task { @MainActor in
                completion(error ?? notes ?? "")
            }
        }
    }

    func exportOpen(sessionId: String, markdown: Bool, completion: @escaping (String) -> Void) {
        host.exportOpen(sessionId: sessionId, markdown: markdown) { text in
            Task { @MainActor in completion(text) }
        }
    }

    func seekLabel(sessionId: String, segmentId: String, completion: @escaping (String?) -> Void) {
        host.seekLabel(sessionId: sessionId, segmentId: segmentId) { label in
            Task { @MainActor in completion(label) }
        }
    }

    func speaker(for id: String) -> SpeakerTag? {
        speakers.first { $0.id == id }
    }

    private func bindAudio() {
        capture.onChunk = { [weak self] data, offset in
            self?.host.audio.pushPcm(data: data as NSData, streamOffsetMs: offset)
        }
        host.audio.onStart = { [weak self] sessionId, keepFile in
            do {
                try self?.capture.start(sessionId: sessionId, keepFile: keepFile)
                return self?.capture.lastFilePath
            } catch {
                return nil
            }
        }
        host.audio.onStop = { [weak self] in
            self?.capture.stop()
        }
    }

    private func startKernel(_ mode: SessionMode) async {
        _ = await requestMic()
        host.startLive(translator: mode == .translator, onSnapshot: { [weak self] snapshot in
            Task { @MainActor in self?.applyLive(mode: mode, snapshot: snapshot) }
        }, onReady: { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.live = .running(mode: mode, captions: [], interim: "", translations: [:], error: error)
                }
            }
        })
    }

    private func apply(_ settings: AppleSettingsSnapshot) {
        applyingSettings = true
        activeProfileId = settings.activeProfileId
        profiles = rows(settings.profiles).map { Profile(id: $0.id, name: $0.name, detail: $0.detail) }
        endpoints = rows(settings.endpoints).map {
            Endpoint(id: $0.id, name: $0.name, baseURL: $0.baseUrl, model: $0.model)
        }
        languageLabel = settings.languageLabel
        vocabulary = settings.vocabulary
        glossary = settings.glossary
        bidirectional = settings.bidirectional
        diarization = settings.diarization
        keepAudio = settings.keepAudio
        consentAcknowledged = settings.consent
        fontScale = settings.fontScale
        layout = settings.sideBySide ? .sideBySide : .stacked
        templates = rows(settings.templates).map {
            PromptTemplate(id: $0.id, name: $0.name, prompt: $0.prompt, isBuiltIn: $0.isBuiltIn)
        }
        defaultTemplateId = settings.defaultTemplateId
        applyingSettings = false
    }

    private func applyLive(mode: SessionMode, snapshot: AppleLiveSnapshot) {
        var translations: [String: String] = [:]
        let captions: [Caption] = rows(snapshot.captions).map { row in
            if let text = row.translation { translations[row.id] = text }
            return Caption(
                id: row.id,
                text: row.text,
                speaker: row.speaker,
                isFinal: row.isFinal,
                direction: row.direction
            )
        }
        speakers = rows(snapshot.speakers).map { SpeakerTag(id: $0.id, name: $0.name, color: $0.color) }
        focusedCaptionId = snapshot.focusedId ?? focusedCaptionId
        live = .running(
            mode: mode,
            captions: captions,
            interim: snapshot.interim,
            translations: translations,
            error: snapshot.error
        )
        lastStatus = snapshot.status
    }

    private static func mapDetail(_ detail: AppleSessionDetail) -> SessionDetail {
        var translations: [String: String] = [:]
        let captions: [Caption] = rows(detail.captions).map { row in
            if let text = row.translation { translations[row.id] = text }
            return Caption(
                id: row.id,
                text: row.text,
                speaker: row.speaker,
                isFinal: row.isFinal,
                direction: row.direction
            )
        }
        return SessionDetail(
            id: detail.id,
            title: detail.title,
            mode: detail.translator ? .translator : .scribe,
            status: detail.status,
            captions: captions,
            translations: translations,
            speakers: rows(detail.speakers).map { SpeakerTag(id: $0.id, name: $0.name, color: $0.color) },
            usage: detail.hasUsage
                ? UsageSummary(audioMinutes: detail.audioMinutes, tokens: Int(detail.tokens), estimate: detail.estimate)
                : nil,
            notes: detail.notes,
            audioReady: detail.audioReady
        )
    }

    private func requestMic() async -> Bool {
        #if os(macOS)
        await AVCaptureDevice.requestAccess(for: .audio)
        #else
        await AVAudioApplication.requestRecordPermission()
        #endif
    }
}

private func rows<T: AnyObject>(_ value: Any) -> [T] {
    if let typed = value as? [T] { return typed }
    if let ns = value as? NSArray { return ns.compactMap { $0 as? T } }
    return []
}

enum SessionMode: String { case scribe = "SCRIBE", translator = "TRANSLATOR" }

enum LiveState {
    case idle
    case running(mode: SessionMode, captions: [Caption], interim: String, translations: [String: String], error: String?)
}

struct Caption: Identifiable {
    let id: String
    let text: String
    let speaker: String
    let isFinal: Bool
    var direction: String = ""
}

struct SpeakerTag: Identifiable, Hashable {
    let id: String
    var name: String
    let color: String
}

struct SessionSummary: Identifiable {
    let id: String
    var title: String
    var mode: SessionMode
    var status: String
    var updated: Date
}

struct SessionDetail {
    var id: String
    var title: String
    var mode: SessionMode
    var status: String
    var captions: [Caption]
    var translations: [String: String]
    var speakers: [SpeakerTag]
    var usage: UsageSummary?
    var notes: String
    var audioReady: Bool
}

struct UsageSummary {
    var audioMinutes: Double
    var tokens: Int
    var estimate: Double
}

struct PromptTemplate: Identifiable {
    let id: String
    var name: String
    var prompt: String
    var isBuiltIn: Bool
}

struct Profile: Identifiable {
    let id: String
    let name: String
    let detail: String
}

struct Endpoint: Identifiable {
    let id: String
    let name: String
    let baseURL: String
    let model: String
}

struct KeyLinkItem: Identifiable {
    let id: String
    let url: String
}
