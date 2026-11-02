package cn.phoneassistant.core

import java.security.PrivateKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 生成 approval.v1 审批证明：绑定信封哈希、级别、审批设备、时间与一次性 nonce。 */
object ApprovalProof {

    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun create(
        envelope: JsonObject,
        level: Int,
        approverDevice: String,
        nonce: String,
        now: Instant,
        signingKey: PrivateKey,
    ): JsonObject {
        val payload = buildJsonObject {
            put("v", 1)
            put("envelope_hash", EnvelopeValidator.envelopeHash(envelope))
            put("decision", "approve")
            put("level", level)
            put("approver_device", approverDevice)
            put("ts", FORMAT.format(now))
            put("nonce", nonce)
        }
        return JsonObject(payload.toMutableMap().apply { put("sig", JsonPrimitive(Es256.signRaw(payload, signingKey))) })
    }
}
