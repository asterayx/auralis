import SwiftUI

struct LiveView: View {
    let mode: SessionMode
    @EnvironmentObject var store: AppStore

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Button("开始") { store.start(mode: mode) }
                        .buttonStyle(.borderedProminent)
                    Button("结束并保存") { store.stop() }
                        .buttonStyle(.bordered)
                    Spacer()
                    Text(mode == .scribe ? "Scribe" : "Translator")
                        .foregroundStyle(.secondary)
                }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if case .running(_, let captions, let interim, let translations, let error) = store.live {
                            if let error { Text(error).foregroundStyle(.red) }
                            ForEach(captions) { caption in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("S\(caption.speaker)  \(caption.text)")
                                        .font(.system(size: 18 * store.fontScale))
                                    if mode == .translator, let tr = translations[caption.id] {
                                        Text(tr)
                                            .font(.system(size: 17 * store.fontScale))
                                            .foregroundStyle(Color.mint)
                                    }
                                }
                            }
                            if !interim.isEmpty {
                                Text(interim)
                                    .font(.system(size: 18 * store.fontScale))
                                    .foregroundStyle(.gray)
                            }
                        } else {
                            Text("灰色是临时词，定稿后变为正式文本。断网时只录音，恢复后自动补转写。")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding()
            .navigationTitle(mode == .scribe ? "实时转写" : "实时翻译")
        }
    }
}
