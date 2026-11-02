package cn.phoneassistant.core

import java.math.BigInteger
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
import org.junit.Assert.assertNull
import org.junit.Test

class RevocationTest {

    private val now: Instant = Instant.parse("2026-11-02T10:00:00Z")
    private val phone = keyPair()
    private val other = keyPair()

    private fun ts(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(instant)

    private fun cert(): JsonObject = DeviceCert.create(
        deviceId = "pc-1",
        role = "pc",
        maxLevel = 2,
        pubkeyJwk = jwk(other),
        issuedBy = "phone-1",
        issuedAt = ts(now.minusSeconds(60)),
        expiresAt = ts(now.plusSeconds(86_400)),
        signingKey = phone.private,
    )

    @Test
    fun validRevocationPasses() {
        val revocation = Revocation.create("pc-1", DeviceCert.hash(cert()), "phone-1", "设备丢失", now, phone.private)

        assertNull(Revocation.verify(revocation, jwk(phone), now))
    }

    @Test
    fun tamperedRevocationFailsSignature() {
        val revocation = Revocation.create("pc-1", DeviceCert.hash(cert()), "phone-1", null, now, phone.private)
        val tampered = JsonObject(revocation.toMutableMap().apply { put("device_id", JsonPrimitive("pc-2")) })

        assertEquals("REVOCATION_SIGNATURE", Revocation.verify(tampered, jwk(phone), now))
    }

    @Test
    fun wrongTrustRootFails() {
        val revocation = Revocation.create("pc-1", DeviceCert.hash(cert()), "phone-1", null, now, phone.private)

        assertEquals("REVOCATION_SIGNATURE", Revocation.verify(revocation, jwk(other), now))
    }

    @Test
    fun futureRevocationIsRejected() {
        val revocation = Revocation.create("pc-1", DeviceCert.hash(cert()), "phone-1", null, now.plusSeconds(600), phone.private)

        assertEquals("REVOCATION_NOT_YET_VALID", Revocation.verify(revocation, jwk(phone), now))
    }

    @Test
    fun revokedCertificateIsRejected() {
        val certificate = cert()
        val hash = DeviceCert.hash(certificate)
        val revocation = Revocation.create("pc-1", hash, "phone-1", null, now, phone.private)

        assertNull(Revocation.verify(revocation, jwk(phone), now))
        assertNull(DeviceCert.verify(certificate, jwk(phone), now))
        assertEquals("CERT_REVOKED", DeviceCert.verify(certificate, jwk(phone), now, setOf(hash)))
    }

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
