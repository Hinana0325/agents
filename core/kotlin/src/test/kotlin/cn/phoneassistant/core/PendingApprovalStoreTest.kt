package cn.phoneassistant.core

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingApprovalStoreTest {

    private fun newFile(): java.io.File =
        Files.createTempDirectory("pending-approvals").toFile().resolve("pending.json")

    private fun entry(id: String, level: Int = 2): PendingApproval = PendingApproval(
        envelopeId = id,
        level = level,
        envelope = buildJsonObject { put("id", id) },
        addedAtMillis = 1000,
    )

    @Test
    fun putListRemoveAndPersist() {
        val file = newFile()
        val store = PendingApprovalStore(file)
        store.put(entry("a"))
        store.put(entry("b"))

        assertEquals(listOf("a", "b"), store.list().map { it.envelopeId })
        assertEquals(listOf("a", "b"), PendingApprovalStore(file).list().map { it.envelopeId })

        store.remove("a")
        assertEquals(listOf("b"), PendingApprovalStore(file).list().map { it.envelopeId })
    }

    @Test
    fun sameEnvelopeIsReplacedNotDuplicated() {
        val store = PendingApprovalStore(newFile())
        store.put(entry("a", level = 2))
        store.put(entry("a", level = 3))

        assertEquals(1, store.size())
        assertEquals(3, store.list().single().level)
    }

    @Test
    fun keepsOnlyLatestEntries() {
        val store = PendingApprovalStore(newFile(), limit = 2)
        repeat(5) { store.put(entry("id$it")) }

        assertEquals(listOf("id3", "id4"), store.list().map { it.envelopeId })
    }

    @Test
    fun damagedFileIsTreatedAsEmpty() {
        val file = newFile().apply {
            parentFile.mkdirs()
            writeText("{not json")
        }

        assertTrue(PendingApprovalStore(file).list().isEmpty())
    }
}
