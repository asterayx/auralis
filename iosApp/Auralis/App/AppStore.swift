import Foundation
import Combine

enum AppTab: Hashable {
    case library, scribe, translator, settings
}

enum TranslationLayout: String {
    case stacked, sideBySide
}

/// UI-facing store. On device this will wrap the KMP `AuralisApp` framework.
/// Preview and first-run use the scripted demo so screens are reviewable without keys.
@MainActor
final class AppStore: ObservableObject {
    @Published var onboarded: Bool
    @Published var tab: AppTab = .library
    @Published var sessions: [SessionSummary] = []
    @Published var live: LiveState = .idle
    @Published var profiles: [Profile] = Profile.defaults
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
    @Published var templates: [PromptTemplate] = PromptTemplate.defaults
    @Published var defaultTemplateId: String = "tpl-meeting-notes"

    init() {
        onboarded = UserDefaults.standard.bool(forKey: "auralis.onboarded")
        sessions = SessionSummary.demoLibrary
        speakers = SpeakerTag.defaults
    }

    func finishOnboarding(useDemo: Bool) {
        onboarded = true
        activeProfileId = useDemo ? "profile-demo" : "profile-quality"
        UserDefaults.standard.set(true, forKey: "auralis.onboarded")
    }

    func start(mode: SessionMode) {
        focusedCaptionId = nil
        live = .running(mode: mode, captions: [], interim: "", translations: [:], error: nil)
        Task { await playDemo(mode: mode) }
    }

    func stop() {
        if case .running(let mode, let captions, _, let translations, _) = live {
            let title = captions.first?.text.prefix(32).description ?? "Untitled session"
            sessions.insert(
                SessionSummary(
                    id: UUID().uuidString,
                    title: String(title),
                    mode: mode,
                    status: "READY",
                    updated: Date(),
                    captions: captions,
                    translations: translations,
                    speakers: speakers,
                    usage: UsageSummary(audioMinutes: 0.1, tokens: 80, estimate: 0.0)
                ),
                at: 0
            )
        }
        live = .idle
        focusedCaptionId = nil
    }

    func search(_ query: String) -> [SessionSummary] {
        let q = query.lowercased()
        guard !q.isEmpty else { return sessions }
        return sessions.filter {
            $0.title.lowercased().contains(q) ||
            $0.captions.contains { $0.text.lowercased().contains(q) }
        }
    }

    func editCaption(sessionId: String, captionId: String, text: String) {
        sessions = sessions.map { session in
            guard session.id == sessionId else { return session }
            var next = session
            next.captions = next.captions.map { $0.id == captionId ? Caption(id: $0.id, text: text, speaker: $0.speaker, isFinal: $0.isFinal, direction: $0.direction, startMs: $0.startMs) : $0 }
            return next
        }
    }

    func catchUp(sessionId: String) {
        sessions = sessions.map { session in
            guard session.id == sessionId else { return session }
            var next = session
            next.status = "READY"
            if next.title.hasPrefix("未完成") { next.title = "供应商对齐会" }
            return next
        }
        lastStatus = "已从本地录音补转写"
    }

    func renameSpeaker(id: String, name: String) {
        speakers = speakers.map { $0.id == id ? SpeakerTag(id: $0.id, name: name, color: $0.color) : $0 }
        sessions = sessions.map { session in
            var next = session
            next.speakers = next.speakers.map { $0.id == id ? SpeakerTag(id: $0.id, name: name, color: $0.color) : $0 }
            return next
        }
    }

    func speaker(for id: String) -> SpeakerTag? {
        speakers.first { $0.id == id }
    }

