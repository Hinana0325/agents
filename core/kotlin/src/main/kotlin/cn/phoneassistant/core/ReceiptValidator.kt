package cn.phoneassistant.core

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设备回执（receipt.v1）结构校验：回执只推进状态机，不含可执行内容。
 * 云端/执行端收到回执后应先过这里；返回 null 表示合法，否则是错误码。
 */
object ReceiptValidator {

    const val INVALID = "RECEIPT_INVALID"

    private val REQUIRED = setOf("v", "envelope_id", "device_id", "status", "ts")
    private val FIELDS = REQUIRED + "error"
    private val STATUSES = setOf(
        "accepted", "awaiting_confirm", "executing", "done",
        "failed", "expired", "rejected", "cancelled",
    )
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val ERROR_FIELDS = setOf("code", "detail")

    fun verify(receipt: JsonElement): String? {
        val obj = receipt as? JsonObject ?: return INVALID
        if (obj.keys.any { it !in FIELDS }) return INVALID
        if (!obj.keys.containsAll(REQUIRED)) return INVALID

        val version = obj["v"] as? JsonPrimitive ?: return INVALID
        if (version.isString || version.content != "1") return INVALID

        for (key in listOf("envelope_id", "device_id")) {
            val value = str(obj[key]) ?: return INVALID
            if (value.length !in 1..64) return INVALID
        }

        val status = str(obj["status"]) ?: return INVALID
        if (status !in STATUSES) return INVALID

        val tsText = str(obj["ts"]) ?: return INVALID
        if (!TS.matches(tsText)) return INVALID
        try {
            Instant.parse(tsText)
        } catch (e: DateTimeParseException) {
            return INVALID
        }

        obj["error"]?.let { element ->
            val error = element as? JsonObject ?: return INVALID
            if (error.keys.any { it !in ERROR_FIELDS } || "code" !in error.keys) return INVALID
            val code = str(error["code"]) ?: return INVALID
            if (code.length !in 1..64) return INVALID
            error["detail"]?.let { detail ->
                val text = str(detail) ?: return INVALID
                if (text.length > 500) return INVALID
            }
        }
        return null
    }

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
