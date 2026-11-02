package cn.phoneassistant.desktop

import cn.phoneassistant.core.DeviceCert
import cn.phoneassistant.core.Jwk
import cn.phoneassistant.core.Revocation
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 配对（v1 手工交换 JSON）：
 * 电脑输出请求 → 手机签发设备证书 → 电脑记录信任根并验证书。
 * 证书格式与校验规则见 protocol/spec/pairing.md。
 */
object Pairing {

    fun request(config: DesktopConfigFile, identity: DesktopIdentity): JsonObject = buildJsonObject {
        put("v", 1)
        put("device_id", config.device_id)
        put("role", config.role)
        put("max_level", 2)
        put("pubkey", identity.publicJwk())
    }

    fun validateJwk(jwk: JsonObject): String? = Jwk.validate(jwk)

    /** 信任手机公钥；换信任根时清掉旧证书。 */
    fun trust(paths: DesktopPaths, config: DesktopConfigFile, phoneKey: JsonObject): Result<DesktopConfigFile> {
        validateJwk(phoneKey)?.let { return Result.failure(IllegalArgumentException(it)) }
        val updated = config.copy(phone_pubkey = phoneKey, device_cert = null)
        DesktopConfig.save(paths, updated)
        return Result.success(updated)
    }

    /** 验证手机签发的设备证书并保存。 */
    fun verifyCertificate(
        paths: DesktopPaths,
        config: DesktopConfigFile,
        cert: JsonObject,
        now: Instant = Instant.now(),
    ): Result<DesktopConfigFile> {
        val root = config.phone_pubkey
            ?: return Result.failure(IllegalStateException("还没有信任根：先运行 trust-phone <手机公钥.json>"))
        val error = DeviceCert.verify(cert, root, now, config.revoked_certs.toSet())
        if (error != null) return Result.failure(IllegalArgumentException(error))
        // 轮换：换上新证书时把旧证书哈希列入本地吊销表，避免旧证书继续可用。
        val newHash = DeviceCert.hash(cert)
        val oldHash = config.device_cert?.let { DeviceCert.hash(it) }
        val revoked = if (oldHash != null && oldHash != newHash) {
            (config.revoked_certs + oldHash).distinct()
        } else {
            config.revoked_certs
        }
        val updated = config.copy(device_cert = cert, revoked_certs = revoked)
        DesktopConfig.save(paths, updated)
        return Result.success(updated)
    }

    /** 供 status 显示的人话：有效 / 即将过期 / 已过期。 */
    fun certificateState(cert: JsonObject?, now: Instant = Instant.now()): String {
        if (cert == null) return "无证书"
        val expiresText = (cert["expires_at"] as? JsonPrimitive)?.contentOrNull
            ?: return "证书格式不完整"
        val expiresAt = runCatching { Instant.parse(expiresText) }.getOrNull()
            ?: return "证书格式不完整"
        val remaining = java.time.Duration.between(now, expiresAt)
        return when {
            remaining.isNegative -> "已过期（$expiresText）"
            remaining.toDays() <= 30 -> "即将过期（剩余 ${remaining.toDays()} 天，$expiresText）"
            else -> "有效（剩余 ${remaining.toDays()} 天，$expiresText）"
        }
    }

    /** 验证手机签发的吊销记录；把证书哈希列入本地吊销表，并清掉匹配的本机证书。 */
    fun revoke(
        paths: DesktopPaths,
        config: DesktopConfigFile,
        revocation: JsonObject,
        now: Instant = Instant.now(),
    ): Result<DesktopConfigFile> {
        val root = config.phone_pubkey
            ?: return Result.failure(IllegalStateException("还没有信任根：先运行 trust-phone <手机公钥.json>"))
        val error = Revocation.verify(revocation, root, now)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val hash = (revocation["cert_hash"] as? JsonPrimitive)?.contentOrNull
            ?: return Result.failure(IllegalArgumentException("REVOCATION_INVALID"))
        val updated = config.copy(
            revoked_certs = (config.revoked_certs + hash).distinct(),
            device_cert = config.device_cert?.takeIf { DeviceCert.hash(it) != hash },
        )
        DesktopConfig.save(paths, updated)
        return Result.success(updated)
    }

    fun describeCertificate(cert: JsonObject?): String {
        if (cert == null) return "无"
        val device = (cert["device_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val role = (cert["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val level = (cert["max_level"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val expires = (cert["expires_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return "$device（role=$role，max_level=$level，到期 $expires）"
    }
}