    private func playDemo(mode: SessionMode) async {
        let script: [(String, String, String, String)] = [
            ("大家好，我们开始今天的供应商对齐会。", "1", "Hello everyone, let's start today's supplier alignment.", "zh → en"),
            ("Hello everyone, thanks for joining.", "2", "大家好，谢谢参加。", "en → zh"),
            ("本周交期能否从十月十二日提前到十月八日？", "1", "Can we pull delivery from 12 Oct to 8 Oct?", "zh → en"),
            ("We can pull in two days if the firmware freeze happens tonight.", "2", "如果今晚冻结固件，可以提前两天。", "en → zh"),
        ]
        for (text, speaker, translation, direction) in script {
            if case .running(let mode, var captions, _, var translations, _) = live {
                live = .running(mode: mode, captions: captions, interim: text, translations: translations, error: nil)
            }
            try? await Task.sleep(nanoseconds: 400_000_000)
            if case .running(let mode, var captions, _, var translations, _) = live {
                let id = UUID().uuidString
                let starts = [400, 3400, 5800, 10000]
                captions.append(Caption(id: id, text: text, speaker: speaker, isFinal: true, direction: direction, startMs: starts[min(captions.count, starts.count - 1)]))
                translations[id] = translation
                focusedCaptionId = id
                live = .running(mode: mode, captions: captions, interim: "", translations: translations, error: nil)
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }
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
    var startMs: Int = 0
}

struct SpeakerTag: Identifiable, Hashable {
    let id: String
    var name: String
    let color: String

    static let defaults = [
        SpeakerTag(id: "1", name: "说话人 1", color: "7C9CFF"),
        SpeakerTag(id: "2", name: "说话人 2", color: "7DDBB6"),
    ]
}

struct SessionSummary: Identifiable {
    let id: String
    var title: String
    var mode: SessionMode
    var status: String
    var updated: Date
    var captions: [Caption] = []
    var translations: [String: String] = [:]
    var speakers: [SpeakerTag] = SpeakerTag.defaults
    var usage: UsageSummary? = UsageSummary(audioMinutes: 6.2, tokens: 640, estimate: 0.08)

    static let demoLibrary = [
        SessionSummary(
            id: "d1",
            title: "供应商对齐会",
            mode: .translator,
            status: "READY",
            updated: Date(),
            captions: [
                Caption(id: "c1", text: "本周交期能否从十月十二日提前到十月八日？", speaker: "1", isFinal: true, direction: "zh → en", startMs: 5800),
                Caption(id: "c2", text: "We can pull in two days if the firmware freeze happens tonight.", speaker: "2", isFinal: true, direction: "en → zh", startMs: 10000),
            ],
            translations: [
                "c1": "Can we pull delivery from 12 Oct to 8 Oct?",
                "c2": "如果今晚冻结固件，可以提前两天。",
            ]
        ),
        SessionSummary(
            id: "d2",
            title: "Firmware review",
            mode: .scribe,
            status: "READY",
            updated: Date().addingTimeInterval(-3600),
            usage: UsageSummary(audioMinutes: 42, tokens: 1200, estimate: 0.12)
        ),
        SessionSummary(
            id: "d3",
            title: "未完成录音（待补转写）",
            mode: .translator,
            status: "OFFLINE_PENDING",
            updated: Date().addingTimeInterval(-120),
            captions: [
                Caption(id: "p1", text: "大家好，我们开始今天的供应商对齐会。", speaker: "1", isFinal: true, startMs: 400),
            ],
            translations: ["p1": "Hello everyone, let's start today's supplier alignment."],
            usage: UsageSummary(audioMinutes: 0.4, tokens: 0, estimate: 0.0)
        ),
    ]
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

    static let defaults = [
        PromptTemplate(id: "tpl-meeting-notes", name: "Meeting notes", prompt: "Summary / key points / actions / open questions", isBuiltIn: true),
        PromptTemplate(id: "tpl-actions", name: "Action items", prompt: "Extract action items", isBuiltIn: true),
        PromptTemplate(id: "tpl-polish", name: "Polish transcript", prompt: "Fix punctuation", isBuiltIn: true),
    ]
}

struct Profile: Identifiable {
    let id: String
    let name: String
    let detail: String

    static let defaults = [
        Profile(id: "profile-demo", name: "Demo (no key)", detail: "Scripted captions for first-run"),
        Profile(id: "profile-quality", name: "Client meeting — quality", detail: "Soniox + strong LLM"),
        Profile(id: "profile-cheap", name: "Daily — save money", detail: "Grok STT (no Chinese) + small LLM"),
        Profile(id: "profile-private", name: "Intranet — private endpoint", detail: "Custom Base URL"),
    ]
}
