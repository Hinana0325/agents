package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 持久化的投递速率窗口：同一设备跨同步/重启也生效，防止把洪水拆成多次拉取绕过升 L3。
 * [checkAndRecord] 返回 true 表示本次已经超过 limit（窗口内已有 limit 条）。
 * 坏文件按空处理；只保留窗口内及最近 [limit] * 2 条，文件不会无限增长。
 */
class RateWindowStore(
    private val file: File,
    private val windowSeconds: Long = 60,
    private val limit: Int = 10,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun checkAndRecord(nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val cutoff = nowMillis - windowSeconds * 1000
        val recent = load().filter { it > cutoff }
        val over = recent.size >= limit
        save((recent + nowMillis).takeLast(limit * 2))
        over
    }

    fun recent(nowMillis: Long = System.currentTimeMillis()): List<Long> = synchronized(LOCK) {
        load().filter { it > nowMillis - windowSeconds * 1000 }
    }

    fun clear() {
        synchronized(LOCK) { runCatching { file.delete() } }
    }

    private fun save(times: List<Long>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(times), Charsets.UTF_8)
        }
    }

    private fun load(): List<Long> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<Long>>(file.readText())
    }.getOrDefault(emptyList())

    private companion object {
        val LOCK = Any()
    }
}
