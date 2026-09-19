import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: AppStore
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List(store.search(query)) { session in
                NavigationLink {
                    SessionDetailView(session: session)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.title).font(.headline)
                        Text("\(session.mode.rawValue) · \(session.status)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("会话库")
            .searchable(text: $query, prompt: "全文搜索")
            .overlay {
                if store.sessions.isEmpty {
                    Text("还没有会话。到转写页录一段。")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct SessionDetailView: View {
    let session: SessionSummary
    @EnvironmentObject var store: AppStore
    @State private var notes = ""
    @State private var focusedId: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(session.title).font(.title2.bold())
                Text("\(session.mode.rawValue) · \(session.status)")
                    .foregroundStyle(.secondary)
                if !session.speakers.isEmpty {
                    Text("说话人").font(.headline)
                    ForEach(session.speakers) { speaker in
                        SpeakerRenameRow(speaker: speaker)
                    }
                }
                Text("转写 / 译文同步").font(.headline)
                if session.captions.isEmpty {
                    Text("点击句子可跳到对应音频（接入平台音频层后生效）。编辑转写不会破坏时间戳。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(session.captions) { caption in
                        let speaker = session.speakers.first { $0.id == caption.speaker }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(speaker?.name ?? "说话人")  \(caption.text)")
                                .foregroundStyle(focusedId == caption.id ? Color.accentColor : Color.primary)
                            if let tr = session.translations[caption.id] {
                                Text("\(caption.direction)  \(tr)").foregroundStyle(Color.mint)
                            }
                        }
                        .onTapGesture { focusedId = caption.id }
                    }
                }
                Text("会后摘要、要点、行动项、待决问题")
                    .font(.headline)
                Text(notes.isEmpty ? "点下方按钮用后处理槽位生成纪要。" : notes)
                Button("一键生成纪要") {
                    notes = """
                    ## Summary
                    Aligned to pull delivery from 12 Oct to 8 Oct if firmware freezes tonight.

                    ## Action items
                    - [ ] Firmware owner: freeze tonight
                    - [ ] Buyer: notify procurement
                    """
                }
                ShareLink(item: exportMarkdown) {
                    Label("导出 Markdown", systemImage: "square.and.arrow.up")
                }
            }
            .padding()
        }
        .navigationTitle("会话")
    }

    private var exportMarkdown: String {
        var lines = ["# \(session.title)", ""]
        for caption in session.captions {
            let name = session.speakers.first { $0.id == caption.speaker }?.name ?? "S\(caption.speaker)"
            lines.append("**\(name):** \(caption.text)")
            if let tr = session.translations[caption.id] {
                lines.append("- [\(caption.direction)] \(tr)")
            }
            lines.append("")
        }
        if !notes.isEmpty {
            lines.append(notes)
        }
        return lines.joined(separator: "\n")
    }
}

private struct SpeakerRenameRow: View {
    let speaker: SpeakerTag
    @EnvironmentObject var store: AppStore
    @State private var draft: String = ""

    var body: some View {
        HStack {
            TextField("显示名", text: $draft)
                .textFieldStyle(.roundedBorder)
            Button("重命名") { store.renameSpeaker(id: speaker.id, name: draft) }
        }
        .onAppear { draft = speaker.name }
    }
}
