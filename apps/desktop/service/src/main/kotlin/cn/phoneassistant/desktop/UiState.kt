package cn.phoneassistant.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/** 界面与服务线程之间的共享状态。UI 用 Compose 收集，服务线程只写 StateFlow。 */
class UiState {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _prompt = MutableStateFlow<ApprovalPrompt?>(null)
    val prompt: StateFlow<ApprovalPrompt?> = _prompt

    @Volatile
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    var trayNotifier: Notifier? = null

    val notifier = Notifier { title, body ->
        log("[通知] $title：$body")
        trayNotifier?.notify(title, body)
    }

    fun log(line: String) {
        _logs.update { (it + line).takeLast(300) }
    }

    /** 服务线程阻塞等待界面点确认；没有界面时不会被调用。 */
    fun ask(prompt: ApprovalPrompt): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _prompt.value = prompt
        return runBlocking { decision.await() }
    }

    fun answer(approved: Boolean) {
        pendingDecision?.complete(approved)
        pendingDecision = null
        _prompt.value = null
    }
}
