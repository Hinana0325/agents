package cn.phoneassistant.transport

import cn.phoneassistant.core.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 发送发件箱里到期的条目：成功出队，失败记一次退避。
 * 单条失败不阻塞其余条目；返回本次成功条数。
 */
suspend fun flushDue(outbox: Outbox, send: suspend (JsonObject) -> Unit): Int =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var sent = 0
        for (item in outbox.due(now)) {
            try {
                send(item.payload)
                outbox.markSent(item.key)
                sent++
            } catch (_: Exception) {
                outbox.markFailed(item.key, now)
            }
        }
        sent
    }
