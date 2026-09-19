package com.auralis.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auralis.app.AuralisApp
import com.auralis.android.ui.library.LibraryScreen
import com.auralis.android.ui.live.LiveScreen
import com.auralis.android.ui.onboarding.OnboardingScreen
import com.auralis.android.ui.settings.SettingsScreen
import com.auralis.model.SessionMode

private val Ink = Color(0xFF0B0D12)
private val Accent = Color(0xFF7C9CFF)

@Composable
fun AuralisRoot(app: AuralisApp) {
    LaunchedEffect(Unit) { app.load() }
    var tab by remember { mutableIntStateOf(0) }
    var onboarded by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Ink,
            surface = Color(0xFF141824),
        ),
    ) {
        if (onboarded == 0) {
            OnboardingScreen(app) { onboarded = 1 }
            return@MaterialTheme
        }
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.Folder, null) },
                        label = { Text("库") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.Mic, null) },
                        label = { Text("转写") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.Translate, null) },
                        label = { Text("翻译") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text("设置") },
                    )
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                0 -> LibraryScreen(app, modifier)
                1 -> LiveScreen(app, SessionMode.SCRIBE, modifier)
                2 -> LiveScreen(app, SessionMode.TRANSLATOR, modifier)
                else -> SettingsScreen(app, modifier)
            }
        }
    }
}
