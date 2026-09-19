import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
#endif

struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @State private var key = ""
    @State private var endpoint = "stt-soniox"
    @State private var keyConfigured = false
    @State private var sttId = ""
    @State private var translationId = ""
    @State private var postProcessId = ""
    @State private var customTemplateName = ""
    @State private var customTemplatePrompt = ""

    private var keyProviders: [Endpoint] {
        store.endpoints.filter { !$0.isDemo }
    }

    private var sttProviders: [Endpoint] {
        store.endpoints.filter { $0.slot == "stt" }
    }

    private var llmProviders: [Endpoint] {
        store.endpoints.filter { $0.slot == "llm" }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("配置档 KEY-3") {
                    Picker("当前档", selection: $store.activeProfileId) {
                        ForEach(store.profiles) { p in
                            Text(p.name).tag(p.id)
                        }
                    }
                    .onChange(of: store.activeProfileId) { _, id in
                        store.setProfile(id)
                        syncSlots()
                    }
                    if let p = store.profiles.first(where: { $0.id == store.activeProfileId }) {
                        Text(p.detail).font(.footnote)
                    }
                    Picker("转写模型", selection: $sttId) {
                        ForEach(sttProviders) { item in
                            Text(item.name).tag(item.id)
                        }
                    }
                    .onChange(of: sttId) { _, _ in persistSlots() }
                    Picker("翻译模型", selection: $translationId) {
                        ForEach(llmProviders) { item in
                            Text(item.name).tag(item.id)
                        }
                    }
                    .onChange(of: translationId) { _, _ in persistSlots() }
                    Picker("后处理模型", selection: $postProcessId) {
                        ForEach(llmProviders) { item in
                            Text(item.name).tag(item.id)
                        }
                    }
                    .onChange(of: postProcessId) { _, _ in persistSlots() }
                }
                Section("API Key · 仅存 Keychain") {
                    Picker("Provider", selection: $endpoint) {
                        ForEach(keyProviders) { item in
                            Text(item.name).tag(item.id)
                        }
                    }
                    .onChange(of: endpoint) { _, id in
                        applyEndpoint(id)
                    }
                    SecureField(keyConfigured ? "•••••••• 已配置" : "API Key", text: $key)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                        .onChange(of: key) { _, value in
                            if !value.isEmpty { keyConfigured = false }
                            store.scheduleModelProbe(endpointId: endpoint, key: value)
                        }
                    TextField("自定义 Base URL", text: $store.baseURL)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                    if store.modelCandidates.isEmpty {
                        TextField("模型名", text: $store.modelName)
                            .submitLabel(.done)
                            .onSubmit { hideKeyboard() }
                    } else {
                        Picker("模型", selection: $store.modelName) {
                            if !store.modelCandidates.contains(store.modelName), !store.modelName.isEmpty {
                                Text(store.modelName).tag(store.modelName)
                            }
                            ForEach(store.modelCandidates, id: \.self) { name in
                                Text(name).tag(name)
                            }
                        }
                    }
                    Button("保存并测试连通性") {
                        hideKeyboard()
                        store.saveKey(endpointId: endpoint, key: key) { ok in
                            if ok {
                                key = ""
                                keyConfigured = true
                            }
                        }
                    }
                    if let status = store.keyProbeStatus {
                        Text(status)
                            .font(.footnote)
                            .foregroundStyle(probeColor)
                    }
                    Text(store.grokNote.isEmpty
                         ? "Grok STT 不含中文，中英会议请用 Soniox 或国内兼容端点。"
                         : store.grokNote)
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                Section("语种 · 热词 · 术语表") {
                    Picker("转写语种", selection: $store.languageLabel) {
                        Text("中+英").tag("中+英")
                        Text("English").tag("English")
                        Text("PT+EN").tag("PT+EN")
                    }
                    .onChange(of: store.languageLabel) { _, label in
                        store.setLanguage(label)
                    }
                    TextField("热词（逗号分隔）", text: $store.vocabulary)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                    Button("保存热词") {
                        hideKeyboard()
                        store.saveVocabulary()
                    }
                    TextField("术语表（源词 → 译法）", text: $store.glossary, axis: .vertical)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                    Button("保存术语表") {
                        hideKeyboard()
                        store.saveGlossary()
                    }
                }
                Section("Prompt 模板库") {
                    Picker("默认模板", selection: $store.defaultTemplateId) {
                        ForEach(store.templates) { template in
                            Text(template.name).tag(template.id)
                        }
                    }
                    .onChange(of: store.defaultTemplateId) { _, id in
                        store.setDefaultTemplate(id)
                    }
                    TextField("自定义模板名", text: $customTemplateName)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                    TextField("Prompt", text: $customTemplatePrompt, axis: .vertical)
                        .submitLabel(.done)
                        .onSubmit { hideKeyboard() }
                    Button("添加自定义模板") {
                        guard !customTemplateName.isEmpty, !customTemplatePrompt.isEmpty else { return }
                        hideKeyboard()
                        store.addTemplate(name: customTemplateName, prompt: customTemplatePrompt)
                        customTemplateName = ""
                        customTemplatePrompt = ""
                    }
                }
                Section("隐私") {
                    Text("你的数据会发送给你所选的 Provider。Auralis 无自有服务器、无账号、默认无遥测。")
                    Toggle("崩溃上报（不含文本/音频/密钥）", isOn: .constant(false))
                    Toggle("录音前告知", isOn: $store.consentAcknowledged)
                        .onChange(of: store.consentAcknowledged) { _, value in
                            store.setConsent(value)
                        }
                    Toggle("保留本地音频", isOn: $store.keepAudio)
                        .onChange(of: store.keepAudio) { _, value in
                            store.setKeepAudio(value)
                        }
                }
                Section("翻译与说话人") {
                    Toggle("双向翻译（本侧中文 ↔ 对侧英文）", isOn: $store.bidirectional)
                        .onChange(of: store.bidirectional) { _, value in
                            store.setBidirectional(value)
                        }
                    Toggle("说话人分轨", isOn: $store.diarization)
                        .onChange(of: store.diarization) { _, value in
                            store.setDiarization(value)
                        }
                    Picker("翻译布局", selection: $store.layout) {
                        Text("并排同步").tag(TranslationLayout.sideBySide)
                        Text("上下堆叠").tag(TranslationLayout.stacked)
                    }
                    .onChange(of: store.layout) { _, value in
                        store.setLayout(value)
                    }
                    Text("点一句字幕，原文和译文会同步高亮。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("无障碍") {
                    Slider(value: $store.fontScale, in: 1...2, step: 0.1) {
                        Text("字幕字号 \(Int(store.fontScale * 100))%")
                    }
                    .onChange(of: store.fontScale) { _, value in
                        store.setFontScale(value)
                    }
                }
            }
            .navigationTitle("设置")
            .dismissKeyboardChrome()
            .onAppear {
                if let item = keyProviders.first(where: { $0.id == endpoint })
                    ?? keyProviders.first {
                    applyEndpoint(item.id)
                }
                syncSlots()
            }
            .onChange(of: store.profiles.map(\.id)) { _, _ in
                syncSlots()
            }
        }
    }

    private var probeColor: Color {
        switch store.keyProbeOk {
        case true: return .green
        case false: return .red
        case nil: return .secondary
        }
    }

    private func applyEndpoint(_ id: String) {
        endpoint = id
        guard let item = store.endpoints.first(where: { $0.id == id }) else { return }
        store.baseURL = item.baseURL
        store.modelName = item.model
        key = ""
        keyConfigured = item.configured
        store.modelCandidates = []
        store.keyProbeStatus = nil
        store.keyProbeOk = nil
        if item.configured {
            store.scheduleModelProbe(endpointId: id, key: "")
        }
    }

    private func syncSlots() {
        guard let p = store.profiles.first(where: { $0.id == store.activeProfileId }) else { return }
        sttId = p.sttId
        translationId = p.translationId
        postProcessId = p.postProcessId
    }

    private func persistSlots() {
        guard !sttId.isEmpty, !translationId.isEmpty, !postProcessId.isEmpty else { return }
        guard let p = store.profiles.first(where: { $0.id == store.activeProfileId }) else { return }
        guard sttId != p.sttId || translationId != p.translationId || postProcessId != p.postProcessId else { return }
        store.setProfileSlots(sttId: sttId, translationId: translationId, postProcessId: postProcessId)
    }
}

private extension View {
    @ViewBuilder
    func dismissKeyboardChrome() -> some View {
#if os(iOS)
        self
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { hideKeyboard() }
                }
            }
#else
        self
#endif
    }
}

private func hideKeyboard() {
#if canImport(UIKit) && !os(macOS)
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
#elseif canImport(AppKit)
    NSApp.keyWindow?.makeFirstResponder(nil)
#endif
}
