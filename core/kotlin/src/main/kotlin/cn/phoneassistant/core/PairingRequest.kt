package cn.phoneassistant.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 电脑/手表发给手机的配对请求（`protocol/spec/pairing.md`）。 */
data class PairingRequest(
    val deviceId: String,
    val role: String,
    val maxLevel: Int,
    val pubkey: JsonObject,
)

object PairingRequests {

    private val ROLES = setOf("pc", "watch")

    fun parse(raw: String): Result<PairingRequest> {
        val element = try {
            Json.parseToJsonElement(raw.trim())
        } catch (e: Exception) {
            return failure("请求不是合法 JSON：${e.message}")
        }
        val obj = element as? JsonObject ?: return failure("请求必须是 JSON 对象")

        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: return failure("缺少 v 字段")
        if (version != 1) return failure("不支持的配对请求版本：$version")

        val deviceId = (obj["device_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure("缺少 device_id")
        if (deviceId.length !in 1..64) return failure("device_id 长度不合法")

        val role = (obj["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure("缺少 role")
        if (role !in ROLES) return failure("只接受电脑或手表（收到 $role）")

        val maxLevel = (obj["max_level"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: return failure("缺少 max_level")
        if (maxLevel !in 1..2) return failure("电脑/手表的最高确认级只能是 1 或 2")

        val pubkey = obj["pubkey"] as? JsonObject ?: return failure("缺少 pubkey")
        Jwk.validate(pubkey)?.let { return failure(it) }

        return Result.success(PairingRequest(deviceId, role, maxLevel, pubkey))
    }

    private fun failure(message: String): Result<PairingRequest> =
        Result.failure(IllegalArgumentException(message))
}
