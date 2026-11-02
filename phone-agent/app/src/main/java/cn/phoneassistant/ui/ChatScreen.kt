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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.phoneassistant.chat.ChatViewModel
import cn.phoneassistant.model.AssistantIntent
import cn.phoneassistant.model.ChatMessage
import cn.phoneassistant.model.IntentType
import cn.phoneassistant.model.PendingIntent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机助理") },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "返回对话" else "设置")
                    }
                },
            )
        },
    ) { padding ->
        if (showSettings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { showSettings = false },
                modifier = Modifier.padding(padding),
            )
        } else {
            ChatContent(
                viewModel = viewModel,
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
private fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val store = viewModel.settings
    var baseUrl by remember { mutableStateOf(store.baseUrl) }
    var model by remember { mutableStateOf(store.model) }
    var apiKeyInput by remember { mutableStateOf("") }
    var savedMessage by remember { mutableStateOf("") }
    var savedIsError by remember { mutableStateOf(false) }
    var storageState by remember { mutableStateOf(store.hasApiKey to store.isConfigured) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
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
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) {
            Text("返回对话")
        }
    }
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
