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
                    Text(mode == .scribe ? "Scribe" : "双向翻译")
                        .foregroundStyle(.secondary)
                }
                speakerLegend
                if mode == .translator {
                    Picker("布局", selection: $store.layout) {
                        Text("并排同步").tag(TranslationLayout.sideBySide)
                        Text("上下堆叠").tag(TranslationLayout.stacked)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: store.layout) { _, value in
                        store.setLayout(value)
                    }
                }
                ScrollViewReader { proxy in
                    ScrollView {
                        content
                    }
                    .onChange(of: store.focusedCaptionId) { _, id in
                        if let id { withAnimation { proxy.scrollTo(id, anchor: .center) } }
                    }
                }
            }
            .padding()
            .navigationTitle(mode == .scribe ? "实时转写" : "实时翻译")
        }
    }

    @ViewBuilder
    private var content: some View {
        if case .running(_, let captions, let interim, let translations, let error) = store.live {
            if let error { Text(error).foregroundStyle(.red) }
            if mode == .translator && store.layout == .sideBySide {
                HStack(alignment: .top, spacing: 12) {
                    captionColumn(title: "原文", captions: captions, translations: translations, showTranslation: false)
                    captionColumn(title: "译文", captions: captions, translations: translations, showTranslation: true)
                }
            } else {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(captions) { caption in
                        captionCard(caption, translation: translations[caption.id], translationOnly: false)
                    }
                }
            }
            if !interim.isEmpty {
                Text(interim)
                    .font(.system(size: 18 * store.fontScale))
                    .foregroundStyle(.gray)
            }
        } else {
            Text(mode == .translator
                 ? "点一句两侧同步高亮。灰字是临时词。说话人来自 STT 分轨。"
                 : "灰色是临时词，定稿后变为正式文本。断网时只录音，恢复后自动补转写。")
                .foregroundStyle(.secondary)
        }
    }

    private var speakerLegend: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.speakers) { speaker in
                    HStack(spacing: 6) {
                        Circle().fill(color(speaker.color)).frame(width: 8, height: 8)
                        Text(speaker.name).font(.caption)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.white.opacity(0.06), in: Capsule())
                }
            }
        }
    }

    private func captionColumn(title: String, captions: [Caption], translations: [String: String], showTranslation: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            ForEach(captions) { caption in
                captionCard(caption, translation: translations[caption.id], translationOnly: showTranslation)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func captionCard(_ caption: Caption, translation: String?, translationOnly: Bool) -> some View {
        let speaker = store.speaker(for: caption.speaker)
        let focused = store.focusedCaptionId == caption.id
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                if let speaker {
                    Text(speaker.name)
                        .font(.caption.bold())
                        .foregroundStyle(color(speaker.color))
                }
                if store.bidirectional && !caption.direction.isEmpty {
                    Text(caption.direction).font(.caption2).foregroundStyle(.secondary)
                }
            }
            if !translationOnly {
                Text(caption.text).font(.system(size: 18 * store.fontScale))
            }
            if mode == .translator, let translation {
                Text(translation)
                    .font(.system(size: 17 * store.fontScale))
                    .foregroundStyle(Color.mint)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(focused ? Color.white.opacity(0.08) : Color.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 12))
        .overlay {
            if focused {
                RoundedRectangle(cornerRadius: 12).stroke(color(speaker?.color ?? "7C9CFF"), lineWidth: 1)
            }
        }
        .id(caption.id)
        .onTapGesture { store.focusedCaptionId = caption.id }
    }

    private func color(_ hex: String) -> Color {
        let scanner = Scanner(string: hex)
        var value: UInt64 = 0
        scanner.scanHexInt64(&value)
        return Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}
