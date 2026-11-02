package cn.phoneassistant.core

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {

    private fun payload(status: String) = buildJsonObject { put("status", status) }

    @Test
    fun enqueuePersistsAndIsIdempotent() {
        val file = Files.createTempDirectory("outbox").resolve("outbox.json").toFile()
        val outbox = Outbox(file)

        outbox.enqueue("receipt:1", "receipt", payload("done"), 1000)
        outbox.enqueue("receipt:1", "receipt", payload("failed"), 2000)

        val reloaded = Outbox(file)
        assertEquals(1, reloaded.size())
        assertEquals("failed", reloaded.list().single().payload["status"]!!.toString().trim('"'))
    }

    @Test
    fun dueHonoursBackoff() {
        val file = Files.createTempDirectory("outbox-due").resolve("outbox.json").toFile()
        val outbox = Outbox(file, baseDelayMillis = 1_000)

        outbox.enqueue("receipt:1", "receipt", payload("done"), 1000)
        assertTrue(outbox.markFailed("receipt:1", 2000)) // 仍在队列，等待重试
        assertTrue(outbox.due(2500).isEmpty())
        assertEquals(1, outbox.due(3000).size)
    }

    @Test
    fun exhaustedItemIsDropped() {
        val file = Files.createTempDirectory("outbox-drop").resolve("outbox.json").toFile()
        val outbox = Outbox(file, maxAttempts = 2, baseDelayMillis = 0)

        outbox.enqueue("receipt:1", "receipt", payload("done"), 0)
        assertTrue(outbox.markFailed("receipt:1", 0))
        assertFalse(outbox.markFailed("receipt:1", 0))
        assertEquals(0, outbox.size())
    }

    @Test
    fun markSentRemovesItem() {
        val file = Files.createTempDirectory("outbox-sent").resolve("outbox.json").toFile()
        val outbox = Outbox(file)

        outbox.enqueue("receipt:1", "receipt", payload("done"), 0)
        outbox.markSent("receipt:1")

        assertEquals(0, outbox.size())
    }

    @Test
    fun damagedFileIsEmpty() {
        val file = Files.createTempDirectory("outbox-bad").resolve("outbox.json").toFile()
        file.writeText("{not json")

        assertEquals(0, Outbox(file).size())
    }
}
