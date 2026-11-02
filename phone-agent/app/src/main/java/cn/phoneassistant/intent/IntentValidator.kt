package cn.phoneassistant.intent

import cn.phoneassistant.model.AssistantIntent
import cn.phoneassistant.model.IntentType
import cn.phoneassistant.model.ValidationResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 结构化意图的唯一入口校验器。
 *
 * 规则（全部不满足即拒绝，拒绝的意图绝不会到达执行器）：
 * 1. 模型输出必须是 JSON 对象，且 type 必须是 [IntentType] 中的一种；
 * 2. 每个类型只接受下表中的参数键，多余的键一律拒绝；
 * 3. 必填参数缺失、为空、类型不对或超出取值范围时拒绝；
 * 4. 文本参数有长度上限，防止超长内容被原样带入系统 Intent 或文件。
 *
 * 参数表：
 * - OPEN_APP: appName（必填，文本）
 * - SET_ALARM: hour（必填，0..23）、minute（必填，0..59）、label（可选）
 * - CREATE_CALENDAR_EVENT: title（必填）、date（必填，yyyy-MM-dd）、
 *   startTime（必填，HH:mm）、durationMinutes（可选，默认 60，1..1440）、
 *   description（可选）、location（可选）
 * - SET_TIMER: seconds（必填，1..86400）
 * - WEB_SEARCH: query（必填）
 * - SAVE_NOTE: content（必填）、title（可选）
 * - CHAT_ONLY: 无 params，reply（必填，见 parse 的 reply 规则）
 */
