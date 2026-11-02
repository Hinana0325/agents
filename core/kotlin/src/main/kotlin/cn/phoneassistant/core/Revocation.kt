package cn.phoneassistant.core

import java.security.PrivateKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备证书吊销记录：手机（信任根）签名，绑定被吊销证书的哈希与设备 id。
 * 电脑验签后把该哈希列入本地吊销表，之后同一证书的 verify 会被拒绝。
 */
object Revocation {

    private val FIELDS = setOf("v", "device_id", "cert_hash", "revoked_at", "issued_by", "reason", "sig")
    private val REQUIRED = listOf("v", "device_id", "cert_hash", "revoked_at", "issued_by", "sig")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun create(
        deviceId: String,
        certHash: String,
        issuedBy: String,
        reason: String?,
        now: Instant,
        signingKey: PrivateKey,
    ): JsonObject {
        val claims = buildJsonObject {
            put("v", 1)
            put("device_id", deviceId)
            put("cert_hash", certHash)
            put("revoked_at", FORMAT.format(now))
            put("issued_by", issuedBy)
            if (!reason.isNullOrBlank()) put("reason", reason.take(200))
        }
        return JsonObject(claims.toMutableMap().apply { put("sig", JsonPrimitive(Es256.signRaw(claims, signingKey))) })
    }

    fun verify(revocation: JsonElement, trustRootJwk: JsonObject, now: Instant): String? {
        val obj = revocation as? JsonObject ?: return "REVOCATION_INVALID"
        for (key in obj.keys) if (key !in FIELDS) return "REVOCATION_INVALID"
        for (key in REQUIRED) if (key !in obj) return "REVOCATION_INVALID"

        val version = obj["v"] as? JsonPrimitive ?: return "REVOCATION_INVALID"
        if (version.isString || version.content != "1") return "REVOCATION_INVALID"

        val deviceId = str(obj["device_id"]) ?: return "REVOCATION_INVALID"
        if (deviceId.length !in 1..64) return "REVOCATION_INVALID"

        val certHash = str(obj["cert_hash"]) ?: return "REVOCATION_INVALID"
        if (!HEX64.matches(certHash)) return "REVOCATION_INVALID"

        val issuedBy = str(obj["issued_by"]) ?: return "REVOCATION_INVALID"
        if (issuedBy.length !in 1..64) return "REVOCATION_INVALID"

        val revokedAtText = str(obj["revoked_at"]) ?: return "REVOCATION_INVALID"
        if (!TS.matches(revokedAtText)) return "REVOCATION_INVALID"
        val revokedAt = runCatching { Instant.parse(revokedAtText) }.getOrNull() ?: return "REVOCATION_INVALID"
        if (revokedAt.isAfter(now.plusSeconds(120))) return "REVOCATION_NOT_YET_VALID"

        obj["reason"]?.let {
            if (!isString(it) || (it as JsonPrimitive).content.length > 200) return "REVOCATION_INVALID"
        }

        val sig = str(obj["sig"]) ?: return "REVOCATION_INVALID"
        val payload = JsonObject(obj.filterKeys { it != "sig" })
        if (!Es256.verify(payload, sig, trustRootJwk)) return "REVOCATION_SIGNATURE"
        return null
    }

    private fun isString(element: JsonElement?): Boolean =
        element is JsonPrimitive && element.isString

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
