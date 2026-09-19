package com.auralis.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.auralis.app.AuralisApp
import com.auralis.core.newId
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PromptTemplate
import com.auralis.model.TranslationLayout
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AuralisApp, modifier: Modifier = Modifier) {
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var keyDraft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(settings.endpoints.first().id) }
    var status by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var vocabDraft by remember { mutableStateOf(settings.vocabulary.joinToString(", ")) }
    var glossaryDraft by remember {
        mutableStateOf(settings.glossary.joinToString("\n") { "${it.source} → ${it.target}" })
    }
    var templateName by remember { mutableStateOf("") }
    var templatePrompt by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BYOK 与配置档", style = MaterialTheme.typography.headlineSmall)
        Text("密钥只写入 Keystore，不会进入数据库、日志或导出文件。")
        Text("配置档")
        settings.profiles.forEach { profile ->
            FilterChip(
                selected = settings.activeProfileId == profile.id,
                onClick = { scope.launch { app.persist { it.copy(activeProfileId = profile.id) } } },
                label = { Text(profile.name) },
            )
            Text(profile.description, style = MaterialTheme.typography.bodySmall)
        }
        Text("写入 API Key / 端点")
        settings.endpoints.filter { it.kind.name != "DEMO" }.forEach { ep ->
            FilterChip(
                selected = selected == ep.id,
                onClick = {
                    selected = ep.id
                    baseUrl = ep.baseUrl
                    model = ep.model
                },
                label = { Text(ep.displayName) },
            )
        }
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            scope.launch {
                app.updateEndpoint(selected, baseUrl = baseUrl, model = model)
                if (keyDraft.isNotBlank()) {
                    val result = app.saveKey(selected, keyDraft)
                    status = result.message
                    keyDraft = ""
                } else {
                    status = "已更新 Base URL / 模型。"
                }
            }
        }) { Text("保存并测试连通性") }
        status?.let { Text(it) }
        Text("语种 · 热词 · 术语表", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageHint.presets.forEach { hint ->
                FilterChip(
                    selected = settings.language == hint,
                    onClick = { scope.launch { app.persist { it.copy(language = hint) } } },
                    label = { Text(hint.label) },
                )
            }
        }
        OutlinedTextField(
            value = vocabDraft,
            onValueChange = { vocabDraft = it },
            label = { Text("热词（逗号分隔）") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            scope.launch { app.setVocabulary(vocabDraft.split(',', '，', '\n')) }
        }) { Text("保存热词") }
        OutlinedTextField(
            value = glossaryDraft,
            onValueChange = { glossaryDraft = it },
            label = { Text("术语表（每行：源词 → 译法）") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            scope.launch {
                app.setGlossary(
                    glossaryDraft.lineSequence().mapNotNull { line ->
                        val parts = line.split("→", "->", limit = 2).map { it.trim() }
                        if (parts.size == 2) GlossaryEntry(parts[0], parts[1]) else null
                    }.toList(),
                )
            }
        }) { Text("保存术语表") }
        Text("Prompt 模板库", style = MaterialTheme.typography.titleMedium)
        settings.templates.forEach { template ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = template.isDefault,
                    onClick = { scope.launch { app.setDefaultTemplate(template.id) } },
                    label = { Text(template.name + if (template.isDefault) " · 默认" else "") },
                )
                if (!template.isBuiltIn) {
                    TextButton(onClick = { scope.launch { app.deleteTemplate(template.id) } }) { Text("删除") }
                }
            }
        }
        OutlinedTextField(value = templateName, onValueChange = { templateName = it }, label = { Text("自定义模板名") })
        OutlinedTextField(value = templatePrompt, onValueChange = { templatePrompt = it }, label = { Text("Prompt") })
        TextButton(onClick = {
            if (templateName.isBlank() || templatePrompt.isBlank()) return@TextButton
            scope.launch {
                app.saveTemplate(
                    PromptTemplate(
                        id = newId("tpl"),
                        name = templateName,
                        description = "custom",
                        prompt = templatePrompt,
                    ),
                )
                templateName = ""
                templatePrompt = ""
            }
        }) { Text("添加自定义模板") }
        Text("翻译与说话人", style = MaterialTheme.typography.titleMedium)
        FilterChip(
            selected = settings.bidirectional,
            onClick = { scope.launch { app.persist { it.copy(bidirectional = !it.bidirectional) } } },
            label = { Text(if (settings.bidirectional) "双向翻译开" else "双向翻译关") },
        )
        FilterChip(
            selected = settings.diarization,
            onClick = { scope.launch { app.persist { it.copy(diarization = !it.diarization) } } },
            label = { Text(if (settings.diarization) "说话人分轨开" else "说话人分轨关") },
        )
        FilterChip(
            selected = settings.keepAudioDefault,
            onClick = { scope.launch { app.persist { it.copy(keepAudioDefault = !it.keepAudioDefault) } } },
            label = { Text(if (settings.keepAudioDefault) "保留本地音频" else "不保留音频文件") },
        )
        FilterChip(
            selected = settings.translationLayout == TranslationLayout.SIDE_BY_SIDE,
            onClick = { scope.launch { app.persist { it.copy(translationLayout = TranslationLayout.SIDE_BY_SIDE) } } },
            label = { Text("并排同步") },
        )
        Text("本侧 ${settings.localLanguage} ↔ 对侧 ${settings.remoteLanguage}。中英会议默认互译。")
        Text("无障碍", style = MaterialTheme.typography.titleMedium)
        Text("字幕字号 ${ (settings.fontScale * 100).toInt() }%")
        Slider(
            value = settings.fontScale,
            onValueChange = { value -> scope.launch { app.persist { it.copy(fontScale = value) } } },
            valueRange = 1f..2f,
        )
        Text("隐私", style = MaterialTheme.typography.titleMedium)
        Text("你的数据会发送给你所选的 Provider。默认关闭崩溃上报。")
        Text("后台录音：请把 Auralis 加入厂商省电白名单，避免被杀进程。")
    }
}
