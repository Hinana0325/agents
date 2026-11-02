package cn.phoneassistant.core

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedStoreTest {

    private fun receipt(status: String) = buildJsonObject { put("status", status) }

    @Test
    fun receiptsSurviveReload() {
        val file = Files.createTempDirectory("processed").resolve("processed.json").toFile()
        val store = ProcessedStore(file)
        assertNull(store.receipt("id-1"))

        store.save("id-1", receipt("done"), nowMillis = 1000)

        val reloaded = ProcessedStore(file)
        assertEquals("done", reloaded.receipt("id-1")!!["status"]!!.toString().trim('"'))
        assertEquals(1, reloaded.count())
    }

    @Test
    fun sameIdIsReplaced() {
        val file = Files.createTempDirectory("processed-replace").resolve("processed.json").toFile()
        val store = ProcessedStore(file)

        store.save("id-1", receipt("done"), 1000)
        store.save("id-1", receipt("failed"), 2000)

        assertEquals("failed", store.receipt("id-1")!!["status"]!!.toString().trim('"'))
        assertEquals(1, store.count())
    }

    @Test
    fun oldEntriesAreTrimmed() {
        val file = Files.createTempDirectory("processed-cap").resolve("processed.json").toFile()
        val store = ProcessedStore(file, limit = 2)

        repeat(5) { index -> store.save("id-$index", receipt("done"), index.toLong()) }

        assertEquals(2, store.count())
        assertNull(store.receipt("id-0"))
        assertEquals("done", store.receipt("id-4")!!["status"]!!.toString().trim('"'))
    }

    @Test
    fun damagedFileIsTreatedAsEmpty() {
        val file = Files.createTempDirectory("processed-bad").resolve("processed.json").toFile()
        file.writeText("{not json")

        assertTrue(ProcessedStore(file).count() == 0)
    }
}
