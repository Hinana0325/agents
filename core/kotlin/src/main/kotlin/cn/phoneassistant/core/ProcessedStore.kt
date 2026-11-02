package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProcessedEntry(
    val envelopeId: String,
    val status: String,
    val receipt: JsonObject,
    val processedAtMillis: Long,
)

/**
 * 设备侧持久化去重：记录终态回执，跨重启保证同一信封只执行一次。
 * 只保存终态（done/failed/rejected/cancelled），待确认的不落库。
 */
class ProcessedStore(private val file: File, private val limit: Int = 500) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun receipt(envelopeId: String): JsonObject? =
        entries().firstOrNull { it.envelopeId == envelopeId }?.receipt

    @Synchronized
    fun save(envelopeId: String, receipt: JsonObject, nowMillis: Long = System.currentTimeMillis()) {
        val status = receipt["status"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "unknown"
        val updated = (entries().filterNot { it.envelopeId == envelopeId } +
            ProcessedEntry(envelopeId, status, receipt, nowMillis))
            .sortedBy { it.processedAtMillis }
            .takeLast(limit)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(updated), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun count(): Int = entries().size

    private fun entries(): List<ProcessedEntry> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<ProcessedEntry>>(file.readText())
    }.getOrDefault(emptyList())
}
