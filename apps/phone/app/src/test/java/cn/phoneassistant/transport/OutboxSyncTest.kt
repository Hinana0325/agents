package cn.phoneassistant.transport

import cn.phoneassistant.core.Outbox
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxSyncTest {

    private fun newOutbox(baseDelayMillis: Long = 2_000): Outbox =
        Outbox(File.createTempFile("outbox", ".json").apply { delete() }, baseDelayMillis = baseDelayMillis)

    private fun receipt(id: String): JsonObject = buildJsonObject {
        put("envelope_id", id)
        put("status", "done")
    }

    private fun idOf(payload: JsonObject): String =
        (payload["envelope_id"] as JsonPrimitive).content

    @Test
    fun sendsDueItemsOnceAndClearsThem() = runBlocking {
        val box = newOutbox()
        box.enqueue("receipt:a", "receipt", receipt("a"))
        box.enqueue("receipt:b", "receipt", receipt("b"))
        val sent = mutableListOf<String>()

        val count = flushDue(box) { sent += idOf(it) }

        assertEquals(2, count)
        assertEquals(listOf("a", "b"), sent)
        assertEquals(0, box.size())
    }

    @Test
    fun failedItemStaysWithBackoffAndOtherItemsStillGo() = runBlocking {
        val box = newOutbox()
        box.enqueue("receipt:a", "receipt", receipt("a"))
        box.enqueue("receipt:b", "receipt", receipt("b"))
        val sent = mutableListOf<String>()

        val count = flushDue(box) {
            if (idOf(it) == "a") throw IllegalStateException("offline")
            sent += idOf(it)
        }

        assertEquals(1, count)
        assertEquals(listOf("b"), sent)
        val remaining = box.list().single()
        assertEquals("receipt:a", remaining.key)
        assertEquals(1, remaining.attempts)
        assertTrue(remaining.nextAttemptAtMillis > System.currentTimeMillis())
    }

    @Test
    fun failedItemIsRetriedAfterBackoff() = runBlocking {
        val box = newOutbox(baseDelayMillis = 0)
        box.enqueue("receipt:a", "receipt", receipt("a"))
        flushDue(box) { throw IllegalStateException("offline") }
        assertEquals(1, box.size())
        assertEquals(1, box.list().single().attempts)

        val sent = mutableListOf<String>()
        flushDue(box) { sent += idOf(it) }

        assertEquals(listOf("a"), sent)
        assertEquals(0, box.size())
    }
}
