package cn.phoneassistant.core

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogTest {

    @Test
    fun appendsAndReloads() {
        val dir = Files.createTempDirectory("audit").toFile()
        val file = dir.resolve("audit.json")
        val log = AuditLog(file)

        log.append(AuditEntry("1", 1000, "SAVE_NOTE", "title=购物", "success", "a.txt"))
        log.append(AuditEntry("2", 2000, "WEB_SEARCH", "q=天气", "success"))

        val reloaded = AuditLog(file).list()
        assertEquals(2, reloaded.size)
        assertEquals("SAVE_NOTE", reloaded[0].intent)
        assertEquals("a.txt", reloaded[0].artifact)
    }

    @Test
    fun keepsOnlyLatestEntries() {
        val dir = Files.createTempDirectory("audit-cap").toFile()
        val log = AuditLog(dir.resolve("audit.json"), limit = 2)

        repeat(5) { index ->
            log.append(AuditEntry("$index", index.toLong(), "CHAT_ONLY", "m$index", "success"))
        }

        assertEquals(listOf("3", "4"), log.list().map { it.id })
    }

    @Test
    fun damagedFileIsTreatedAsEmpty() {
        val dir = Files.createTempDirectory("audit-bad").toFile()
        val file = dir.resolve("audit.json").apply { writeText("{not json") }

        assertTrue(AuditLog(file).list().isEmpty())
    }

    @Test
    fun entriesAreHashChained() {
        val dir = Files.createTempDirectory("audit-chain").toFile()
        val log = AuditLog(dir.resolve("audit.json"))

        repeat(3) { index ->
            log.append(AuditEntry("$index", index.toLong(), "CHAT_ONLY", "m$index", "success"))
        }

        val entries = log.list()
        assertNotNull(entries[0].hash)
        assertEquals(entries[0].hash, entries[1].prevHash)
        assertEquals(entries[1].hash, entries[2].prevHash)
        assertNull(log.verify())
    }

    @Test
    fun tamperedEntryIsDetected() {
        val dir = Files.createTempDirectory("audit-tamper").toFile()
        val file = dir.resolve("audit.json")
        val log = AuditLog(file)
        repeat(3) { index ->
            log.append(AuditEntry("$index", index.toLong(), "CHAT_ONLY", "m$index", "success"))
        }

        file.writeText(file.readText().replace("m1", "hacked"))

        assertEquals("AUDIT_TAMPERED", log.verify())
    }

    @Test
    fun removedMiddleEntryBreaksChain() {
        val dir = Files.createTempDirectory("audit-gap").toFile()
        val file = dir.resolve("audit.json")
        val log = AuditLog(file)
        repeat(3) { index ->
            log.append(AuditEntry("$index", index.toLong(), "CHAT_ONLY", "m$index", "success"))
        }
        val json = Json { encodeDefaults = true }
        val entries = json.decodeFromString<List<AuditEntry>>(file.readText())
        file.writeText(json.encodeToString(entries.filterIndexed { index, _ -> index != 1 }))

        assertEquals("AUDIT_CHAIN_BROKEN", log.verify())
    }

    @Test
    fun legacyEntriesAreKeptAndNewOnesStartAChain() {
        val dir = Files.createTempDirectory("audit-legacy").toFile()
        val file = dir.resolve("audit.json")
        file.writeText("""[{"id":"old","tsMillis":1,"intent":"CHAT_ONLY","summary":"old","outcome":"success"}]""")
        val log = AuditLog(file)

        assertNull(log.verify())
        log.append(AuditEntry("new", 2, "SAVE_NOTE", "new", "success"))

        assertEquals(2, log.list().size)
        assertNotNull(log.list().last().hash)
        assertNull(log.verify())
    }

    @Test
    fun prunedLogStillVerifies() {
        val dir = Files.createTempDirectory("audit-prune").toFile()
        val log = AuditLog(dir.resolve("audit.json"), limit = 2)

        repeat(5) { index ->
            log.append(AuditEntry("$index", index.toLong(), "CHAT_ONLY", "m$index", "success"))
        }

        assertEquals(2, log.list().size)
        assertNull(log.verify())
    }
}
