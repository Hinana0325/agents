package cn.phoneassistant.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateWindowStoreTest {

    private fun newFile(): java.io.File =
        Files.createTempDirectory("rate-window").toFile().resolve("rate.json")

    @Test
    fun allowsTenAndEscalatesTheEleventh() {
        val store = RateWindowStore(newFile())
        val now = 1_000_000L
        repeat(10) {
            assertFalse(store.checkAndRecord(now))
        }
        assertTrue(store.checkAndRecord(now))
    }

    @Test
    fun windowSlidesAfterAMinute() {
        val store = RateWindowStore(newFile())
        val now = 1_000_000L
        repeat(10) { store.checkAndRecord(now) }

        assertFalse(store.checkAndRecord(now + 61_000))
        assertEquals(1, store.recent(now + 61_000).size)
    }

    @Test
    fun persistsAcrossInstances() {
        val file = newFile()
        val now = 1_000_000L
        val first = RateWindowStore(file)
        repeat(10) { first.checkAndRecord(now) }

        val second = RateWindowStore(file)
        assertTrue(second.checkAndRecord(now))
    }

    @Test
    fun corruptFileIsTreatedAsEmpty() {
        val file = newFile().apply {
            parentFile.mkdirs()
            writeText("{not json")
        }
        val store = RateWindowStore(file)

        assertFalse(store.checkAndRecord(1_000_000L))
        assertEquals(1, store.recent(1_000_000L).size)
    }

    @Test
    fun clearResetsTheWindow() {
        val store = RateWindowStore(newFile())
        val now = 1_000_000L
        repeat(10) { store.checkAndRecord(now) }
        store.clear()

        assertFalse(store.checkAndRecord(now))
    }
}