object IntentValidator {

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 解析模型返回的原始文本。非 JSON、type 未知、参数非法时返回 Invalid。 */
    fun parse(rawText: String): ValidationResult {
        val cleaned = stripCodeFence(rawText.trim())
        val root = try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            return ValidationResult.Invalid("模型返回的不是合法 JSON，已拒绝执行。请换一种说法再试一次。")
        }
        val typeElement = root["type"]
        val typeName = if (typeElement is JsonPrimitive && typeElement.isString) {
            typeElement.contentOrNull
        } else {
            null
        } ?: return ValidationResult.Invalid("意图缺少 type 字段，已拒绝执行。")
        val type = try {
            IntentType.valueOf(typeName.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid("未知的意图类型“$typeName”，已拒绝执行。")
        }
        val paramsElement = root["params"]
        val paramsObject: JsonObject = when {
            type == IntentType.CHAT_ONLY -> JsonObject(emptyMap())
            paramsElement == null -> {
                return ValidationResult.Invalid("意图缺少 params 字段，已拒绝执行。")
            }
            paramsElement is JsonObject -> paramsElement
            else -> return ValidationResult.Invalid("意图的 params 不是对象，已拒绝执行。")
        }
        val replyElement = root["reply"]
        val reply = if (replyElement is JsonPrimitive && replyElement.isString) {
            replyElement.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (type == IntentType.CHAT_ONLY && reply.isNullOrEmpty()) {
            return ValidationResult.Invalid("纯聊天意图缺少回复内容，已拒绝执行。")
        }
        return validate(type, paramsObject, reply)
    }

    private fun validate(
        type: IntentType,
        params: JsonObject,
        reply: String?,
    ): ValidationResult {
        val allowedKeys = when (type) {
            IntentType.OPEN_APP -> setOf("appName")
            IntentType.SET_ALARM -> setOf("hour", "minute", "label")
            IntentType.CREATE_CALENDAR_EVENT ->
                setOf("title", "date", "startTime", "durationMinutes", "description", "location")
            IntentType.SET_TIMER -> setOf("seconds")
            IntentType.WEB_SEARCH -> setOf("query")
            IntentType.SAVE_NOTE -> setOf("content", "title")
            IntentType.CHAT_ONLY -> emptySet()
        }
        val unknown = params.keys - allowedKeys
        if (unknown.isNotEmpty()) {
            return ValidationResult.Invalid("意图包含不支持的参数：${unknown.joinToString()}，已拒绝执行。")
        }
        val out = mutableMapOf<String, String>()
        fun text(key: String, required: Boolean, maxLength: Int): String? {
            val element = params[key] ?: return if (required) {
                throw InvalidParam("缺少必填参数 $key，已拒绝执行。")
            } else {
                null
            }
            if (element !is JsonPrimitive || !element.isString) {
                throw InvalidParam("参数 $key 必须是文本，已拒绝执行。")
            }
            val value = element.content.trim()
            if (required && value.isEmpty()) {
                throw InvalidParam("参数 $key 不能为空，已拒绝执行。")
            }
            if (value.length > maxLength) {
                throw InvalidParam("参数 $key 过长（最多 $maxLength 字），已拒绝执行。")
            }
            return value.ifEmpty { null }
        }
        fun intValue(key: String, required: Boolean, min: Int, max: Int, default: Int?): Int? {
            val element = params[key] ?: return if (required) {
                throw InvalidParam("缺少必填参数 $key，已拒绝执行。")
            } else {
                default
            }
            if (element !is JsonPrimitive) {
                throw InvalidParam("参数 $key 必须是整数，已拒绝执行。")
            }
            val value = element.intOrNull
                ?: throw InvalidParam("参数 $key 必须是整数，已拒绝执行。")
            if (value < min || value > max) {
                throw InvalidParam("参数 $key 超出范围（$min..$max），已拒绝执行。")
            }
            return value
        }
        return try {
            when (type) {
                IntentType.OPEN_APP -> {
                    out["appName"] = text("appName", required = true, maxLength = 100)!!
                }
                IntentType.SET_ALARM -> {
                    out["hour"] = intValue("hour", required = true, min = 0, max = 23, default = null).toString()
                    out["minute"] = intValue("minute", required = true, min = 0, max = 59, default = null).toString()
                    text("label", required = false, maxLength = 100)?.let { out["label"] = it }
                }
                IntentType.CREATE_CALENDAR_EVENT -> {
                    out["title"] = text("title", required = true, maxLength = 200)!!
                    val dateText = text("date", required = true, maxLength = 10)!!
                    val timeText = text("startTime", required = true, maxLength = 5)!!
                    val date = try {
                        LocalDate.parse(dateText, dateFormat)
                    } catch (e: DateTimeParseException) {
                        throw InvalidParam("日期格式不对，请用 yyyy-MM-dd，已拒绝执行。")
                    }
                    val time = try {
                        LocalTime.parse(timeText, timeFormat)
                    } catch (e: DateTimeParseException) {
                        throw InvalidParam("时间格式不对，请用 HH:mm，已拒绝执行。")
                    }
                    val duration = intValue(
                        "durationMinutes", required = false, min = 1, max = 1440, default = 60,
                    )!!
                    val zone = ZoneId.systemDefault()
                    val beginMillis = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    out["date"] = dateText
                    out["startTime"] = timeText
                    out["durationMinutes"] = duration.toString()
                    out["beginMillis"] = beginMillis.toString()
                    out["endMillis"] = (beginMillis + duration * 60_000L).toString()
                    text("description", required = false, maxLength = 500)?.let { out["description"] = it }
                    text("location", required = false, maxLength = 500)?.let { out["location"] = it }
                }
                IntentType.SET_TIMER -> {
                    out["seconds"] = intValue("seconds", required = true, min = 1, max = 86400, default = null).toString()
                }
                IntentType.WEB_SEARCH -> {
                    out["query"] = text("query", required = true, maxLength = 500)!!
                }
                IntentType.SAVE_NOTE -> {
                    out["content"] = text("content", required = true, maxLength = 5000)!!
                    text("title", required = false, maxLength = 200)?.let { out["title"] = it }
                }
                IntentType.CHAT_ONLY -> {
                    // 无参数，reply 已在 parse 中校验。
                }
            }
            ValidationResult.Valid(AssistantIntent(type, out, reply))
        } catch (e: InvalidParam) {
            ValidationResult.Invalid(e.message ?: "参数非法，已拒绝执行。")
        }
    }

    /** 去掉模型常包的 ```json / ``` 代码围栏，只保留其中的 JSON。 */
    private fun stripCodeFence(text: String): String {
        var result = text
        if (result.startsWith("```")) {
            result = result.removePrefix("```")
            if (result.startsWith("json", ignoreCase = true)) {
                result = result.removePrefix("json").removePrefix("JSON")
            }
            result = result.trim()
        }
        if (result.endsWith("```")) {
            result = result.removeSuffix("```").trim()
        }
        return result
    }

    private class InvalidParam(message: String) : Exception(message)
}
