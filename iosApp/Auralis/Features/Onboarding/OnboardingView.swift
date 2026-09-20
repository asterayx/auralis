import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Auralis 聆语")
                    .font(.largeTitle.bold())
                Text("本地优先、模型可插拔的语音工作台。App 只负责采集、编排和呈现；转写、后处理、翻译全部由你自己的 API Key 驱动。")
                privacy
                Text("三分钟起步")
                    .font(.title3.bold())
                Text("1. 先用内置演示跑通字幕与翻译\n2. 到设置页粘贴 Soniox / Grok / OpenAI 兼容 Key\n3. 保存时会做连通性测试，并给出可读错误")
                linkList
                Button("先用演示（无需 Key）") { store.finishOnboarding(useDemo: true) }
                    .buttonStyle(.borderedProminent)
                Button("我已有 Key") { store.finishOnboarding(useDemo: false) }
                    .buttonStyle(.bordered)
            }
            .padding(24)
        }
        .background(Color.black)
    }

    private var privacy: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("你的数据会发送给你所选的 Provider。")
                .font(.headline)
            Text("Auralis 没有自有服务器、没有账号。密钥只进 Keychain，不会进入数据库、日志、崩溃报告或导出文件。")
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
    }

    private var linkList: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("申请 Key")
                .font(.headline)
            if store.keyLinks.isEmpty {
                Text("Soniox → https://console.soniox.com")
                Text("xAI / Grok → https://console.x.ai")
                Text("Gemini → https://aistudio.google.com/apikey")
                Text("硅基流动 → https://cloud.siliconflow.cn")
            } else {
                ForEach(store.keyLinks) { link in
                    Text("\(link.id) → \(link.url)")
                }
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
}
