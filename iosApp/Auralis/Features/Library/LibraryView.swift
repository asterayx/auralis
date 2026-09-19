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
    @State private var notes = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(session.title).font(.title2.bold())
                Text("点击句子可跳到对应音频（接入平台音频层后生效）。编辑转写不会破坏时间戳。")
                    .foregroundStyle(.secondary)
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
                ShareLink(item: "# \(session.title)\n\n\(notes)") {
                    Label("导出 Markdown", systemImage: "square.and.arrow.up")
                }
            }
            .padding()
        }
        .navigationTitle("会话")
    }
}
