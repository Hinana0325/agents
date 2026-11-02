package cn.phoneassistant.core

import kotlinx.serialization.json.JsonObject

/**
 * 校验上下文。所有会改变结论的运行时输入都显式传入，便于向量逐条复现。
 *
 * - [now]：设备当前时间（RFC 3339 UTC 秒级），null 表示不判过期。
 * - [seenIds]：已处理过的信封 id（幂等去重）。
 * - [pubkeyJwk]：受信公钥 JWK；null 表示只做结构/策略校验，不验签。
 * - [appCatalog]：端上应用目录（app_id → {class: ...}），发送方无法自证 app_class。
 * - [urlAllowlist]：端上允许的 https host 清单。
 */
data class ValidationContext(
    val now: String? = null,
    val seenIds: Set<String> = emptySet(),
    val pubkeyJwk: JsonObject? = null,
    val clockOffsetSeconds: Int = 0,
    val appCatalog: JsonObject? = null,
    val urlAllowlist: List<String> = emptyList(),
    val recentAlarms24h: Int = 0,
    val deviceRole: String? = null,
)

data class EnvelopeValidationResult(
    val ok: Boolean,
    val code: String? = null,
    val level: Int? = null,
    val executable: Boolean = true,
) {
    companion object {
        fun fail(code: String) = EnvelopeValidationResult(ok = false, code = code)
        fun pass(level: Int, executable: Boolean) = EnvelopeValidationResult(ok = true, level = level, executable = executable)
    }
}
