package cn.phoneassistant.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 同步增量批（sync-batch.v1）结构校验：同步实体只改数据，不含可执行内容。
 *
 * 与 tools/vector-runner/py/validator.py 的 validate_sync 逐条对应；两侧对
 * protocol/vectors/sync 必须给出完全一致的结果。错误码表见 protocol/spec/sync.md。
 * 返回 null 表示合法，否则是 SYNC_* 错误码。
 */
object SyncValidator {

    val KINDS = listOf(
        "chat_message", "note", "setting", "calendar_mirror",
        "scheduled_task", "memory", "presence", "audit_ref",
    )

    const val BAD_TYPE = "SYNC_BAD_TYPE"
    const val UNKNOWN_FIELD = "SYNC_UNKNOWN_FIELD"
    const val MISSING_FIELD = "SYNC_MISSING_FIELD"
    const val BAD_DEVICE_ID = "SYNC_BAD_DEVICE_ID"
    const val BAD_BASE_REV = "SYNC_BAD_BASE_REV"
    const val BAD_ENTRIES = "SYNC_BAD_ENTRIES"
    const val BAD_KIND = "SYNC_BAD_KIND"
    const val BAD_ID = "SYNC_BAD_ID"
    const val BAD_REV = "SYNC_BAD_REV"
    const val BAD_TS = "SYNC_BAD_TS"
    const val BAD_PAYLOAD = "SYNC_BAD_PAYLOAD"
    const val TOMBSTONE_PAYLOAD = "SYNC_TOMBSTONE_PAYLOAD"
    const val DUP_ID = "SYNC_DUP_ID"
    const val BAD_CURSOR = "SYNC_BAD_CURSOR"

    const val MAX_ENTRIES = 500
    const val MAX_CURSOR = 512

    private val BATCH_FIELDS = setOf("device_id", "base_rev", "entries", "cursor", "next")
    private val BATCH_REQUIRED = listOf("device_id", "base_rev", "entries", "next")
    private val ENTITY_FIELDS = setOf("kind", "id", "rev", "deleted", "payload", "device_id", "ts")
    private val ENTITY_REQUIRED = listOf("kind", "id", "rev", "device_id", "ts")

    private val UUID7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")

    fun validate(batch: JsonElement): String? {
        val obj = batch as? JsonObject ?: return BAD_TYPE
        checkKeys(obj, BATCH_FIELDS, BATCH_REQUIRED)?.let { return it }

        checkDeviceId(obj["device_id"])?.let { return it }

        val baseRev = obj["base_rev"]
        if (!isInt(baseRev) || intOf(baseRev!!) < 0) return BAD_BASE_REV

        val entries = obj["entries"]
        if (entries !is JsonArray) return BAD_TYPE
        if (entries.size > MAX_ENTRIES) return BAD_ENTRIES
        for (entry in entries) validateEntity(entry)?.let { return it }

        obj["cursor"]?.let { element ->
            val text = str(element) ?: return BAD_TYPE
            if (cpLength(text) > MAX_CURSOR) return BAD_CURSOR
        }
        if (!isBool(obj["next"])) return BAD_TYPE

        val seen = mutableSetOf<String>()
        for (entry in entries) {
            val id = str((entry as JsonObject)["id"])!!
            if (!seen.add(id)) return DUP_ID
        }
        return null
    }

    private fun validateEntity(entry: JsonElement): String? {
        val obj = entry as? JsonObject ?: return BAD_TYPE
        checkKeys(obj, ENTITY_FIELDS, ENTITY_REQUIRED)?.let { return it }

        val kind = obj["kind"]
        if (!isString(kind)) return BAD_TYPE
        if (str(kind)!! !in KINDS) return BAD_KIND

        if (!isString(obj["id"]) || !UUID7.matches(str(obj["id"])!!)) return BAD_ID
        if (!isInt(obj["rev"]) || intOf(obj["rev"]!!) < 1) return BAD_REV
        checkDeviceId(obj["device_id"])?.let { return it }
        if (!isString(obj["ts"]) || !TS.matches(str(obj["ts"])!!)) return BAD_TS

        obj["deleted"]?.let { if (!isBool(it)) return BAD_TYPE }
        val deleted = (obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true

        if (deleted) {
            if ("payload" in obj) return TOMBSTONE_PAYLOAD
        } else {
            if (obj["payload"] !is JsonObject) return BAD_PAYLOAD
        }
        return null
    }

    private fun checkDeviceId(element: JsonElement?): String? {
        val text = str(element) ?: return BAD_DEVICE_ID
        if (text.isEmpty() || cpLength(text) > 64) return BAD_DEVICE_ID
        return null
    }

    private fun checkKeys(obj: JsonObject, allowed: Set<String>, required: List<String>): String? {
        for (key in obj.keys) if (key !in allowed) return UNKNOWN_FIELD
        for (key in required) if (key !in obj) return MISSING_FIELD
        return null
    }

    // ---- 基础类型判定（与 Python 侧语义保持一致） ----

    private fun isString(element: JsonElement?): Boolean =
        element is JsonPrimitive && element.isString

    /** kotlinx 的 booleanOrNull 会解析字符串内容，所以必须同时排除 isString。 */
    private fun isBool(element: JsonElement?): Boolean =
        element is JsonPrimitive && !element.isString && element.booleanOrNull != null

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isInt(element: JsonElement?): Boolean {
        if (element !is JsonPrimitive || element.isString || element.booleanOrNull != null) return false
        val content = element.content
        if (content.isEmpty() || content.contains('.') || content.contains('e') || content.contains('E')) return false
        return content.toLongOrNull() != null
    }

    private fun intOf(element: JsonElement): Long = (element as JsonPrimitive).content.toLong()

    private fun cpLength(text: String): Int = text.codePointCount(0, text.length)
}
