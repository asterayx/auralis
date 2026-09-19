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
                    speakers: speakers
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
                captions.append(Caption(id: id, text: text, speaker: speaker, isFinal: true, direction: direction))
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

    static let demoLibrary = [
        SessionSummary(
            id: "d1",
            title: "供应商对齐会",
            mode: .translator,
            status: "READY",
            updated: Date(),
            captions: [
                Caption(id: "c1", text: "本周交期能否从十月十二日提前到十月八日？", speaker: "1", isFinal: true, direction: "zh → en"),
                Caption(id: "c2", text: "We can pull in two days if the firmware freeze happens tonight.", speaker: "2", isFinal: true, direction: "en → zh"),
            ],
            translations: [
                "c1": "Can we pull delivery from 12 Oct to 8 Oct?",
                "c2": "如果今晚冻结固件，可以提前两天。",
            ]
        ),
        SessionSummary(id: "d2", title: "Firmware review", mode: .scribe, status: "READY", updated: Date().addingTimeInterval(-3600)),
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
