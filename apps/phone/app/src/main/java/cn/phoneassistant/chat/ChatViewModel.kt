package cn.phoneassistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.phoneassistant.agent.PhoneAgent
import cn.phoneassistant.agent.RemoteApproval
import cn.phoneassistant.agent.describeEnvelope
import cn.phoneassistant.core.AppCatalog
import cn.phoneassistant.core.AssistantIntent
import cn.phoneassistant.core.AuditEntry
import cn.phoneassistant.core.AuditLog
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.core.Jwk
import cn.phoneassistant.core.LocalIntentParser
import cn.phoneassistant.core.ModelIntentParser
import cn.phoneassistant.core.Outbox
import cn.phoneassistant.core.PendingApproval
import cn.phoneassistant.core.PendingApprovalStore
import cn.phoneassistant.core.ProcessedStore
import cn.phoneassistant.core.RateWindowStore
import cn.phoneassistant.core.ValidationResult
import cn.phoneassistant.core.runtime.EnvelopeState
import cn.phoneassistant.data.ChatHistoryStore
import cn.phoneassistant.data.DeviceIdentity
import cn.phoneassistant.data.NoteStore
import cn.phoneassistant.data.SettingsStore
import cn.phoneassistant.intent.IntentExecutor
import cn.phoneassistant.llm.LlmClient
import cn.phoneassistant.model.ChatMessage
import cn.phoneassistant.model.PendingIntent
import cn.phoneassistant.transport.HttpHubClient
import cn.phoneassistant.transport.flushDue
import java.security.Signature
import cn.phoneassistant.work.OutboxScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** 设备身份（Keystore 密钥）首次打开配对页时才初始化。 */
    val identity: DeviceIdentity by lazy { DeviceIdentity(application) }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<PendingIntent?>(null)
    val pending: StateFlow<PendingIntent?> = _pending.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _audit = MutableStateFlow<List<AuditEntry>>(emptyList())
    val audit: StateFlow<List<AuditEntry>> = _audit.asStateFlow()
    private val _auditIntegrity = MutableStateFlow<String?>(null)
    val auditIntegrity: StateFlow<String?> = _auditIntegrity.asStateFlow()
    private val auditLog = AuditLog(File(application.filesDir, "audit.json"))
    private val processedStore = ProcessedStore(File(application.filesDir, "processed.json"))
    private val pendingApprovals = PendingApprovalStore(File(application.filesDir, "pending_approvals.json"))
    private val rateWindow = RateWindowStore(File(application.filesDir, "rate_window.json"))
    private val outbox = Outbox(File(application.filesDir, "outbox.json"))

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _remoteApproval = MutableStateFlow<RemoteApproval?>(null)
    val remoteApproval: StateFlow<RemoteApproval?> = _remoteApproval.asStateFlow()

    private sealed interface RemoteAnswer {
        data class Plain(val approved: Boolean) : RemoteAnswer
        data class Signed(val approval: JsonObject) : RemoteAnswer
    }

    @Volatile
    private var remoteDecision: CompletableDeferred<RemoteAnswer>? = null

    private var nextId = 1L

    init {
        // 在 IO 线程第一次触碰加密存储，界面之后读设置就不会在主线程做 Keystore I/O。
        viewModelScope.launch(Dispatchers.IO) { settings.isConfigured }
        viewModelScope.launch(Dispatchers.IO) { refreshAuditOnIo() }
        // 启动时补发上次没送出去的回执；仍有剩余就交给 WorkManager，进程退出后也会重试。
        viewModelScope.launch(Dispatchers.IO) {
            if (settings.isHubConfigured) {
                runCatching {
                    flushOutbox(HttpHubClient(settings.hubUrl, identity.deviceId, settings.hubToken))
                }
                if (outbox.size() > 0) OutboxScheduler.ensureScheduled(getApplication())
            }
        }
        // 上次进程在确认框弹出时被杀掉的话，启动后把待确认信封重新弹出来。
        resumePendingApprovals()
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
                    if (runLocalFallback(input, "还没配置大模型，先用本地规则处理。")) return@launch
                    appendAssistant(
                        settings.storageFailure
                            ?: "还没配好：服务地址、模型、密钥有一项是空的，" +
                            "请先在右上角“设置”里填好，我现在不会调用任何接口。" +
                            "（离线可用：计时、闹钟、备忘、打开应用、搜索）",
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
                if (runLocalFallback(input, "网络请求失败，已用本地规则处理。")) return@launch
                appendAssistant(e.message ?: "请求失败，请稍后再试。", isError = true)
            } finally {
                _thinking.value = false
            }
        }
    }

    /** 从编排服务拉取一次信封并处理；L2 会弹确认框。 */
    fun syncNow() {
        if (_syncing.value || remoteApproval.value != null) return
        val hubUrl = settings.hubUrl
        val token = settings.hubToken
        val pubkeyText = settings.hubPubkey
        val kid = settings.hubKid
        if (hubUrl.isBlank() || token.isBlank() || pubkeyText.isBlank()) {
            appendAssistant("还没配齐编排服务：Hub 地址、设备令牌、Hub 公钥都要填。", isError = true)
            return
        }
        val pubkey = runCatching { Json.parseToJsonElement(pubkeyText) as? JsonObject }.getOrNull()
        if (pubkey == null) {
            appendAssistant("Hub 公钥不是合法 JSON。", isError = true)
            return
        }
        Jwk.validate(pubkey)?.let {
            appendAssistant("Hub 公钥不合法：$it", isError = true)
            return
        }
        val appCatalog: Map<String, String> = runCatching {
            val text = settings.appCatalogJson
            if (text.isBlank()) emptyMap() else AppCatalog.parse(Json.parseToJsonElement(text)).getOrThrow()
        }.getOrElse {
            appendAssistant("应用目录不合法：${it.message ?: "请检查 JSON"}", isError = true)
            return
        }
        _syncing.value = true
        viewModelScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    val client = HttpHubClient(hubUrl, identity.deviceId, token)
                    val approvalPubkey = identity.approvalPublicJwk()
                    android.util.Log.i(
                        "PhoneAgent",
                        if (approvalPubkey == null) {
                            "approval key unavailable (no secure lock / enrolled biometric); L3 will be refused"
                        } else {
                            "approval key ready (biometric-gated)"
                        },
                    )
                    val agent = PhoneAgent(
                        getApplication(), identity, pubkey, kid, appCatalog, approvalPubkey, rateWindow,
                    )
                    val resent = flushOutbox(client)
                    val pull = client.pull()
                    val results = mutableListOf<String>()
                    if (resent > 0) results += "补发回执 $resent 条"
                    for (envelope in pull.envelopes) {
                        val id = (envelope["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                        // 跨重启去重：终态回执已落库时直接回执，不再执行。
                        val stored = processedStore.receipt(id)
                        if (stored != null) {
                            deliverReceipt(client, stored)
                            results += "$id：duplicate"
                            continue
                        }
                        val receipt = agent.submit(envelope)
                        val finalReceipt = if (agent.stateOf(id) == EnvelopeState.AWAITING_CONFIRM) {
                            val level = agent.requiredLevelOf(id) ?: 2
                            // 先落盘再弹框：进程在这个对话框上被杀掉时，重启还能恢复。
                            pendingApprovals.put(PendingApproval(id, level, envelope, System.currentTimeMillis()))
                            resolveDecision(agent, id, envelope, level).also { pendingApprovals.remove(id) }
                        } else {
                            receipt
                        }
                        deliverReceipt(client, finalReceipt)
                        val status = (finalReceipt["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                        if (status in TERMINAL_STATUSES) {
                            processedStore.save(id, finalReceipt)
                        }
                        val intentName = (envelope["intent"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "REMOTE"
                        auditLog.append(
                            AuditEntry(
                                id = UUID.randomUUID().toString(),
                                tsMillis = System.currentTimeMillis(),
                                intent = intentName,
                                summary = "来自编排服务",
                                outcome = if (status == "done") "success" else "failure",
                            ),
                        )
                        results += "$id：$status"
                    }
                    refreshAuditOnIo()
                    results
                }
                appendAssistant(
                    if (lines.isEmpty()) "编排服务没有新任务。" else "同步完成：\n" + lines.joinToString("\n"),
                )
            } catch (e: Exception) {
                appendAssistant("同步失败：${e.message ?: "未知错误"}", isError = true)
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 用户在界面上对远端任务点确认/取消（L2）。 */
    fun answerRemote(approved: Boolean) {
        remoteDecision?.complete(RemoteAnswer.Plain(approved))
    }

    private fun answerRemoteWithApproval(approval: JsonObject) {
        remoteDecision?.complete(RemoteAnswer.Signed(approval))
    }

    /** 生物识别成功后的调用：初始化签名器交给 BiometricPrompt 解锁。 */
    fun approvalSignature(): Signature? = identity.createApprovalSignature()

    /** 用生物识别解锁过的 Signature 生成 L3 证明并提交。 */
    fun submitRemoteApproval(level: Int, signature: Signature) {
        val current = _remoteApproval.value ?: return
        viewModelScope.launch {
            val proof = withContext(Dispatchers.IO) {
                runCatching {
                    identity.createApprovalWithSignature(current.envelope, level, signature)
                }.getOrNull()
            }
            if (proof == null) {
                appendAssistant("生物识别已通过，但审批签名失败。", isError = true)
            } else {
                answerRemoteWithApproval(proof)
            }
        }
    }

    /** 弹出确认并执行对应决策；L3 只接受生物识别签名，绝不吃普通确认。 */
    private suspend fun resolveDecision(
        agent: PhoneAgent,
        id: String,
        envelope: JsonObject,
        level: Int,
    ): JsonObject {
        val decision = askRemote(envelope, level)
        return when {
            decision is RemoteAnswer.Signed -> agent.approveWith(id, decision.approval)
            level >= 3 -> {
                appendAssistant("L3 审批必须通过生物识别签名，已拒绝。", isError = true)
                agent.reject(id)
            }
            (decision as RemoteAnswer.Plain).approved -> agent.approve(id, envelope, level)
            else -> agent.reject(id)
        }
    }

    private data class HubSetup(
        val client: HttpHubClient,
        val pubkey: JsonObject,
        val kid: String,
        val catalog: Map<String, String>,
    )

    /** 只在 IO 线程调用。 */
    private fun buildHubSetup(): Result<HubSetup> {
        if (!settings.isHubConfigured) {
            return Result.failure(IllegalStateException("Hub 配置已清除"))
        }
        val pubkey = runCatching {
            Json.parseToJsonElement(settings.hubPubkey) as? JsonObject
        }.getOrNull() ?: return Result.failure(IllegalArgumentException("Hub 公钥不是合法 JSON"))
        Jwk.validate(pubkey)?.let { return Result.failure(IllegalArgumentException("Hub 公钥不合法：$it")) }
        val catalog = runCatching {
            val text = settings.appCatalogJson
            if (text.isBlank()) emptyMap() else AppCatalog.parse(Json.parseToJsonElement(text)).getOrThrow()
        }.getOrElse { return Result.failure(IllegalArgumentException("应用目录不合法：${it.message ?: "请检查 JSON"}")) }
        return Result.success(
            HubSetup(
                client = HttpHubClient(settings.hubUrl, identity.deviceId, settings.hubToken),
                pubkey = pubkey,
                kid = settings.hubKid,
                catalog = catalog,
            ),
        )
    }

    @Volatile
    private var resumingPending = false

    /** 启动时恢复上次没审批完的信封；没有待确认或正在同步时什么都不做。 */
    fun resumePendingApprovals() {
        if (resumingPending || _syncing.value || remoteApproval.value != null) return
        resumingPending = true
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { pendingApprovals.list() }
                if (pending.isEmpty()) return@launch
                val setup = withContext(Dispatchers.IO) { buildHubSetup() }
                if (setup.isFailure) {
                    appendAssistant(
                        "有 ${pending.size} 条待确认任务，但无法恢复：${setup.exceptionOrNull()?.message ?: "配置不可用"}",
                        isError = true,
                    )
                    return@launch
                }
                val hub = setup.getOrThrow()
                _syncing.value = true
                val lines = withContext(Dispatchers.IO) {
                    val agent = PhoneAgent(
                        getApplication(), identity, hub.pubkey, hub.kid, hub.catalog,
                        identity.approvalPublicJwk(), rateWindow,
                    )
                    val results = mutableListOf<String>()
                    for (item in pending) {
                        val id = item.envelopeId
                        val stored = processedStore.receipt(id)
                        if (stored != null) {
                            deliverReceipt(hub.client, stored)
                            pendingApprovals.remove(id)
                            results += "$id：duplicate"
                            continue
                        }
                        val receipt = agent.submit(item.envelope)
                        val finalReceipt = if (agent.stateOf(id) == EnvelopeState.AWAITING_CONFIRM) {
                            val level = agent.requiredLevelOf(id) ?: item.level
                            resolveDecision(agent, id, item.envelope, level)
                        } else {
                            receipt
                        }
                        deliverReceipt(hub.client, finalReceipt)
                        val status = (finalReceipt["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                        if (status in TERMINAL_STATUSES) processedStore.save(id, finalReceipt)
                        // 回执落库/入队之后再删待确认，进程在这中间被杀也能靠 processed/outbox 补发。
                        pendingApprovals.remove(id)
                        auditLog.append(
                            AuditEntry(
                                id = UUID.randomUUID().toString(),
                                tsMillis = System.currentTimeMillis(),
                                intent = (item.envelope["intent"] as? JsonPrimitive)
                                    ?.contentOrNull?.uppercase() ?: "REMOTE",
                                summary = "恢复待确认任务",
                                outcome = if (status == "done") "success" else "failure",
                            ),
                        )
                        results += "$id：$status"
                    }
                    refreshAuditOnIo()
                    results
                }
                appendAssistant("恢复了 ${lines.size} 条待确认任务：\n" + lines.joinToString("\n"))
            } catch (e: Exception) {
                appendAssistant("恢复待确认任务失败：${e.message ?: "未知错误"}", isError = true)
            } finally {
                _syncing.value = false
                resumingPending = false
            }
        }
    }

    private suspend fun askRemote(envelope: JsonObject, level: Int): RemoteAnswer {
        val id = (envelope["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val intent = (envelope["intent"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val decision = CompletableDeferred<RemoteAnswer>()
        remoteDecision = decision
        _remoteApproval.value = RemoteApproval(id, intent, describeEnvelope(envelope), level, envelope)
        return try {
            decision.await()
        } finally {
            _remoteApproval.value = null
            remoteDecision = null
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
                is IntentExecutor.ExecutionResult.Success -> {
                    appendAssistant(result.message)
                    recordAudit(pendingIntent.intent, "success", result.artifact)
                }
                is IntentExecutor.ExecutionResult.Failure -> {
                    appendAssistant(result.message, isError = true)
                    recordAudit(pendingIntent.intent, "failure")
                }
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

    /** 本地规则兜底：认出来就按正常意图卡片处理，认不出返回 false。 */
    private suspend fun runLocalFallback(input: String, notice: String): Boolean {
        val intent = LocalIntentParser.parse(input) ?: return false
        appendAssistant(notice)
        handleValidIntent(intent)
        return true
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
                    is IntentExecutor.ExecutionResult.Success -> {
                        appendAssistant("已为你搜索“${intent.params.getValue("query")}”。")
                        recordAudit(intent, "success")
                    }
                    is IntentExecutor.ExecutionResult.Failure -> {
                        appendAssistant(result.message, isError = true)
                        recordAudit(intent, "failure")
                    }
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

    /** 从“动作历史”里撤销一条备忘；文件删除成功才记成功。 */
    fun undoNote(entry: AuditEntry) {
        val artifact = entry.artifact ?: return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { NoteStore.delete(getApplication(), artifact) }
            appendAssistant(
                if (deleted) "已删除备忘“$artifact”。" else "删除失败：备忘可能已经不存在。",
                isError = !deleted,
            )
            val undo = AuditEntry(
                id = UUID.randomUUID().toString(),
                tsMillis = System.currentTimeMillis(),
                intent = "UNDO_SAVE_NOTE",
                summary = "删除备忘 $artifact",
                outcome = if (deleted) "success" else "failure",
                artifact = if (deleted) artifact else null,
            )
            withContext(Dispatchers.IO) {
                auditLog.append(undo)
                refreshAuditOnIo()
            }
        }
    }

    /** 先落盘再发送：成功删除，失败留在 outbox 等下次同步/启动重试。 */
    private suspend fun deliverReceipt(client: HttpHubClient, receipt: JsonObject) {
        val id = (receipt["envelope_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val key = "receipt:$id"
        withContext(Dispatchers.IO) { outbox.enqueue(key, "receipt", receipt) }
        try {
            withContext(Dispatchers.IO) { client.postReceipt(receipt) }
            withContext(Dispatchers.IO) { outbox.markSent(key) }
        } catch (_: Exception) {
            // 记一次失败并按指数退避安排下次重试；进程可能被回收，交给 WorkManager 兜底
            val keep = withContext(Dispatchers.IO) { outbox.markFailed(key) }
            if (keep) OutboxScheduler.ensureScheduled(getApplication())
        }
    }

    private suspend fun flushOutbox(client: HttpHubClient): Int =
        flushDue(outbox) { client.postReceipt(it) }

    /** 只在 IO 线程调用。 */
    private fun refreshAuditOnIo() {
        _audit.value = auditLog.list()
        _auditIntegrity.value = auditLog.verify()
    }

    private fun recordAudit(intent: AssistantIntent, outcome: String, artifact: String? = null) {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            tsMillis = System.currentTimeMillis(),
            intent = intent.type.name,
            summary = summarize(intent),
            outcome = outcome,
            artifact = artifact,
        )
        viewModelScope.launch(Dispatchers.IO) {
            auditLog.append(entry)
            refreshAuditOnIo()
        }
    }

    private fun summarize(intent: AssistantIntent): String {
        val internal = setOf("beginMillis", "endMillis")
        return intent.params.entries
            .filter { it.key !in internal }
            .take(4)
            .joinToString("，") { "${it.key}=${it.value.take(40)}" }
            .ifBlank { intent.type.name }
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
        val TERMINAL_STATUSES = setOf("done", "failed", "rejected", "cancelled")
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(application) as T
        }
    }
}
