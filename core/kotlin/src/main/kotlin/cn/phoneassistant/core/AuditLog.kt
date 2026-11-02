package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AuditEntry(
    val id: String,
    val tsMillis: Long,
    val intent: String,
    val summary: String,
    val outcome: String,
    val artifact: String? = null,
    val prevHash: String? = null,
    val hash: String? = null,
)

/**
 * 本机动作历史：只追加、保留最近 [limit] 条，写在应用私有目录。
 * 不记录密钥、完整聊天内容或参数原文里的敏感值，只记录可核对的摘要。
 *
 * 每条记录带 SHA-256 哈希链（prevHash + hash）。日志按条数截断，
 * 所以校验从保留区第一条开始；删改中间记录会被 [verify] 发现。
 */
class AuditLog(private val file: File, private val limit: Int = 200) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<AuditEntry> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<AuditEntry>>(file.readText())
    }.getOrDefault(emptyList())

    /** 追加一条记录并返回带链字段的副本；调用方传入的 prevHash/hash 会被覆盖。 */
    @Synchronized
    fun append(entry: AuditEntry): AuditEntry {
        val entries = list()
        val prev = entries.lastOrNull { it.hash != null }?.hash ?: GENESIS
        val seeded = entry.copy(prevHash = prev, hash = null)
        val chained = seeded.copy(hash = hashOf(seeded))
        val updated = (entries + chained).takeLast(limit)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(updated), Charsets.UTF_8)
        }
        return chained
    }

    /**
     * 校验保留区内的哈希链。返回 null 表示通过；
     * AUDIT_TAMPERED 表示某条内容被改，AUDIT_CHAIN_BROKEN 表示中间记录被删/换序。
     * 旧版本写入的条目没有 hash，按“未链接的前缀”跳过。
     */
    @Synchronized
    fun verify(): String? {
        val hashed = list().filter { it.hash != null }
        if (hashed.isEmpty()) return null
        var expectedPrev: String? = null
        for ((index, entry) in hashed.withIndex()) {
            if (hashOf(entry) != entry.hash) return "AUDIT_TAMPERED"
            if (index > 0 && entry.prevHash != expectedPrev) return "AUDIT_CHAIN_BROKEN"
            expectedPrev = entry.hash
        }
        return null
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun hashOf(entry: AuditEntry): String = Es256.sha256Hex(payloadOf(entry))

    private fun payloadOf(entry: AuditEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("tsMillis", entry.tsMillis)
        put("intent", entry.intent)
        put("summary", entry.summary)
        put("outcome", entry.outcome)
        if (entry.artifact == null) put("artifact", JsonNull) else put("artifact", entry.artifact)
        if (entry.prevHash == null) put("prevHash", JsonNull) else put("prevHash", entry.prevHash)
    }

    private companion object {
        const val GENESIS = "genesis"
    }
}
