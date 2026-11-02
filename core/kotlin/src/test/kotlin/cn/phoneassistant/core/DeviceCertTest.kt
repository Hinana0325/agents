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

class DeviceCertTest {

    private val now: Instant = Instant.parse("2026-11-02T10:00:00Z")
    private val phone = keyPair()
    private val pc = keyPair()

    private fun validCert(level: Int = 2, issuedAt: String = ts(now.minusSeconds(60)), expiresAt: String = ts(now.plusSeconds(86_400))) =
        DeviceCert.create(
            deviceId = "pc-1",
            role = "pc",
            maxLevel = level,
            pubkeyJwk = jwk(pc),
            issuedBy = "phone-1",
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            signingKey = phone.private,
        )

    @Test
    fun validCertificatePasses() {
        assertNull(DeviceCert.verify(validCert(), jwk(phone), now))
    }

    @Test
    fun tamperedCertificateFailsSignature() {
        val cert = validCert()
        val tampered = JsonObject(cert.toMutableMap().apply { put("max_level", JsonPrimitive(1)) })
        assertEquals("CERT_SIGNATURE", DeviceCert.verify(tampered, jwk(phone), now))
    }

    @Test
    fun pcCannotHaveLevelThree() {
        assertEquals("CERT_LEVEL", DeviceCert.verify(validCert(level = 3), jwk(phone), now))
    }

    @Test
    fun expiredCertificateIsRejected() {
        val cert = validCert(expiresAt = ts(now.minusSeconds(10)))
        assertEquals("CERT_EXPIRED", DeviceCert.verify(cert, jwk(phone), now))
    }

    @Test
    fun futureIssuedCertificateIsRejected() {
        val cert = validCert(issuedAt = ts(now.plusSeconds(600)))
        assertEquals("CERT_NOT_YET_VALID", DeviceCert.verify(cert, jwk(phone), now))
    }

    @Test
    fun wrongTrustRootIsRejected() {
        val cert = validCert()
        assertEquals("CERT_SIGNATURE", DeviceCert.verify(cert, jwk(pc), now))
    }

    @Test
    fun missingFieldIsRejected() {
        val cert = JsonObject(validCert().toMutableMap().apply { remove("issued_by") })
        assertEquals("CERT_INVALID", DeviceCert.verify(cert, jwk(phone), now))
    }

    private fun ts(instant: Instant): String =
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
