package cn.phoneassistant.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** P-256 公钥 JWK 的公共校验，电脑端与手机端共用。 */
object Jwk {

    private val B64_32 = Regex("^[A-Za-z0-9_-]{43}$")

    fun validate(jwk: JsonObject): String? {
        if (jwk.keys != setOf("kty", "crv", "x", "y")) return "公钥必须是 {kty, crv, x, y}"
        if ((jwk["kty"] as? JsonPrimitive)?.contentOrNull != "EC") return "只支持 EC 公钥"
        if ((jwk["crv"] as? JsonPrimitive)?.contentOrNull != "P-256") return "只支持 P-256"
        if ((jwk["x"] as? JsonPrimitive)?.contentOrNull?.let { B64_32.matches(it) } != true) {
            return "x 不是合法的 P-256 坐标"
        }
        if ((jwk["y"] as? JsonPrimitive)?.contentOrNull?.let { B64_32.matches(it) } != true) {
            return "y 不是合法的 P-256 坐标"
        }
        return null
    }
}
