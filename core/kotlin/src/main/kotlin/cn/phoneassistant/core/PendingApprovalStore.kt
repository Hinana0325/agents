package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class PendingApproval(
    val envelopeId: String,
    val level: Int,
    val envelope: JsonObject,
    val addedAtMillis: Long,
)

/**
 * 待确认信封的本地落盘：进程被杀/重启后仍能把审批弹出来，而不是丢掉任务。
 * 按信封 id 去重；最多保留最近 [limit] 条；坏文件按空处理。
 */
class PendingApprovalStore(private val file: File, private val limit: Int = 50) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<PendingApproval> = synchronized(LOCK) { load() }

    fun put(entry: PendingApproval) {
        synchronized(LOCK) {
            val updated = (load().filterNot { it.envelopeId == entry.envelopeId } + entry).takeLast(limit)
            save(updated)
        }
    }

    fun remove(envelopeId: String) {
        synchronized(LOCK) {
            save(load().filterNot { it.envelopeId == envelopeId })
        }
    }

    fun size(): Int = synchronized(LOCK) { load().size }

    private fun save(items: List<PendingApproval>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private fun load(): List<PendingApproval> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<PendingApproval>>(file.readText())
    }.getOrDefault(emptyList())

    private companion object {
        val LOCK = Any()
    }
}
