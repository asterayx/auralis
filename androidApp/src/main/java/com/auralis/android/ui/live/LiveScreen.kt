package com.auralis.android.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.auralis.model.SessionMode
import com.auralis.pipeline.SessionPipeline
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(app: AuralisApp, mode: SessionMode, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var pipeline by remember { mutableStateOf<SessionPipeline?>(null) }
    val live = pipeline?.state?.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (mode == SessionMode.SCRIBE) "Smart Scribe" else "Translator", style = MaterialTheme.typography.headlineSmall)
        live?.value?.lastError?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        live?.value?.statusMessage?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { pipeline = app.startLive(mode) }
            }) { Text("开始") }
            Button(onClick = {
                scope.launch {
                    pipeline?.let { app.finishLive(it) }
                    pipeline = null
                }
            }) { Text("结束并保存") }
        }
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            live?.value?.segments?.forEach { seg ->
                Text(seg.text)
                if (mode == SessionMode.TRANSLATOR) {
                    live.value.translations.lastOrNull { it.segmentId == seg.id }?.let {
                        Text(it.translatedText, color = Color(0xFF9AD0B8))
                    }
                }
            }
            live?.value?.interimText?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFF9AA3B5))
            }
        }
        Text("灰字是临时词，定稿后变白。音频始终先落本机。", style = MaterialTheme.typography.bodySmall)
    }
}
