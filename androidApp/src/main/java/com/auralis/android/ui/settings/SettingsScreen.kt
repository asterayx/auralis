package com.auralis.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AuralisApp, modifier: Modifier = Modifier) {
    val settings by app.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var keyDraft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(settings.endpoints.first().id) }
    var status by remember { mutableStateOf<String?>(null) }
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
        Text("写入 API Key")
        settings.endpoints.filter { it.kind.name != "DEMO" }.forEach { ep ->
            FilterChip(
                selected = selected == ep.id,
                onClick = { selected = ep.id },
                label = { Text(ep.displayName) },
            )
        }
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        TextButton(onClick = {
            scope.launch {
                val result = app.saveKey(selected, keyDraft)
                status = result.message
                keyDraft = ""
            }
        }) { Text("保存并测试连通性") }
        status?.let { Text(it) }
        Text("隐私", style = MaterialTheme.typography.titleMedium)
        Text("你的数据会发送给你所选的 Provider。默认关闭崩溃上报。")
        Text("后台录音：请把 Auralis 加入厂商省电白名单，避免被杀进程。")
    }
}
