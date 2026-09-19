import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @State private var key = ""
    @State private var endpoint = "stt-soniox"
    @State private var customTemplateName = ""
    @State private var customTemplatePrompt = ""

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
                    }
                    if let p = store.profiles.first(where: { $0.id == store.activeProfileId }) {
                        Text(p.detail).font(.footnote)
                    }
                }
                Section("API Key · 仅存 Keychain") {
                    Picker("Provider", selection: $endpoint) {
                        ForEach(store.endpoints) { item in
                            Text(item.name).tag(item.id)
                        }
                    }
                    .onChange(of: endpoint) { _, id in
                        if let item = store.endpoints.first(where: { $0.id == id }) {
                            store.baseURL = item.baseURL
                            store.modelName = item.model
                        }
                    }
                    SecureField("API Key", text: $key)
                    TextField("自定义 Base URL", text: $store.baseURL)
                    TextField("模型名", text: $store.modelName)
                    Button("保存并测试连通性") {
                        store.saveKey(endpointId: endpoint, key: key)
                        key = ""
                    }
                    if let status = store.lastStatus { Text(status).font(.footnote) }
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
                    Button("保存热词") { store.saveVocabulary() }
                    TextField("术语表（源词 → 译法）", text: $store.glossary, axis: .vertical)
                    Button("保存术语表") { store.saveGlossary() }
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
                    TextField("Prompt", text: $customTemplatePrompt, axis: .vertical)
                    Button("添加自定义模板") {
                        guard !customTemplateName.isEmpty, !customTemplatePrompt.isEmpty else { return }
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
            .onAppear {
                if let item = store.endpoints.first(where: { $0.id == endpoint })
                    ?? store.endpoints.first {
                    endpoint = item.id
                    if store.baseURL.isEmpty {
                        store.baseURL = item.baseURL
                        store.modelName = item.model
                    }
                }
            }
        }
    }
}
