package cn.phoneassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.phoneassistant.chat.ChatViewModel
import cn.phoneassistant.core.AssistantIntent
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.llm.LlmClient
import cn.phoneassistant.model.ChatMessage
import cn.phoneassistant.model.PendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { CHAT, SETTINGS, NOTES, PAIRING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.CHAT -> "手机助理"
                            Screen.SETTINGS -> "设置"
                            Screen.NOTES -> "备忘"
                            Screen.PAIRING -> "配对"
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.CHAT) {
                        TextButton(onClick = { screen = Screen.CHAT }) { Text("返回") }
                    }
                },
                actions = {
                    if (screen == Screen.CHAT) {
                        TextButton(onClick = { screen = Screen.NOTES }) { Text("备忘") }
                        TextButton(onClick = { screen = Screen.PAIRING }) { Text("配对") }
                        TextButton(onClick = { screen = Screen.SETTINGS }) { Text("设置") }
                    }
                },
            )
        },
    ) { padding ->
        when (screen) {
            Screen.CHAT -> ChatContent(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
            Screen.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
            Screen.NOTES -> NotesScreen(modifier = Modifier.padding(padding))
            Screen.PAIRING -> PairingScreen(
                identity = viewModel.identity,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ChatContent(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val messages by viewModel.messages.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val thinking by viewModel.thinking.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, pending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (thinking) {
                item { Text("正在思考…", style = MaterialTheme.typography.bodySmall, color = MutedGrey) }
            }
        }

        pending?.let { pendingIntent ->
            IntentConfirmCard(
                pending = pendingIntent,
                onSelectApp = viewModel::selectAppCandidate,
                onConfirm = viewModel::confirmPending,
                onCancel = viewModel::cancelPending,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说句话，比如：明天早上 7 点半叫我") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        viewModel.send(input)
                        input = ""
                    },
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = !thinking && pending == null && input.isNotBlank(),
            ) {
                Text("发送")
            }
        }
        Text(
            text = "需要确认的动作会先弹出卡片，等你点确认才执行；点取消不会产生任何副作用。",
            style = MaterialTheme.typography.bodySmall,
            color = MutedGrey,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(
                if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart,
            ),
            shape = ShapeBubble,
            color = when {
                message.isError -> MaterialTheme.colorScheme.surfaceVariant
                message.isUser -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    message.isError -> DangerRed
                    message.isUser -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun IntentConfirmCard(
    pending: PendingIntent,
    onSelectApp: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = ShapeCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("请确认这个操作", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("动作：${intentTypeLabel(pending.intent.type)}", style = MaterialTheme.typography.bodyMedium)
            describeParams(pending.intent).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            pending.intent.reply?.takeIf { it.isNotBlank() }?.let { reply ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(reply, style = MaterialTheme.typography.bodySmall, color = MutedGrey)
            }
            if (pending.intent.type == IntentType.OPEN_APP) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("请选择要打开的应用：", style = MaterialTheme.typography.bodyMedium)
                pending.appCandidates.forEach { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(
                                selected = pending.selectedPackage == candidate.packageName,
                                onClick = { onSelectApp(candidate.packageName) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = pending.selectedPackage == candidate.packageName,
                            onClick = { onSelectApp(candidate.packageName) },
                        )
                        Column {
                            Text(candidate.label, style = MaterialTheme.typography.bodyMedium)
                            Text(candidate.packageName, style = MaterialTheme.typography.bodySmall, color = MutedGrey)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onConfirm,
                    enabled = pending.intent.type != IntentType.OPEN_APP ||
                        !pending.selectedPackage.isNullOrBlank(),
                ) {
                    Text("确认执行")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = viewModel.settings
    var baseUrl by remember { mutableStateOf(store.baseUrl) }
    var model by remember { mutableStateOf(store.model) }
    var apiKeyInput by remember { mutableStateOf("") }
    var savedMessage by remember { mutableStateOf("") }
    var savedIsError by remember { mutableStateOf(false) }
    var storageState by remember { mutableStateOf(store.hasApiKey to store.isConfigured) }
    var testMessage by remember { mutableStateOf("") }
    var testIsError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "密钥只保存在本机加密存储中，不会上传到除你填写的服务地址之外的任何地方，" +
                "也不会显示原文。留空密钥则保持上次的设置不变。",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedGrey,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务地址（OpenAI 兼容接口的根地址）") },
            placeholder = { Text("填你的服务地址，以 /v1 结尾") },
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密钥（再次输入可覆盖）") },
            placeholder = {
                Text(if (storageState.first) "已设置，留空保持不变" else "未设置")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        val failure = store.storageFailure
        if (failure != null) {
            Text(failure, style = MaterialTheme.typography.bodyMedium, color = DangerRed)
        }
        Text(
            "当前状态：${if (storageState.first) "密钥已设置" else "密钥未设置"}；" +
                if (storageState.second) "已配齐，可以对话。" else "还没配齐，对话会被拒绝且不会调用接口。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row {
            Button(
                onClick = {
                    val urlError = validateBaseUrl(baseUrl)
                    when {
                        urlError != null -> {
                            savedIsError = true
                            savedMessage = urlError
                        }
                        model.isBlank() -> {
                            savedIsError = true
                            savedMessage = "请填写模型名。"
                        }
                        else -> {
                            store.baseUrl = baseUrl
                            store.model = model
                            if (apiKeyInput.isNotBlank()) {
                                store.apiKey = apiKeyInput
                                apiKeyInput = ""
                            }
                            storageState = store.hasApiKey to store.isConfigured
                            savedIsError = store.storageFailure != null
                            savedMessage = if (savedIsError) {
                                "保存失败：" + store.storageFailure.orEmpty()
                            } else {
                                "已保存。"
                            }
                        }
                    }
                },
            ) {
                Text("保存设置")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    store.clearApiKey()
                    apiKeyInput = ""
                    storageState = store.hasApiKey to store.isConfigured
                    savedIsError = false
                    savedMessage = "密钥已清除，之后对话会被拒绝，直到重新填写。"
                },
            ) {
                Text("清除密钥")
            }
        }
        if (savedMessage.isNotEmpty()) {
            Text(
                savedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (savedIsError) DangerRed else SuccessGreen,
            )
        }

        OutlinedButton(
            enabled = !testing,
            onClick = {
                val urlError = validateBaseUrl(baseUrl)
                val key = if (apiKeyInput.isNotBlank()) apiKeyInput else store.apiKey
                when {
                    urlError != null -> {
                        testIsError = true
                        testMessage = urlError
                    }
                    model.isBlank() -> {
                        testIsError = true
                        testMessage = "请先填写模型名。"
                    }
                    key.isBlank() -> {
                        testIsError = true
                        testMessage = "请先填写密钥。"
                    }
                    else -> {
                        testing = true
                        testMessage = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    LlmClient(baseUrl.trim().trimEnd('/'), model.trim(), key).checkConnection()
                                }
                            }
                            testing = false
                            testIsError = result.isFailure
                            testMessage = result.getOrElse { e -> e.message ?: "连接测试失败。" }
                        }
                    }
                }
            },
        ) {
            Text(if (testing) "正在测试…" else "测试连接")
        }
        if (testMessage.isNotEmpty()) {
            Text(
                testMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (testIsError) DangerRed else SuccessGreen,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("数据", style = MaterialTheme.typography.titleMedium)
        Text(
            "对话记录与备忘都存在本机应用私有目录，卸载即清除；清空对话不会删除备忘。",
            style = MaterialTheme.typography.bodySmall,
            color = MutedGrey,
        )
        OutlinedButton(onClick = { confirmClear = true }) {
            Text("清空对话记录", color = DangerRed)
        }
        Text(
            "手机助理 v$versionName · 应用级，无 root、无后台服务",
            style = MaterialTheme.typography.bodySmall,
            color = MutedGrey,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空对话记录？") },
            text = { Text("本机的聊天存档会被删除，备忘不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        confirmClear = false
                        savedIsError = false
                        savedMessage = "对话记录已清空。"
                    },
                ) {
                    Text("清空", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 与 network_security_config 保持一致：只允许 https，本机调试地址可用 http。 */
private fun validateBaseUrl(raw: String): String? {
    val value = raw.trim().trimEnd('/')
    if (value.isEmpty()) return "请填写服务地址。"
    val cleartext = value.startsWith("http://localhost") ||
        value.startsWith("http://127.0.0.1") ||
        value.startsWith("http://10.0.2.2")
    if (!value.startsWith("https://") && !cleartext) {
        return "服务地址必须以 https:// 开头；本机调试可用 http://localhost、http://127.0.0.1 或 http://10.0.2.2。"
    }
    return null
}

private fun intentTypeLabel(type: IntentType): String = when (type) {
    IntentType.OPEN_APP -> "打开应用"
    IntentType.SET_ALARM -> "设置闹钟"
    IntentType.CREATE_CALENDAR_EVENT -> "创建日程"
    IntentType.SET_TIMER -> "设置计时"
    IntentType.WEB_SEARCH -> "网络搜索"
    IntentType.SAVE_NOTE -> "保存备忘"
    IntentType.CHAT_ONLY -> "纯聊天"
}

/** 把校验后的参数翻译成中文行；内部计算字段（开始/结束毫秒）不展示。 */
private fun describeParams(intent: AssistantIntent): List<String> {
    val params = intent.params
    val lines = mutableListOf<String>()
    when (intent.type) {
        IntentType.SET_ALARM -> {
            val hour = params["hour"]?.toIntOrNull()
            val minute = params["minute"]?.toIntOrNull()
            if (hour != null && minute != null) {
                lines += "时间：${hour}:%02d".format(minute)
            }
            params["label"]?.let { lines += "备注：$it" }
        }
        IntentType.CREATE_CALENDAR_EVENT -> {
            params["title"]?.let { lines += "标题：$it" }
            val date = params["date"]
            val start = params["startTime"]
            if (date != null && start != null) lines += "开始：$date $start"
            params["durationMinutes"]?.let { lines += "时长：$it 分钟" }
            params["location"]?.let { lines += "地点：$it" }
            params["description"]?.let { lines += "说明：$it" }
        }
        IntentType.SET_TIMER -> {
            params["seconds"]?.let { lines += "时长：$it 秒" }
        }
        else -> {
            val labels = mapOf(
                "appName" to "应用",
                "query" to "搜索内容",
                "title" to "标题",
                "content" to "内容",
            )
            params.forEach { (key, value) ->
                labels[key]?.let { label -> lines += "$label：$value" }
            }
        }
    }
    return lines
}
