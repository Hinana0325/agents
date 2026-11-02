package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class OutboxItem(
    val key: String,
    val kind: String,
    val payload: JsonObject,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
)

/**
 * 设备发件箱：先落盘再发送，失败按指数退避重试；同一个 key 幂等（覆盖旧内容）。
 * 退避上限与最大尝试次数可配；耗尽后丢弃并返回 false，由调用方决定是否提示。
 */
class Outbox(
    private val file: File,
    private val maxAttempts: Int = 8,
    private val baseDelayMillis: Long = 2_000,
    private val maxDelayMillis: Long = 5 * 60_000,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<OutboxItem> = synchronized(LOCK) { load() }

    fun enqueue(key: String, kind: String, payload: JsonObject, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            val updated = load().filterNot { it.key == key } + OutboxItem(key, kind, payload, 0, nowMillis)
            save(updated)
        }
    }

    fun due(nowMillis: Long = System.currentTimeMillis()): List<OutboxItem> =
        synchronized(LOCK) { load().filter { it.nextAttemptAtMillis <= nowMillis } }

    fun markSent(key: String) {
        synchronized(LOCK) { save(load().filterNot { it.key == key }) }
    }

    /** @return true 仍在队列（等待重试），false 已耗尽被丢弃 */
    fun markFailed(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(LOCK) {
            val items = load()
            val item = items.firstOrNull { it.key == key } ?: return@synchronized false
            val attempts = item.attempts + 1
            if (attempts >= maxAttempts) {
                save(items.filterNot { it.key == key })
                return@synchronized false
            }
            val delay = minOf(baseDelayMillis shl minOf(attempts - 1, 20), maxDelayMillis)
            val updated = items.map {
                if (it.key == key) it.copy(attempts = attempts, nextAttemptAtMillis = nowMillis + delay) else it
            }
            save(updated)
            return@synchronized true
        }

    fun size(): Int = synchronized(LOCK) { load().size }

    private fun save(items: List<OutboxItem>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private fun load(): List<OutboxItem> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<OutboxItem>>(file.readText())
    }.getOrDefault(emptyList())

    private companion object {
        /** 进程级锁：界面和后台 worker 各持有一个 Outbox 实例，不能只锁实例。 */
        val LOCK = Any()
    }
}
