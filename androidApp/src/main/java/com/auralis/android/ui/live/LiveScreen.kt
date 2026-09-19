package com.auralis.android.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.auralis.app.AuralisApp
import com.auralis.model.SessionMode
import com.auralis.model.TranslationLayout
import com.auralis.pipeline.LiveSync
import com.auralis.pipeline.SessionPipeline
import com.auralis.pipeline.SyncedCaption
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(app: AuralisApp, mode: SessionMode, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by app.settings.collectAsState()
    var pipeline by remember { mutableStateOf<SessionPipeline?>(null) }
    val live = pipeline?.state?.collectAsState()
    var focusedId by remember { mutableStateOf<String?>(null) }
    val lines = live?.value?.let { LiveSync.lines(it.segments, it.translations, it.speakers) }.orEmpty()
    val startLive = {
        scope.launch { pipeline = app.startLive(mode) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive()
    }
    LaunchedEffect(live?.value?.focusedSegmentId) {
        focusedId = live?.value?.focusedSegmentId ?: focusedId
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (mode == SessionMode.SCRIBE) "Smart Scribe" else "双向翻译",
            style = MaterialTheme.typography.headlineSmall,
        )
        live?.value?.lastError?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        live?.value?.statusMessage?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startLive() else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text("开始") }
            Button(onClick = {
                scope.launch {
                    pipeline?.let { app.finishLive(it) }
                    pipeline = null
                    focusedId = null
                }
            }) { Text("结束并保存") }
        }
        SpeakerLegend(lines)
        if (mode == SessionMode.TRANSLATOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.translationLayout == TranslationLayout.SIDE_BY_SIDE,
                    onClick = { scope.launch { app.persist { it.copy(translationLayout = TranslationLayout.SIDE_BY_SIDE) } } },
                    label = { Text("并排同步") },
                )
                FilterChip(
                    selected = settings.translationLayout == TranslationLayout.STACKED,
                    onClick = { scope.launch { app.persist { it.copy(translationLayout = TranslationLayout.STACKED) } } },
                    label = { Text("上下堆叠") },
                )
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(focusedId, lines.size) { scroll.animateScrollTo(scroll.maxValue) }
        if (mode == SessionMode.TRANSLATOR && settings.translationLayout == TranslationLayout.SIDE_BY_SIDE) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CaptionColumn(
                    title = "原文",
                    lines = lines,
                    focusedId = focusedId,
                    showTranslation = false,
                    fontScale = settings.fontScale,
                    modifier = Modifier.weight(1f).verticalScroll(scroll),
                    onFocus = { focusedId = it },
                )
                CaptionColumn(
                    title = "译文",
                    lines = lines,
                    focusedId = focusedId,
                    showTranslation = true,
                    fontScale = settings.fontScale,
                    modifier = Modifier.weight(1f).verticalScroll(scroll),
                    onFocus = { focusedId = it },
                )
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                lines.forEach { line ->
                    CaptionCard(
                        line = line,
                        focused = line.segment.id == focusedId,
                        showTranslation = mode == SessionMode.TRANSLATOR,
                        fontScale = settings.fontScale,
                        onClick = { focusedId = line.segment.id },
                    )
                }
                live?.value?.interimText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Color(0xFF9AA3B5),
                        fontSize = (18 * settings.fontScale).sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Text(
            if (mode == SessionMode.TRANSLATOR) {
                "点一句两侧同步高亮。灰字是临时词。说话人来自 STT 分轨。"
            } else {
                "灰字是临时词，定稿后变白。音频始终先落本机。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpeakerLegend(lines: List<SyncedCaption>) {
    val speakers = lines.mapNotNull { it.speaker }.distinctBy { it.id }
    if (speakers.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        speakers.forEach { speaker ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1B2030))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(colorOf(speaker.colorHex))
                        .padding(6.dp),
                )
                Text(speaker.displayName, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CaptionColumn(
    title: String,
    lines: List<SyncedCaption>,
    focusedId: String?,
    showTranslation: Boolean,
    fontScale: Float,
    modifier: Modifier,
    onFocus: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFF9AA3B5))
        lines.forEach { line ->
            CaptionCard(
                line = line,
                focused = line.segment.id == focusedId,
                showTranslation = showTranslation,
                translationOnly = showTranslation,
                fontScale = fontScale,
                onClick = { onFocus(line.segment.id) },
            )
        }
    }
}

@Composable
private fun CaptionCard(
    line: SyncedCaption,
    focused: Boolean,
    showTranslation: Boolean,
    translationOnly: Boolean = false,
    fontScale: Float = 1f,
    onClick: () -> Unit,
) {
    val accent = colorOf(line.speaker?.colorHex ?: "#7C9CFF")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF1E2740) else Color(0xFF141824))
            .then(if (focused) Modifier.border(1.dp, accent, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            line.speaker?.let { Text(it.displayName, color = accent, style = MaterialTheme.typography.labelMedium) }
            line.translation?.let {
                Text(it.directionLabel, color = Color(0xFF9AA3B5), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!translationOnly) {
            Text(line.segment.text, fontSize = (18 * fontScale).sp)
        }
        if (showTranslation) {
            Text(
                line.translation?.translatedText ?: "…",
                color = Color(0xFF9AD0B8),
                fontSize = (17 * fontScale).sp,
            )
        }
    }
}

private fun colorOf(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: return Color(0xFF7C9CFF)
    return Color(0xFF000000 or value)
}
