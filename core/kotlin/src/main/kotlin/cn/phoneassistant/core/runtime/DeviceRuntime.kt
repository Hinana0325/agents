package cn.phoneassistant.core.runtime

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.ValidationContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 信封在设备上的生命周期状态。非法转移一律丢弃，不写回执。 */
enum class EnvelopeState {
    CREATED, QUEUED, DELIVERED, AWAITING_CONFIRM, EXECUTING, DONE, FAILED, EXPIRED, REJECTED, CANCELLED;

    val terminal: Boolean
        get() = this in setOf(DONE, FAILED, EXPIRED, REJECTED, CANCELLED)
}

data class ExecutionOutcome(val success: Boolean, val detail: String? = null)

fun interface IntentExecutor {
    fun execute(intent: String, params: JsonObject): ExecutionOutcome
}

/**
 * 设备端运行时：收信封 → 验签/校验 → 去重 → （L1 执行 | L2+ 等审批）→ 回执。
 *
 * 纯 JVM，不依赖 Android；手机端只提供 [IntentExecutor] 的实现（走公开 Intent）。
 * 幂等：同一个信封 id 重复投递只执行一次，返回同一份回执。
 * 状态机见 protocol/spec/state-machine.md。
 */
class DeviceRuntime(
    private val deviceId: String,
    private val deviceRole: String,
    private val executor: IntentExecutor,
    private val hubPublicKeys: Map<String, JsonObject> = emptyMap(),
    private val approvers: Map<String, TrustedApprover> = emptyMap(),
    private val appCatalog: JsonObject = JsonObject(emptyMap()),
    private val urlAllowlist: List<String> = emptyList(),
    private val recentAlarms24h: Int = 0,
    private val clock: () -> Instant = { Instant.now() },
    private val approvalWindowSeconds: Long = 120,
) {

    private data class Record(
        val envelope: JsonObject,
        var state: EnvelopeState,
        var receipt: JsonObject,
        val level: Int,
    )

    private val records = LinkedHashMap<String, Record>()
    private val usedNonces = mutableSetOf<String>()

    fun stateOf(id: String): EnvelopeState? = records[id]?.state

    fun receiptOf(id: String): JsonObject? = records[id]?.receipt

    /** 投递一条信封（原始 JSON）。返回回执；重复投递返回首次的回执。 */
    fun submit(raw: JsonElement): JsonObject {
        val envelope = raw as? JsonObject
        val id = str(envelope?.get("id"))
        if (id != null && records.containsKey(id)) return records.getValue(id).receipt

        val kid = str((envelope?.get("from") as? JsonObject)?.get("kid"))
        val ctx = ValidationContext(
            now = timestamp(),
            seenIds = records.keys.toSet(),
            pubkeyJwk = kid?.let { hubPublicKeys[it] },
            appCatalog = appCatalog,
            urlAllowlist = urlAllowlist,
            recentAlarms24h = recentAlarms24h,
            deviceRole = deviceRole,
        )
        val result = EnvelopeValidator.validate(raw, ctx)
        if (!result.ok || envelope == null) {
            return receipt(id.orEmpty(), "rejected", result.code ?: "BAD_TYPE")
        }

        if (!result.executable) {
            return store(id!!, envelope, result.level!!, EnvelopeState.REJECTED, "rejected", "VOICE_NOT_EXECUTABLE")
        }
        return if (result.level!! >= 2) {
            store(id!!, envelope, result.level, EnvelopeState.AWAITING_CONFIRM, "awaiting_confirm")
        } else {
            store(id!!, envelope, result.level, EnvelopeState.EXECUTING, "executing")
            runAndFinish(id)
        }
    }

    /** 用户在当前受信界面批准。审批证明必须在窗口内、绑定信封哈希、级别足够。 */
    fun approve(id: String, approval: JsonElement): JsonObject {
        val record = records[id] ?: return receipt(id, "rejected", "UNKNOWN_ENVELOPE")
        if (record.state != EnvelopeState.AWAITING_CONFIRM) {
            return receipt(id, statusText(record.state), "NOT_AWAITING_CONFIRM")
        }
        val error = ApprovalValidator.verify(
            approval = approval,
            envelope = record.envelope,
            requiredLevel = record.level,
            approvers = approvers,
            usedNonces = usedNonces,
            now = clock(),
            windowSeconds = approvalWindowSeconds,
        )
        if (error != null) {
            // 审批失败不改变任务状态，任务继续等待合法审批。
            return receipt(id, "awaiting_confirm", error)
        }
        usedNonces += str((approval as JsonObject)["nonce"])!!
        runAndFinish(id)
        return records.getValue(id).receipt
    }

    /** 用户在界面上拒绝。 */
    fun reject(id: String): JsonObject {
        val record = records[id] ?: return receipt(id, "rejected", "UNKNOWN_ENVELOPE")
        if (record.state != EnvelopeState.AWAITING_CONFIRM) {
            return receipt(id, statusText(record.state), "NOT_AWAITING_CONFIRM")
        }
        record.state = EnvelopeState.REJECTED
        record.receipt = receipt(id, "rejected", "USER_REJECTED")
        return record.receipt
    }

    /** 把已过期、未到终态的信封标成 expired。由调用方定期触发。 */
    fun sweepExpired(): List<String> {
        val now = clock()
        val expired = mutableListOf<String>()
        for ((id, record) in records) {
            if (record.state.terminal) continue
            val ts = runCatching { Instant.parse(str(record.envelope["ts"])!!) }.getOrNull() ?: continue
            val ttl = (record.envelope["ttl_s"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
            if (now.isAfter(ts.plusSeconds(ttl))) {
                record.state = EnvelopeState.EXPIRED
                record.receipt = receipt(id, "expired")
                expired += id
            }
        }
        return expired
    }

    private fun store(
        id: String,
        envelope: JsonObject,
        level: Int,
        state: EnvelopeState,
        status: String,
        errorCode: String? = null,
    ): JsonObject {
        val receipt = receipt(id, status, errorCode)
        records[id] = Record(envelope, state, receipt, level)
        return receipt
    }

    private fun runAndFinish(id: String): JsonObject {
        val record = records.getValue(id)
        record.state = EnvelopeState.EXECUTING
        val intent = str(record.envelope["intent"]).orEmpty()
        val params = record.envelope["params"] as JsonObject
        val outcome = try {
            executor.execute(intent, params)
        } catch (e: Exception) {
            ExecutionOutcome(false, e.message)
        }
        record.state = if (outcome.success) EnvelopeState.DONE else EnvelopeState.FAILED
        record.receipt = if (outcome.success) {
            receipt(id, "done")
        } else {
            receipt(id, "failed", "EXECUTOR_FAILED", outcome.detail)
        }
        return record.receipt
    }

    private fun receipt(id: String, status: String, errorCode: String? = null, detail: String? = null): JsonObject =
        buildJsonObject {
            put("v", 1)
            put("envelope_id", id)
            put("device_id", deviceId)
            put("status", status)
            put("ts", timestamp())
            if (errorCode != null) {
                putJsonObject("error") {
                    put("code", errorCode)
                    if (detail != null) put("detail", detail.take(500))
                }
            }
        }

    private fun timestamp(): String = FORMAT.format(clock())

    private fun statusText(state: EnvelopeState): String = when (state) {
        EnvelopeState.AWAITING_CONFIRM -> "awaiting_confirm"
        EnvelopeState.EXECUTING -> "executing"
        EnvelopeState.DONE -> "done"
        EnvelopeState.FAILED -> "failed"
        EnvelopeState.EXPIRED -> "expired"
        EnvelopeState.REJECTED -> "rejected"
        EnvelopeState.CANCELLED -> "cancelled"
        else -> "accepted"
    }

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
