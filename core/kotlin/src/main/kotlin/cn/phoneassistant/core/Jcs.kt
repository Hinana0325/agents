package cn.phoneassistant.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * RFC 8785 JCS 规范化（本项目子集）。
 * 只允许整数：信封里出现浮点数在解析阶段就判 BAD_TYPE，这里直接抛错。
 * 键按 UTF-16 code unit 排序，Kotlin 的 String.compareTo 正是这个顺序。
 */
object Jcs {

    fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, sb: StringBuilder) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> writePrimitive(element, sb)
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) sb.append(',')
                    write(value, sb)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    escape(key, sb)
                    sb.append(':')
                    write(element.getValue(key), sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writePrimitive(value: JsonPrimitive, sb: StringBuilder) {
        if (value.isString) {
            escape(value.content, sb)
            return
        }
        val bool = value.booleanOrNull
        if (bool != null) {
            sb.append(if (bool) "true" else "false")
            return
        }
        val number = value.content
        require(!number.contains('.') && !number.contains('e') && !number.contains('E')) {
            "floats are not allowed in envelopes"
        }
        val asLong = number.toLongOrNull() ?: throw IllegalArgumentException("invalid integer: $number")
        sb.append(asLong.toString())
    }

    private fun escape(text: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in text) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
