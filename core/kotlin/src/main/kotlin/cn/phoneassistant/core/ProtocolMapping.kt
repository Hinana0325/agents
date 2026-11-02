package cn.phoneassistant.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 协议意图（envelope.intent + params）→ 手机现有的 [AssistantIntent]。
 *
 * 参数已经在 EnvelopeValidator 里按 schema 校验过；这里只做字段改名与展示字段换算。
 * open_app 的 app_id 目前按包名处理（见 REPORT 的已知限制）。
 */
object ProtocolMapping {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun toAssistantIntent(
        intent: String,
        params: JsonObject,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result<AssistantIntent> {
        return try {
            when (intent) {
                "chat" -> Result.success(
                    AssistantIntent(IntentType.CHAT_ONLY, emptyMap(), str(params, "text")),
                )
                "web_search" -> Result.success(
                    AssistantIntent(IntentType.WEB_SEARCH, mapOf("query" to requireStr(params, "query")), null),
                )
                "save_note" -> {
                    val out = mutableMapOf("content" to requireStr(params, "text"))
                    str(params, "title")?.let { out["title"] = it }
                    Result.success(AssistantIntent(IntentType.SAVE_NOTE, out, null))
                }
                "set_timer" -> {
                    val out = mutableMapOf("seconds" to requireInt(params, "seconds").toString())
                    str(params, "label")?.let { out["label"] = it }
                    Result.success(AssistantIntent(IntentType.SET_TIMER, out, null))
                }
                "set_alarm" -> {
                    val out = mutableMapOf(
                        "hour" to requireInt(params, "hour").toString(),
                        "minute" to requireInt(params, "minute").toString(),
                    )
                    str(params, "label")?.let { out["label"] = it }
                    Result.success(AssistantIntent(IntentType.SET_ALARM, out, null))
                }
                "create_event" -> {
                    val beginMs = requireLong(params, "begin_ms")
                    val endMs = requireLong(params, "end_ms")
                    if (endMs <= beginMs) return failure("end_ms 必须大于 begin_ms")
                    val begin = Instant.ofEpochMilli(beginMs).atZone(zone)
                    val minutes = ((endMs - beginMs) / 60_000L).coerceAtLeast(1)
                    val out = mutableMapOf(
                        "title" to requireStr(params, "title"),
                        "date" to begin.toLocalDate().toString(),
                        "startTime" to begin.toLocalTime().format(TIME),
                        "durationMinutes" to minutes.toString(),
                        "beginMillis" to beginMs.toString(),
                        "endMillis" to endMs.toString(),
                    )
                    str(params, "description")?.let { out["description"] = it }
                    str(params, "location")?.let { out["location"] = it }
                    Result.success(AssistantIntent(IntentType.CREATE_CALENDAR_EVENT, out, null))
                }
                "open_app" -> Result.success(
                    AssistantIntent(IntentType.OPEN_APP, mapOf("appName" to requireStr(params, "app_id")), null),
                )
                else -> failure("手机端不执行 $intent")
            }
        } catch (e: IllegalArgumentException) {
            failure(e.message ?: "参数映射失败")
        }
    }

    private fun str(params: JsonObject, key: String): String? =
        (params[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun requireStr(params: JsonObject, key: String): String =
        str(params, key) ?: throw IllegalArgumentException("缺少 $key")

    private fun requireInt(params: JsonObject, key: String): Int {
        val value = (params[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("缺少 $key")
        return value
    }

    private fun requireLong(params: JsonObject, key: String): Long {
        val value = (params[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("缺少 $key")
        return value
    }

    private fun failure(message: String): Result<AssistantIntent> =
        Result.failure(IllegalArgumentException(message))
}
