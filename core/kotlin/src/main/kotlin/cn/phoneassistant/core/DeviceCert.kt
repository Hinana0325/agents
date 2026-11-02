package cn.phoneassistant.core

import java.security.PrivateKey
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备证书：手机作为信任根，给电脑/手表签发 {pubkey, role, max_level, 有效期}。
 * 校验规则见 protocol/spec/pairing.md；返回 null 表示通过，否则是错误码。
 */
object DeviceCert {

    private val FIELDS = setOf("v", "device_id", "role", "max_level", "pubkey", "issued_by", "issued_at", "expires_at", "sig")
    private val PUBKEY_FIELDS = setOf("kty", "crv", "x", "y")
    private val ROLES = setOf("phone", "watch", "pc")
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val B64_32 = Regex("^[A-Za-z0-9_-]{43}$")

    fun create(
        deviceId: String,
        role: String,
        maxLevel: Int,
        pubkeyJwk: JsonObject,
        issuedBy: String,
        issuedAt: String,
        expiresAt: String,
        signingKey: PrivateKey,
    ): JsonObject {
        val claims = buildJsonObject {
            put("v", 1)
            put("device_id", deviceId)
            put("role", role)
            put("max_level", maxLevel)
            put("pubkey", pubkeyJwk)
            put("issued_by", issuedBy)
            put("issued_at", issuedAt)
            put("expires_at", expiresAt)
        }
        return JsonObject(claims.toMutableMap().apply { put("sig", JsonPrimitive(Es256.signRaw(claims, signingKey))) })
    }

    fun verify(cert: JsonElement, trustRootJwk: JsonObject, now: Instant): String? {
        val obj = cert as? JsonObject ?: return "CERT_INVALID"
        if (obj.keys != FIELDS) return "CERT_INVALID"

        val version = obj["v"] as? JsonPrimitive ?: return "CERT_INVALID"
        if (version.isString || version.content != "1") return "CERT_INVALID"

        val deviceId = str(obj["device_id"]) ?: return "CERT_INVALID"
        if (deviceId.length !in 1..64) return "CERT_INVALID"

        val role = str(obj["role"]) ?: return "CERT_INVALID"
        if (role !in ROLES) return "CERT_INVALID"

        val levelElement = obj["max_level"] as? JsonPrimitive ?: return "CERT_INVALID"
        if (levelElement.isString) return "CERT_INVALID"
        val level = levelElement.content.toIntOrNull() ?: return "CERT_INVALID"
        if (level !in 1..3) return "CERT_INVALID"
        if (role != "phone" && level > 2) return "CERT_LEVEL"

        val pubkey = obj["pubkey"] as? JsonObject ?: return "CERT_INVALID"
        if (pubkey.keys != PUBKEY_FIELDS) return "CERT_INVALID"
        if (str(pubkey["kty"]) != "EC" || str(pubkey["crv"]) != "P-256") return "CERT_INVALID"
        if (str(pubkey["x"])?.let { B64_32.matches(it) } != true) return "CERT_INVALID"
        if (str(pubkey["y"])?.let { B64_32.matches(it) } != true) return "CERT_INVALID"

        val issuedBy = str(obj["issued_by"]) ?: return "CERT_INVALID"
        if (issuedBy.length !in 1..64) return "CERT_INVALID"

        val issuedAtText = str(obj["issued_at"]) ?: return "CERT_INVALID"
        val expiresAtText = str(obj["expires_at"]) ?: return "CERT_INVALID"
        if (!TS.matches(issuedAtText) || !TS.matches(expiresAtText)) return "CERT_INVALID"
        val issuedAt = runCatching { Instant.parse(issuedAtText) }.getOrNull() ?: return "CERT_INVALID"
        val expiresAt = runCatching { Instant.parse(expiresAtText) }.getOrNull() ?: return "CERT_INVALID"
        if (!expiresAt.isAfter(issuedAt)) return "CERT_INVALID"
        if (now.isAfter(expiresAt)) return "CERT_EXPIRED"
        if (issuedAt.isAfter(now.plusSeconds(120))) return "CERT_NOT_YET_VALID"

        if (str(obj["sig"]) == null) return "CERT_INVALID"
        val payload = JsonObject(obj.filterKeys { it != "sig" })
        if (!Es256.verify(payload, str(obj["sig"])!!, trustRootJwk)) return "CERT_SIGNATURE"
        return null
    }

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
