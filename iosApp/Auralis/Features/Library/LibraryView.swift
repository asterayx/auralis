import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: AppStore
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List(store.search(query)) { session in
                NavigationLink {
                    SessionDetailView(sessionId: session.id)
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
            .onChange(of: query) { _, value in
                store.refreshLibrary(query: value)
            }
            .onAppear { store.refreshLibrary(query: query) }
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
    let sessionId: String
    @EnvironmentObject var store: AppStore
    @State private var detail: SessionDetail?
    @State private var notes = ""
    @State private var focusedId: String?
    @State private var seekLabel: String?
    @State private var editingId: String?
    @State private var editDraft = ""
    @State private var export = ""

    var body: some View {
        ScrollView {
            if let session = detail {
                VStack(alignment: .leading, spacing: 16) {
                    Text(session.title).font(.title2.bold())
                    Text("\(session.mode.rawValue) · \(session.status)")
                        .foregroundStyle(.secondary)
                    if let usage = session.usage {
                        Text(String(format: "用量（估算）：音频 %.1f 分钟 · %d tokens · $%.2f（估算）", usage.audioMinutes, usage.tokens, usage.estimate))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if !session.speakers.isEmpty {
                        Text("说话人").font(.headline)
                        ForEach(session.speakers) { speaker in
                            SpeakerRenameRow(sessionId: sessionId, speaker: speaker) { next in
                                detail = next
                            }
                        }
                    }
                    Text("转写 / 译文同步").font(.headline)
                    Text("点一句跳到对应时间戳。编辑只写覆盖层，不破坏时间戳。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if let seekLabel {
                        Text(seekLabel).foregroundStyle(Color.accentColor).font(.footnote)
                    }
                    if session.captions.isEmpty {
                        Text("接入平台音频层后可点击跳转。")
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
                                Button("编辑") {
                                    editingId = caption.id
                                    editDraft = caption.text
                                }
                                .buttonStyle(.bordered)
                            }
                            .onTapGesture {
                                focusedId = caption.id
                                store.seekLabel(sessionId: sessionId, segmentId: caption.id) { seekLabel = $0 }
                            }
                        }
                    }
                    if editingId != nil {
                        TextField("编辑转写（不破坏时间戳）", text: $editDraft, axis: .vertical)
                            .textFieldStyle(.roundedBorder)
                        Button("保存编辑") {
                            if let editingId {
                                store.editCaption(sessionId: sessionId, captionId: editingId, text: editDraft) {
                                    detail = $0
                                }
                            }
                            editingId = nil
                        }
                    }
                    Text("会后摘要 · Prompt 模板").font(.headline)
                    Picker("模板", selection: $store.defaultTemplateId) {
                        ForEach(store.templates) { template in
                            Text(template.name).tag(template.id)
                        }
                    }
                    Text(notes.isEmpty ? (session.notes.isEmpty ? "点下方按钮用后处理槽位生成纪要。" : session.notes) : notes)
                    Button("用当前模板生成纪要") {
                        store.generateNotes(sessionId: sessionId, templateId: store.defaultTemplateId) { notes = $0 }
                    }
                    Button {
                        store.exportOpen(sessionId: sessionId, markdown: true) { export = $0 }
                    } label: {
                        Label("导出 Markdown", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        store.exportOpen(sessionId: sessionId, markdown: false) { export = $0 }
                    } label: {
                        Label("导出 TXT", systemImage: "doc")
                    }
                    if !export.isEmpty {
                        ShareLink(item: export) {
                            Label("系统分享", systemImage: "square.and.arrow.up.on.square")
                        }
                        Text(export).font(.footnote)
                    }
                }
                .padding()
            } else {
                Text("找不到这场会话。").padding()
            }
        }
        .navigationTitle("会话")
        .onAppear {
            store.loadDetail(sessionId: sessionId) { detail = $0 }
        }
    }
}

private struct SpeakerRenameRow: View {
    let sessionId: String
    let speaker: SpeakerTag
    var onRenamed: (SessionDetail?) -> Void
    @EnvironmentObject var store: AppStore
    @State private var draft: String = ""

    var body: some View {
        HStack {
            TextField("显示名", text: $draft)
                .textFieldStyle(.roundedBorder)
            Button("重命名") {
                store.renameSpeaker(sessionId: sessionId, id: speaker.id, name: draft, completion: onRenamed)
            }
        }
        .onAppear { draft = speaker.name }
    }
}
