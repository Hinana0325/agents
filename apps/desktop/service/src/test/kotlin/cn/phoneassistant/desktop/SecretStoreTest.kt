package cn.phoneassistant.desktop

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SecretStoreTest {

    @Test
    fun protectRoundTrip() {
        val data = "身份密钥测试".toByteArray(Charsets.UTF_8)
        val stored = SecretStore.protect(data)
        assertArrayEquals(data, SecretStore.unprotect(stored))
        if (SecretStore.isProtected) {
            assertFalse("DPAPI 后不应与明文相同", stored.contentEquals(data))
        }
    }

    @Test
    fun identityIsEncryptedAtRestOnWindows() {
        assumeTrue(SecretStore.isProtected)
        val dir = Files.createTempDirectory("identity-dpapi").toFile()

        val identity = DesktopIdentity(dir)
        val protectedFile = File(dir, "identity-private.dpapi")
        val plainFile = File(dir, "identity-private.der")
        assertTrue(protectedFile.isFile)
        assertFalse("不应留下明文私钥", plainFile.exists())

        val reloaded = DesktopIdentity(dir)
        assertEquals(identity.publicJwk(), reloaded.publicJwk())
    }

    @Test
    fun legacyPlaintextKeyIsMigrated() {
        assumeTrue(SecretStore.isProtected)
        val dir = Files.createTempDirectory("identity-legacy").toFile()

        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        File(dir, "identity-private.der").writeBytes(pair.private.encoded)
        File(dir, "identity-public.der").writeBytes(pair.public.encoded)

        val identity = DesktopIdentity(dir)

        assertFalse("迁移后应删除明文私钥", File(dir, "identity-private.der").exists())
        assertTrue(File(dir, "identity-private.dpapi").isFile)
        assertArrayEquals(pair.public.encoded, identity.keyPair.public.encoded)
    }
}
