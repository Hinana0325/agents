package cn.phoneassistant.agent

import android.content.Context
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.core.ProtocolMapping
import cn.phoneassistant.core.runtime.DeviceRuntime
import cn.phoneassistant.core.runtime.EnvelopeState
import cn.phoneassistant.core.runtime.ExecutionOutcome
import cn.phoneassistant.core.runtime.TrustedApprover
import cn.phoneassistant.data.DeviceIdentity
import cn.phoneassistant.intent.IntentExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 手机端的协议运行时装配：信封 → core.DeviceRuntime（验签/策略/去重）→ 现有 Android 执行器。
 * L2 用 Keystore 身份密钥签 approval.v1；L3 必须用生物识别保护的审批密钥（单独受信条目）。
 * 回执由调用方回传 hub。
 */
class PhoneAgent(
    private val context: Context,
    private val identity: DeviceIdentity,
    hubPubkey: JsonObject,
    hubKid: String,
    private val appCatalog: Map<String, String> = emptyMap(),
    approvalPubkey: JsonObject? = null,
) {

    private val runtime = DeviceRuntime(
        deviceId = identity.deviceId,
        deviceRole = "phone",
        executor = { intent, params -> execute(intent, params) },
        hubPublicKeys = mapOf(hubKid to hubPubkey),
        approvers = buildMap {
            // 身份密钥只能批 L2；L3 必须走单独的、生物识别保护的审批密钥。
            put(identity.deviceId, TrustedApprover(identity.deviceId, 2, identity.publicJwk()))
            if (approvalPubkey != null) {
                val approvalId = "${identity.deviceId}:approval"
                put(approvalId, TrustedApprover(approvalId, 3, approvalPubkey))
            }
        },
    )

    fun submit(envelope: JsonObject): JsonObject = runtime.submit(envelope)

    fun stateOf(id: String): EnvelopeState? = runtime.stateOf(id)

    fun requiredLevelOf(id: String): Int? = runtime.requiredLevelOf(id)

    fun approve(id: String, envelope: JsonObject, level: Int): JsonObject =
        runtime.approve(id, identity.createApproval(envelope, level))

    /** 传入生物识别解锁后生成的 L3 审批证明。 */
    fun approveWith(id: String, approval: JsonObject): JsonObject = runtime.approve(id, approval)

    fun reject(id: String): JsonObject = runtime.reject(id)

    fun receiptOf(id: String): JsonObject? = runtime.receiptOf(id)

    private fun execute(intent: String, params: JsonObject): ExecutionOutcome {
        val mapped = ProtocolMapping.toAssistantIntent(intent, params).getOrElse {
            return ExecutionOutcome(false, it.message ?: "协议参数映射失败")
        }
        // 云端只能给逻辑 app_id；包名必须从本机目录解析，缺失就拒绝，绝不把 app_id 当包名。
        val confirmedPackage = if (mapped.type == IntentType.OPEN_APP) {
            val appId = (params["app_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: return ExecutionOutcome(false, "缺少 app_id")
            appCatalog[appId]
                ?: return ExecutionOutcome(false, "应用目录里没有 $appId（请在设置里配置 app_id → 包名）")
        } else {
            null
        }
        val result = try {
            IntentExecutor.execute(
                context = context,
                intent = mapped,
                confirmedPackage = confirmedPackage,
            )
        } catch (e: Exception) {
            return ExecutionOutcome(false, e.message)
        }
        return when (result) {
            is IntentExecutor.ExecutionResult.Success -> ExecutionOutcome(true, result.message)
            is IntentExecutor.ExecutionResult.Failure -> ExecutionOutcome(false, result.message)
        }
    }
}

/** 待用户在手机上确认的远端任务。 */
data class RemoteApproval(
    val envelopeId: String,
    val intent: String,
    val summary: String,
    val level: Int,
    val envelope: JsonObject,
)

fun describeEnvelope(envelope: JsonObject): String {
    val intent = (envelope["intent"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val params = envelope["params"]?.toString().orEmpty()
    return "$intent\n${params.take(400)}"
}
