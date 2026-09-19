import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @State private var key = ""
    @State private var endpoint = "stt-soniox"
    @State private var probe = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("配置档 KEY-3") {
                    Picker("当前档", selection: $store.activeProfileId) {
                        ForEach(store.profiles) { p in
                            Text(p.name).tag(p.id)
                        }
                    }
                    if let p = store.profiles.first(where: { $0.id == store.activeProfileId }) {
                        Text(p.detail).font(.footnote)
                    }
                }
                Section("API Key · 仅存 Keychain") {
                    Picker("Provider", selection: $endpoint) {
                        Text("Soniox").tag("stt-soniox")
                        Text("Grok STT").tag("stt-grok")
                        Text("ElevenLabs").tag("stt-elevenlabs")
                        Text("OpenAI 兼容转写").tag("stt-openai-batch")
                        Text("Grok LLM").tag("llm-grok")
                        Text("Gemini").tag("llm-gemini")
                        Text("硅基流动").tag("llm-siliconflow")
                    }
                    SecureField("API Key", text: $key)
                    TextField("自定义 Base URL（可选）", text: .constant(""))
                    Button("保存并测试连通性") {
                        KeychainStore.put(endpoint, value: key)
                        key = ""
                        probe = "已写入 Keychain。真机将调用 ConnectivityTester。"
                    }
                    if !probe.isEmpty { Text(probe).font(.footnote) }
                    Text("Grok STT 不含中文，中英会议请用 Soniox 或国内兼容端点。")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                Section("隐私") {
                    Text("你的数据会发送给你所选的 Provider。Auralis 无自有服务器、无账号、默认无遥测。")
                    Toggle("崩溃上报（不含文本/音频/密钥）", isOn: .constant(false))
                    Toggle("录音前告知", isOn: $store.consentAcknowledged)
                }
                Section("翻译与说话人") {
                    Toggle("双向翻译（本侧中文 ↔ 对侧英文）", isOn: $store.bidirectional)
                    Toggle("说话人分轨", isOn: $store.diarization)
                    Picker("翻译布局", selection: $store.layout) {
                        Text("并排同步").tag(TranslationLayout.sideBySide)
                        Text("上下堆叠").tag(TranslationLayout.stacked)
                    }
                    Text("点一句字幕，原文和译文会同步高亮。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("无障碍") {
                    Slider(value: $store.fontScale, in: 1...2, step: 0.1) {
                        Text("字幕字号 \(Int(store.fontScale * 100))%")
                    }
                }
            }
            .navigationTitle("设置")
        }
    }
}
