package cn.phoneassistant.desktop

import cn.phoneassistant.core.DeviceCert
import cn.phoneassistant.core.Revocation
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private val now: Instant = Instant.parse("2026-11-02T10:00:00Z")
    private val phone = keyPair()
    private val other = keyPair()

    @Test
    fun trustRootAndCertificateArePersisted() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing").toFile()).also { it.ensure() }
        val identity = DesktopIdentity(paths.home)
        val config = DesktopConfigFile(device_id = "pc-1")

        val trusted = Pairing.trust(paths, config, jwk(phone)).getOrThrow()
        assertNotNull(trusted.phone_pubkey)
        assertNull(trusted.device_cert)

        val cert = certificateFor(identity, signingKey = phone.private)
        val verified = Pairing.verifyCertificate(paths, trusted, cert, now).getOrThrow()
        assertEquals(cert, verified.device_cert)

        val reloaded = DesktopConfig.loadOrCreate(paths)
        assertEquals(cert, reloaded.device_cert)
        assertEquals(jwk(phone), reloaded.phone_pubkey)
    }

    @Test
    fun revokedCertificateIsRejectedAndCleared() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-revoke").toFile()).also { it.ensure() }
        val identity = DesktopIdentity(paths.home)
        val trusted = Pairing.trust(paths, DesktopConfigFile(device_id = "pc-1"), jwk(phone)).getOrThrow()
        val cert = certificateFor(identity, signingKey = phone.private)
        val verified = Pairing.verifyCertificate(paths, trusted, cert, now).getOrThrow()
        assertEquals(cert, verified.device_cert)

        val revocation = Revocation.create(
            deviceId = "pc-1",
            certHash = DeviceCert.hash(cert),
            issuedBy = "phone-1",
            reason = "设备丢失",
            now = now,
            signingKey = phone.private,
        )
        val revoked = Pairing.revoke(paths, verified, revocation, now).getOrThrow()

        assertEquals(listOf(DeviceCert.hash(cert)), revoked.revoked_certs)
        assertNull(revoked.device_cert)

        val again = Pairing.verifyCertificate(paths, revoked, cert, now)
        assertTrue(again.isFailure)
        assertEquals("CERT_REVOKED", again.exceptionOrNull()!!.message)
    }

    @Test
    fun tamperedRevocationIsRejected() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-revoke-bad").toFile()).also { it.ensure() }
        val trusted = Pairing.trust(paths, DesktopConfigFile(device_id = "pc-1"), jwk(phone)).getOrThrow()
        val revocation = JsonObject(
            Revocation.create("pc-1", "a".repeat(64), "phone-1", null, now, phone.private)
                .toMutableMap().apply { put("device_id", JsonPrimitive("pc-2")) },
        )

        val result = Pairing.revoke(paths, trusted, revocation, now)

        assertTrue(result.isFailure)
        assertEquals("REVOCATION_SIGNATURE", result.exceptionOrNull()!!.message)
    }

    @Test
    fun verifyWithoutTrustRootFails() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-noroot").toFile()).also { it.ensure() }
        val identity = DesktopIdentity(paths.home)
        val config = DesktopConfigFile(device_id = "pc-1")
        val cert = certificateFor(identity, signingKey = phone.private)

        val result = Pairing.verifyCertificate(paths, config, cert, now)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("信任根"))
    }

    @Test
    fun tamperedCertificateIsRejected() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-tamper").toFile()).also { it.ensure() }
        val identity = DesktopIdentity(paths.home)
        val trusted = Pairing.trust(paths, DesktopConfigFile(device_id = "pc-1"), jwk(phone)).getOrThrow()
        val cert = JsonObject(
            certificateFor(identity, signingKey = phone.private).toMutableMap()
                .apply { put("max_level", JsonPrimitive(1)) },
        )

        val result = Pairing.verifyCertificate(paths, trusted, cert, now)

        assertTrue(result.isFailure)
        assertEquals("CERT_SIGNATURE", result.exceptionOrNull()!!.message)
    }

    @Test
    fun certificateFromAnotherKeyIsRejected() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-wrongkey").toFile()).also { it.ensure() }
        val identity = DesktopIdentity(paths.home)
        val trusted = Pairing.trust(paths, DesktopConfigFile(device_id = "pc-1"), jwk(phone)).getOrThrow()
        val cert = certificateFor(identity, signingKey = other.private)

        assertTrue(Pairing.verifyCertificate(paths, trusted, cert, now).isFailure)
    }

    @Test
    fun invalidPhoneKeyIsRejected() {
        val paths = DesktopPaths(Files.createTempDirectory("pairing-badkey").toFile()).also { it.ensure() }
        val bad = buildJsonObject {
            put("kty", "RSA")
            put("crv", "P-256")
            put("x", "a")
            put("y", "b")
        }
        assertTrue(Pairing.trust(paths, DesktopConfigFile(device_id = "pc-1"), bad).isFailure)
    }

    private fun certificateFor(identity: DesktopIdentity, signingKey: java.security.PrivateKey): JsonObject =
        DeviceCert.create(
            deviceId = "pc-1",
            role = "pc",
            maxLevel = 2,
            pubkeyJwk = identity.publicJwk(),
            issuedBy = "phone-1",
            issuedAt = timestamp(now.minusSeconds(60)),
            expiresAt = timestamp(now.plusSeconds(86_400)),
            signingKey = signingKey,
        )

    private fun timestamp(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(instant)

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun jwk(pair: KeyPair): JsonObject {
        val publicKey = pair.public as ECPublicKey
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", b64(publicKey.w.affineX))
            put("y", b64(publicKey.w.affineY))
        }
    }

    private fun b64(value: BigInteger): String {
        val bytes = value.toByteArray()
        val fixed = when {
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> bytes
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed)
    }
}
