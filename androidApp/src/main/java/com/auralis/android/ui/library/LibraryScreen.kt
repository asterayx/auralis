package com.auralis.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.app.AuralisApp
import com.auralis.app.LibraryController
import com.auralis.export.ExportFormat
import com.auralis.export.TranscriptExporter
import com.auralis.model.SessionBundle
import com.auralis.pipeline.LiveSync
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(app: AuralisApp, modifier: Modifier = Modifier) {
    val controller = remember { LibraryController(app) }
    val state by controller.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.refresh() }
    if (selectedId != null) {
        SessionDetailScreen(app, selectedId!!) {
            selectedId = null
            scope.launch { controller.refresh(query) }
        }
        return
    }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("会话库", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                scope.launch { controller.refresh(it) }
            },
            label = { Text("全文搜索") },
        )
        if (state.sessions.isEmpty()) {
            Text("还没有会话。到「转写」页录一段，或用演示配置先跑通。", Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(state.sessions, key = { it.id }) { session ->
                ListItem(
                    headlineContent = { Text(session.title) },
                    supportingContent = { Text("${session.mode} · ${session.status}") },
                    modifier = Modifier.clickable { selectedId = session.id },
                )
            }
        }
    }
}

@Composable
private fun SessionDetailScreen(app: AuralisApp, sessionId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bundle by remember { mutableStateOf<SessionBundle?>(null) }
    var notes by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) { bundle = app.sessions.get(sessionId) }
    val current = bundle
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回会话库") }
        if (current == null) {
            Text("找不到这场会话。")
            return
        }
        Text(current.session.title, style = MaterialTheme.typography.headlineSmall)
        Text("${current.session.mode} · ${current.session.status}", color = Color(0xFF9AA3B5))
        if (current.speakers.isNotEmpty()) {
            Text("说话人", style = MaterialTheme.typography.titleMedium)
            current.speakers.forEach { speaker ->
                var draft by remember(speaker.id, speaker.displayName) { mutableStateOf(speaker.displayName) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(speaker.id) })
                    TextButton(onClick = {
                        scope.launch {
                            app.renameSpeaker(sessionId, speaker.id, draft)
                            bundle = app.sessions.get(sessionId)
                        }
                    }) { Text("重命名") }
                }
            }
        }
        Text("转写 / 译文同步", style = MaterialTheme.typography.titleMedium)
        LiveSync.lines(current.segments, current.translations, current.speakers).forEach { line ->
            Column(
                Modifier
                    .clickable { focusedId = line.segment.id }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    "${line.speaker?.displayName ?: "未知"} · ${line.segment.text}",
                    color = if (line.segment.id == focusedId) Color(0xFF7C9CFF) else Color.Unspecified,
                )
                line.translation?.let {
                    Text("${it.directionLabel}  ${it.translatedText}", color = Color(0xFF9AD0B8))
                }
            }
        }
        Text("会后纪要", style = MaterialTheme.typography.titleMedium)
        Text(notes ?: current.postProcess.lastOrNull()?.content ?: "还没有纪要。")
        TextButton(onClick = {
            scope.launch {
                notes = app.postProcess(sessionId).content
                bundle = app.sessions.get(sessionId)
            }
        }) { Text("一键生成纪要") }
        TextButton(onClick = {
            export = TranscriptExporter.export(current, ExportFormat.MARKDOWN)
        }) { Text("导出 Markdown") }
        export?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
