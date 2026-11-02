package cn.phoneassistant.desktop

import cn.phoneassistant.core.DeviceCert
import cn.phoneassistant.core.Jwk
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
        val error = DeviceCert.verify(cert, root, now)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val updated = config.copy(device_cert = cert)
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
