package cn.phoneassistant.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IssuedCertStoreTest {

    @Test
    fun addsAndPersists() {
        val file = Files.createTempDirectory("issued").resolve("issued.json").toFile()
        val store = IssuedCertStore(file)

        store.add(IssuedCertRecord("pc-1", "a".repeat(64), 1000, "{}"))

        val reloaded = IssuedCertStore(file)
        assertEquals(1, reloaded.list().size)
        assertEquals("pc-1", reloaded.list().single().deviceId)
        assertNull(reloaded.list().single().revokedAtMillis)
    }

    @Test
    fun sameHashIsReplaced() {
        val file = Files.createTempDirectory("issued-replace").resolve("issued.json").toFile()
        val store = IssuedCertStore(file)

        store.add(IssuedCertRecord("pc-1", "a".repeat(64), 1000, "{\"v\":1}"))
        store.add(IssuedCertRecord("pc-1", "a".repeat(64), 2000, "{\"v\":1,\"x\":2}"))

        assertEquals(1, store.list().size)
        assertEquals(2000, store.list().single().issuedAtMillis)
    }

    @Test
    fun marksRevoked() {
        val file = Files.createTempDirectory("issued-revoke").resolve("issued.json").toFile()
        val store = IssuedCertStore(file)
        store.add(IssuedCertRecord("pc-1", "a".repeat(64), 1000, "{}"))

        store.markRevoked("a".repeat(64), "{\"revoked\":true}", 3000)

        val record = store.list().single()
        assertNotNull(record.revokedAtMillis)
        assertEquals("{\"revoked\":true}", record.revocationJson)
    }

    @Test
    fun damagedFileIsEmpty() {
        val file = Files.createTempDirectory("issued-bad").resolve("issued.json").toFile()
        file.writeText("{not json")

        assertEquals(0, IssuedCertStore(file).list().size)
    }
}
