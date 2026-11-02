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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.phoneassistant.chat.ChatViewModel
import cn.phoneassistant.core.AuditEntry
import cn.phoneassistant.data.NoteEntry
import cn.phoneassistant.data.NoteStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** “记录”页：备忘与动作历史两个页签。 */
@Composable
fun RecordsScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("备忘") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("动作历史") })
        }
        if (tab == 0) {
            NotesTab(modifier = Modifier.fillMaxSize())
        } else {
            HistoryTab(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NotesTab(modifier: Modifier = Modifier) {
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

@Composable
private fun HistoryTab(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.audit.collectAsState()
    val integrity by viewModel.auditIntegrity.collectAsState()
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "只记录本机的执行结果，不保存密钥或完整聊天内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MutedGrey,
        )
        Text(
            when (integrity) {
                null, "" -> "审计链：完整（SHA-256 哈希链）"
                "AUDIT_TAMPERED" -> "审计链异常：有记录被改动"
                "AUDIT_CHAIN_BROKEN" -> "审计链异常：中间记录缺失"
                else -> "审计链异常：$integrity"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (integrity == null || integrity == "") MutedGrey else MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                "还没有动作记录。在对话里执行过一次操作后，这里会出现。",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedGrey,
            )
        } else {
            val undone = entries
                .filter { it.intent == "UNDO_SAVE_NOTE" && it.outcome == "success" }
                .mapNotNull { it.artifact }
                .toSet()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries.asReversed(), key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        undone = entry.artifact != null && entry.artifact in undone,
                        onUndo = { viewModel.undoNote(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: AuditEntry, undone: Boolean, onUndo: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(auditIntentLabel(entry.intent), style = MaterialTheme.typography.titleMedium)
                if (entry.summary.isNotBlank()) {
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${formatDate(entry.tsMillis)} · ${if (entry.outcome == "success") "成功" else "失败"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedGrey,
                )
            }
            if (entry.intent == "SAVE_NOTE" && entry.outcome == "success" && entry.artifact != null && !undone) {
                TextButton(onClick = onUndo) { Text("撤销", color = DangerRed) }
            }
        }
    }
}

private fun auditIntentLabel(name: String): String = when (name) {
    "OPEN_APP" -> "打开应用"
    "SET_ALARM" -> "设置闹钟"
    "CREATE_CALENDAR_EVENT" -> "创建日程"
    "SET_TIMER" -> "设置计时"
    "WEB_SEARCH" -> "网络搜索"
    "SAVE_NOTE" -> "保存备忘"
    "CHAT_ONLY" -> "纯聊天"
    "UNDO_SAVE_NOTE" -> "撤销备忘"
    else -> name
}
