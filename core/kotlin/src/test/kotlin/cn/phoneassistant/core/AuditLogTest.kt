package cn.phoneassistant.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
