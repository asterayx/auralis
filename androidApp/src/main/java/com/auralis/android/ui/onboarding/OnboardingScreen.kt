package com.auralis.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.app.AuralisApp
import com.auralis.provider.Presets
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(app: AuralisApp, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Auralis 聆语", style = MaterialTheme.typography.headlineLarge)
        Text("本地优先、模型可插拔的语音工作台。密钥只存在本机，音频默认不离开这台设备。")
        Text("你的数据会发送给你所选的 Provider。Auralis 没有自有服务器。")
        Text("推荐 3 分钟起步：先用内置演示跑通一次，再填 Soniox / Grok Key。")
        Text("申请 Key：")
        Presets.applyKeyLinks.forEach { (id, url) ->
            Text("• $id → $url", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            scope.launch {
                app.persist { it.copy(activeProfileId = Presets.demo.id) }
                onDone()
            }
        }) { Text("先用演示（无需 Key）") }
        OutlinedButton(onClick = {
            scope.launch {
                app.persist { it.copy(activeProfileId = Presets.qualityMeeting.id) }
                onDone()
            }
        }) { Text("我已有 Key，去设置页配置") }
    }
}
