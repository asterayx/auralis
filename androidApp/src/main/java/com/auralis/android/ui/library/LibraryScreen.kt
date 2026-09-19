package com.auralis.android.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.app.AuralisApp
import com.auralis.app.LibraryController
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(app: AuralisApp, modifier: Modifier = Modifier) {
    val controller = remember { LibraryController(app) }
    val state by controller.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.refresh() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("会话库")
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
                )
            }
        }
    }
}
