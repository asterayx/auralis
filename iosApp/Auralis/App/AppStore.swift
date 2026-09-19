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
    private var applyingSettings = false

    init() {
        onboarded = UserDefaults.standard.bool(forKey: "auralis.onboarded")
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("auralis", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        host = AppleAuralis.companion.create(documentsPath: dir.path)
        grokNote = host.grokLanguageNote()
        keyLinks = rows(host.keyLinks(), as: KeyLink.self).map { link in
            KeyLinkItem(id: link.id, url: link.url)
        }
        bindAudio()
        host.watchSettings { [weak self] settings in
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
        host.close()
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
                self?.sessions = rows(list, as: AppleSessionRow.self).map { session in
                    SessionSummary(
                        id: session.id,
                        title: session.title,
                        mode: kbool(session.translator) ? .translator : .scribe,
                        status: session.status,
                        updated: Date(timeIntervalSince1970: kdouble(session.updatedAtMs) / 1000)
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
            self?.host.audio.pushPcm(data: data as Data, streamOffsetMs: offset)
        }
        host.audio.onStart = { [weak self] sessionId, keepFile in
            do {
                try self?.capture.start(sessionId: sessionId, keepFile: keepFile.boolValue)
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
        profiles = rows(settings.profiles, as: AppleProfileRow.self).map { row in
            Profile(id: row.id, name: row.name, detail: row.detail)
        }
        endpoints = rows(settings.endpoints, as: AppleEndpointRow.self).map { row in
            Endpoint(id: row.id, name: row.name, baseURL: row.baseUrl, model: row.model)
        }
        languageLabel = settings.languageLabel
        vocabulary = settings.vocabulary
        glossary = settings.glossary
        bidirectional = kbool(settings.bidirectional)
        diarization = kbool(settings.diarization)
        keepAudio = kbool(settings.keepAudio)
        consentAcknowledged = kbool(settings.consent)
        fontScale = kdouble(settings.fontScale)
        layout = kbool(settings.sideBySide) ? .sideBySide : .stacked
        templates = rows(settings.templates, as: AppleTemplateRow.self).map { row in
            PromptTemplate(id: row.id, name: row.name, prompt: row.prompt, isBuiltIn: kbool(row.isBuiltIn))
        }
        defaultTemplateId = settings.defaultTemplateId
        applyingSettings = false
    }

    private func applyLive(mode: SessionMode, snapshot: AppleLiveSnapshot) {
        var translations: [String: String] = [:]
        let captions: [Caption] = rows(snapshot.captions, as: AppleCaptionRow.self).map { row in
            if let text = row.translation { translations[row.id] = text }
            return Caption(
                id: row.id,
                text: row.text,
                speaker: row.speaker,
                isFinal: kbool(row.isFinal),
                direction: row.direction
            )
        }
        speakers = rows(snapshot.speakers, as: AppleSpeakerRow.self).map { row in
            SpeakerTag(id: row.id, name: row.name, color: row.color)
        }
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
        let captions: [Caption] = rows(detail.captions, as: AppleCaptionRow.self).map { row in
            if let text = row.translation { translations[row.id] = text }
            return Caption(
                id: row.id,
                text: row.text,
                speaker: row.speaker,
                isFinal: kbool(row.isFinal),
                direction: row.direction
            )
        }
        return SessionDetail(
            id: detail.id,
            title: detail.title,
            mode: kbool(detail.translator) ? .translator : .scribe,
            status: detail.status,
            captions: captions,
            translations: translations,
            speakers: rows(detail.speakers, as: AppleSpeakerRow.self).map { row in
                SpeakerTag(id: row.id, name: row.name, color: row.color)
            },
            usage: kbool(detail.hasUsage)
                ? UsageSummary(
                    audioMinutes: kdouble(detail.audioMinutes),
                    tokens: Int(kdouble(detail.tokens)),
                    estimate: kdouble(detail.estimate)
                )
                : nil,
            notes: detail.notes,
            audioReady: kbool(detail.audioReady)
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

private func rows<T: AnyObject>(_ value: Any, as type: T.Type) -> [T] {
    if let typed = value as? [T] { return typed }
    if let ns = value as? NSArray { return ns.compactMap { $0 as? T } }
    if let enumerable = value as? NSFastEnumeration {
        var out: [T] = []
        var iterator = NSFastEnumerationIterator(enumerable)
        while let element = iterator.next() {
            if let typed = element as? T { out.append(typed) }
        }
        return out
    }
    return []
}

private func kbool(_ value: Any) -> Bool {
    if let flag = value as? Bool { return flag }
    if let flag = value as? KotlinBoolean { return flag.boolValue }
    return false
}

private func kdouble(_ value: Any) -> Double {
    if let number = value as? NSNumber { return number.doubleValue }
    if let number = value as? Double { return number }
    if let number = value as? KotlinDouble { return number.doubleValue }
    if let number = value as? KotlinLong { return number.doubleValue }
    if let number = value as? KotlinInt { return number.doubleValue }
    return 0
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
