package cn.phoneassistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.phoneassistant.data.NoteEntry
import cn.phoneassistant.data.NoteStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备忘列表：只展示本机 notes 目录下的 .txt，支持查看与删除。 */
@Composable
fun NotesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<NoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<NoteEntry?>(null) }
    var openedContent by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<NoteEntry?>(null) }

    LaunchedEffect(Unit) {
        notes = withContext(Dispatchers.IO) { NoteStore.list(context) }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("只存在本机，卸载应用会一并清除。", style = MaterialTheme.typography.bodySmall, color = MutedGrey)
        Spacer(modifier = Modifier.height(12.dp))
        when {
            loading -> Text("正在读取…", style = MaterialTheme.typography.bodyMedium, color = MutedGrey)
            notes.isEmpty() -> Text(
                "还没有备忘。在对话里说“记一下……”，确认后就会存到这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedGrey,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notes, key = { it.fileName }) { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeCard,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        openedContent = withContext(Dispatchers.IO) {
                                            NoteStore.read(context, note.fileName).orEmpty()
                                        }
                                        opened = note
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    note.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatDate(note.modifiedAt)} · ${note.sizeBytes} 字节",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedGrey,
                                )
                            }
                            TextButton(onClick = { pendingDelete = note }) {
                                Text("删除", color = DangerRed)
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { note ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(note.title) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(openedContent.ifBlank { "（空）" }, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = note; opened = null }) {
                    Text("删除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { opened = null }) { Text("关闭") }
            },
        )
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条备忘？") },
            text = { Text(note.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { NoteStore.delete(context, note.fileName) }
                            notes = withContext(Dispatchers.IO) { NoteStore.list(context) }
                            pendingDelete = null
                        }
                    },
                ) {
                    Text("删除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
