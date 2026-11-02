package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AuditEntry(
    val id: String,
    val tsMillis: Long,
    val intent: String,
    val summary: String,
    val outcome: String,
    val artifact: String? = null,
)

/**
 * 本机动作历史：只追加、保留最近 [limit] 条，写在应用私有目录。
 * 不记录密钥、完整聊天内容或参数原文里的敏感值，只记录可核对的摘要。
 */
class AuditLog(private val file: File, private val limit: Int = 200) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<AuditEntry> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<AuditEntry>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun append(entry: AuditEntry) {
        val updated = (list() + entry).takeLast(limit)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(updated), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
