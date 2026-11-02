package cn.phoneassistant.core.runtime

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.Es256
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 受信审批方：手机可批 L3，电脑/手表最多 L2。公钥由手机签发。 */
data class TrustedApprover(
    val deviceId: String,
    val maxLevel: Int,
    val pubkeyJwk: JsonObject,
)

/**
 * 审批证明校验（approval.v1）。返回 null 表示通过，否则返回错误码。
 *
 * 顺序：结构 → 审批方 → 级别 → 哈希 → 有效期 → 重放 → 签名。
 * 哈希与级别都必须对得上；执行端自己验，不信云端转述。
 */
object ApprovalValidator {

    private val FIELDS = setOf("v", "envelope_hash", "decision", "level", "approver_device", "ts", "nonce", "sig")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")

    fun verify(
        approval: JsonElement,
        envelope: JsonObject,
        requiredLevel: Int,
        approvers: Map<String, TrustedApprover>,
        usedNonces: Set<String>,
        now: Instant,
        windowSeconds: Long,
    ): String? {
        if (approval !is JsonObject) return "APPROVAL_INVALID"
        if (approval.keys != FIELDS) return "APPROVAL_INVALID"

        val v = approval["v"] as? JsonPrimitive ?: return "APPROVAL_INVALID"
        if (v.isString || v.content != "1") return "APPROVAL_INVALID"

        val envelopeHash = str(approval["envelope_hash"]) ?: return "APPROVAL_INVALID"
        if (!HEX64.matches(envelopeHash)) return "APPROVAL_INVALID"

        if (str(approval["decision"]) != "approve") return "APPROVAL_INVALID"

        val deviceId = str(approval["approver_device"]) ?: return "APPROVAL_INVALID"
        val approver = approvers[deviceId] ?: return "APPROVAL_UNKNOWN_APPROVER"

        val levelElement = approval["level"] as? JsonPrimitive ?: return "APPROVAL_INVALID"
        if (levelElement.isString) return "APPROVAL_INVALID"
        val level = levelElement.content.toIntOrNull() ?: return "APPROVAL_INVALID"
        if (level !in 1..3) return "APPROVAL_INVALID"
        if (level < requiredLevel) return "APPROVAL_LEVEL"
        if (level > approver.maxLevel) return "APPROVAL_LEVEL"

        val tsText = str(approval["ts"]) ?: return "APPROVAL_INVALID"
        if (!TS.matches(tsText)) return "APPROVAL_INVALID"
        val ts = runCatching { Instant.parse(tsText) }.getOrNull() ?: return "APPROVAL_INVALID"
        if (kotlin.math.abs(Duration.between(ts, now).seconds) > windowSeconds) return "APPROVAL_EXPIRED"

        val nonce = str(approval["nonce"]) ?: return "APPROVAL_INVALID"
        if (!NONCE.matches(nonce)) return "APPROVAL_INVALID"
        if (nonce in usedNonces) return "APPROVAL_REPLAY"

        if (EnvelopeValidator.envelopeHash(envelope) != envelopeHash) return "APPROVAL_HASH"

        val sig = str(approval["sig"]) ?: return "APPROVAL_INVALID"
        val payload = JsonObject(approval.filterKeys { it != "sig" })
        if (!Es256.verify(payload, sig, approver.pubkeyJwk)) return "APPROVAL_SIGNATURE"
        return null
    }

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
