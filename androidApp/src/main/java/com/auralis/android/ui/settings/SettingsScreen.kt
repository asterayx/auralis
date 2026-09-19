package com.auralis.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.auralis.app.AuralisApp
import com.auralis.core.newId
import com.auralis.model.GlossaryEntry
import com.auralis.model.LanguageHint
import com.auralis.model.PromptTemplate
import com.auralis.model.TranslationLayout
import com.auralis.provider.ProviderEndpoint
import com.auralis.provider.ProviderKind
import com.auralis.provider.fitsLlm
import com.auralis.provider.fitsStt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AuralisApp, modifier: Modifier = Modifier) {
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var keyDraft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(settings.endpoints.first { it.kind != ProviderKind.DEMO }.id) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf<Boolean?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var modelCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyConfigured by remember { mutableStateOf(false) }
    var vocabDraft by remember { mutableStateOf(settings.vocabulary.joinToString(", ")) }
    var glossaryDraft by remember {
        mutableStateOf(settings.glossary.joinToString("\n") { "${it.source} → ${it.target}" })
    }
    var templateName by remember { mutableStateOf("") }
    var templatePrompt by remember { mutableStateOf("") }

    val keyEndpoints = settings.endpoints.filter { it.kind != ProviderKind.DEMO }
    val sttEndpoints = settings.endpoints.filter { it.fitsStt() }
    val llmEndpoints = settings.endpoints.filter { it.fitsLlm() }
    val activeProfile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }

    fun dismissKeyboard() {
        focus.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(selected) {
        val endpoint = settings.endpoints.firstOrNull { it.id == selected }
        if (endpoint != null) {
            baseUrl = endpoint.baseUrl
            model = endpoint.model
        }
        keyDraft = ""
        keyConfigured = app.hasKey(selected)
    }

    LaunchedEffect(keyDraft, selected, keyConfigured) {
        val trimmed = keyDraft.trim()
        if (trimmed.length < 8 && !(trimmed.isEmpty() && keyConfigured)) return@LaunchedEffect
        delay(450)
        status = "正在拉取模型列表…"
        statusOk = null
        val result = app.probeModels(selected, trimmed)
        status = result.message
        statusOk = result.ok
        if (result.models.isNotEmpty()) {
            modelCandidates = result.models
            if (model.isBlank()) model = result.models.first()
        }
    }

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
        Text("当前档模型组合", style = MaterialTheme.typography.titleMedium)
        SlotDropdown(
            label = "转写模型",
            selectedId = activeProfile?.sttId.orEmpty(),
            options = sttEndpoints,
            onSelect = { id ->
                val profileId = settings.activeProfileId
                val translation = activeProfile?.translationId.orEmpty()
                val post = activeProfile?.postProcessId.orEmpty()
                scope.launch { app.setProfileSlots(profileId, id, translation, post) }
            },
        )
        SlotDropdown(
            label = "翻译模型",
            selectedId = activeProfile?.translationId.orEmpty(),
            options = llmEndpoints,
            onSelect = { id ->
                val profileId = settings.activeProfileId
                val stt = activeProfile?.sttId.orEmpty()
                val post = activeProfile?.postProcessId.orEmpty()
                scope.launch { app.setProfileSlots(profileId, stt, id, post) }
            },
        )
        SlotDropdown(
            label = "后处理模型",
            selectedId = activeProfile?.postProcessId.orEmpty(),
            options = llmEndpoints,
            onSelect = { id ->
                val profileId = settings.activeProfileId
                val stt = activeProfile?.sttId.orEmpty()
                val translation = activeProfile?.translationId.orEmpty()
                scope.launch { app.setProfileSlots(profileId, stt, translation, id) }
            },
        )
        Text("写入 API Key / 端点")
        keyEndpoints.forEach { ep ->
            FilterChip(
                selected = selected == ep.id,
                onClick = {
                    selected = ep.id
                    keyDraft = ""
                    baseUrl = ep.baseUrl
                    model = ep.model
                    modelCandidates = emptyList()
                    status = null
                    statusOk = null
                },
                label = { Text(ep.displayName) },
            )
        }
        OutlinedTextField(
            value = keyDraft,
            onValueChange = {
                keyDraft = it
                if (it.isNotEmpty()) keyConfigured = false
            },
            label = { Text(if (keyConfigured) "•••••••• 已配置" else "API Key") },
            placeholder = { if (keyConfigured) Text("•••••••• 已配置") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        ModelDropdown(
            model = model,
            candidates = modelCandidates,
            onModelChange = { model = it },
            onDone = { dismissKeyboard() },
        )
        TextButton(onClick = {
            scope.launch {
                dismissKeyboard()
                status = "正在保存并测试连通性…"
                statusOk = null
                app.updateEndpoint(selected, baseUrl = baseUrl, model = model)
                val result = app.saveKey(selected, keyDraft)
                status = if (result.ok) "保存成功 · ${result.message}" else "保存失败 · ${result.message}"
                statusOk = result.ok
                if (result.models.isNotEmpty()) modelCandidates = result.models
                if (result.ok) {
                    keyDraft = ""
                    keyConfigured = true
                }
            }
        }) { Text("保存并测试连通性") }
        status?.let {
            Text(
                it,
                color = when (statusOk) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        TextButton(onClick = {
            dismissKeyboard()
            scope.launch { app.setVocabulary(vocabDraft.split(',', '，', '\n')) }
        }) { Text("保存热词") }
        OutlinedTextField(
            value = glossaryDraft,
            onValueChange = { glossaryDraft = it },
            label = { Text("术语表（每行：源词 → 译法）") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        TextButton(onClick = {
            dismissKeyboard()
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
        OutlinedTextField(
            value = templateName,
            onValueChange = { templateName = it },
            label = { Text("自定义模板名") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        OutlinedTextField(
            value = templatePrompt,
            onValueChange = { templatePrompt = it },
            label = { Text("Prompt") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
        )
        TextButton(onClick = {
            if (templateName.isBlank() || templatePrompt.isBlank()) return@TextButton
            dismissKeyboard()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotDropdown(
    label: String,
    selectedId: String,
    options: List<ProviderEndpoint>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.id == selectedId }?.displayName ?: selectedId
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { endpoint ->
                DropdownMenuItem(
                    text = { Text(endpoint.displayName) },
                    onClick = {
                        onSelect(endpoint.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    model: String,
    candidates: List<String>,
    onModelChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    if (candidates.isEmpty()) {
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("模型名") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onModelChange(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
