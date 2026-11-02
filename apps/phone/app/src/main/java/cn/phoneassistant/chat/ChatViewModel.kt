package cn.phoneassistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.phoneassistant.core.AssistantIntent
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.core.ModelIntentParser
import cn.phoneassistant.core.ValidationResult
import cn.phoneassistant.data.ChatHistoryStore
import cn.phoneassistant.data.SettingsStore
import cn.phoneassistant.intent.IntentExecutor
import cn.phoneassistant.llm.LlmClient
import cn.phoneassistant.model.ChatMessage
import cn.phoneassistant.model.PendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天状态机：界面只通过它收发消息，绝不直接调用网络或执行意图。
 *
 * 确认分级（写死在这里，不由模型决定）：
 * - CHAT_ONLY：只展示回复，不执行任何动作；
 * - WEB_SEARCH：低风险，直接执行并在对话中说明搜了什么；
 * - OPEN_APP、SET_ALARM、SET_TIMER、CREATE_CALENDAR_EVENT、SAVE_NOTE：
 *   必须先展示意图卡片，等用户点“确认”才执行，点“取消”则无任何副作用。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val settings = SettingsStore(application)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<PendingIntent?>(null)
    val pending: StateFlow<PendingIntent?> = _pending.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private var nextId = 1L

    init {
        // 在 IO 线程第一次触碰加密存储，界面之后读设置就不会在主线程做 Keystore I/O。
        viewModelScope.launch(Dispatchers.IO) { settings.isConfigured }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { ChatHistoryStore.load(getApplication()) }
            if (loaded.isEmpty()) {
                appendAssistant(INTRO)
            } else {
                nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1
                _messages.value = loaded
            }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || _thinking.value || _pending.value != null) return
        appendUser(input)
        _thinking.value = true
        viewModelScope.launch {
            try {
                val configured = withContext(Dispatchers.IO) { settings.isConfigured }
                if (!configured) {
                    appendAssistant(
                        settings.storageFailure
                            ?: "还没配好：服务地址、模型、密钥有一项是空的，" +
                            "请先在右上角“设置”里填好，我现在不会调用任何接口。",
                        isError = true,
                    )
                    return@launch
                }
                val raw = withContext(Dispatchers.IO) {
                    LlmClient(settings.baseUrl, settings.model, settings.apiKey).chat(input)
                }
                when (val result = ModelIntentParser.parse(raw)) {
                    is ValidationResult.Invalid -> appendAssistant(result.reason, isError = true)
                    is ValidationResult.Valid -> handleValidIntent(result.intent)
                }
            } catch (e: Exception) {
                appendAssistant(e.message ?: "请求失败，请稍后再试。", isError = true)
            } finally {
                _thinking.value = false
            }
        }
    }

    fun confirmPending() {
        val pendingIntent = _pending.value ?: return
        if (pendingIntent.intent.type == IntentType.OPEN_APP &&
            pendingIntent.selectedPackage.isNullOrBlank()
        ) {
            appendAssistant("请先在卡片里选一个应用，再点确认。", isError = true)
            return
        }
        _pending.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                IntentExecutor.execute(
                    context = getApplication(),
                    intent = pendingIntent.intent,
                    confirmedPackage = pendingIntent.selectedPackage,
                )
            }
            when (result) {
                is IntentExecutor.ExecutionResult.Success -> appendAssistant(result.message)
                is IntentExecutor.ExecutionResult.Failure -> appendAssistant(result.message, isError = true)
            }
        }
    }

    fun cancelPending() {
        if (_pending.value == null) return
        _pending.value = null
        appendAssistant("已取消，没有执行任何操作。")
    }

    fun selectAppCandidate(packageName: String) {
        _pending.update { current ->
            current?.copy(selectedPackage = packageName)
        }
    }

    private suspend fun handleValidIntent(intent: AssistantIntent) {
        intent.reply?.takeIf { it.isNotBlank() }?.let { appendAssistant(it) }
        when (intent.type) {
            IntentType.CHAT_ONLY -> {
                // 回复已在上面展示，无动作。
            }
            IntentType.WEB_SEARCH -> {
                val result = withContext(Dispatchers.IO) {
                    IntentExecutor.execute(getApplication(), intent)
                }
                when (result) {
                    is IntentExecutor.ExecutionResult.Success ->
                        appendAssistant("已为你搜索“${intent.params.getValue("query")}”。")
                    is IntentExecutor.ExecutionResult.Failure ->
                        appendAssistant(result.message, isError = true)
                }
            }
            IntentType.OPEN_APP -> {
                // 遍历全部可启动应用并 loadLabel，放到 IO 线程做，避免界面卡住。
                val candidates = withContext(Dispatchers.IO) {
                    IntentExecutor.resolveAppCandidates(
                        getApplication(),
                        intent.params.getValue("appName"),
                    )
                }
                if (candidates.isEmpty()) {
                    appendAssistant(
                        "没有找到叫“${intent.params.getValue("appName")}”的应用，" +
                            "请换一个更准确的应用名再试。",
                        isError = true,
                    )
                } else {
                    _pending.value = PendingIntent(
                        intent = intent,
                        appCandidates = candidates,
                        selectedPackage = candidates.singleOrNull()?.packageName,
                    )
                }
            }
            IntentType.SET_ALARM,
            IntentType.SET_TIMER,
            IntentType.CREATE_CALENDAR_EVENT,
            IntentType.SAVE_NOTE -> {
                _pending.value = PendingIntent(intent = intent)
            }
        }
    }

    private fun appendUser(text: String) {
        _messages.update { it + ChatMessage(id = nextId++, isUser = true, text = text) }
        persist()
    }

    private fun appendAssistant(text: String, isError: Boolean = false) {
        if (text.isBlank()) return
        _messages.update {
            it + ChatMessage(id = nextId++, isUser = false, text = text, isError = isError)
        }
        persist()
    }

    private fun persist() {
        val snapshot = _messages.value
        viewModelScope.launch(Dispatchers.IO) { ChatHistoryStore.save(getApplication(), snapshot) }
    }

    /** 清空对话记录（含本地存档），保留开场白。 */
    fun clearHistory() {
        _pending.value = null
        nextId = 1
        _messages.value = emptyList()
        appendAssistant(INTRO)
    }

    private companion object {
        const val INTRO =
            "你好，我是手机助理。先在右上角“设置”里填好服务地址、模型和密钥，" +
                "然后直接说话就行：我会先把要做的事列出来，需要确认的动作等你点确认才执行。"
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(application) as T
        }
    }
}
