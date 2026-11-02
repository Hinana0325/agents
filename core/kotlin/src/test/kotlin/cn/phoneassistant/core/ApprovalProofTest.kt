package cn.phoneassistant.core

import cn.phoneassistant.core.runtime.ApprovalValidator
import cn.phoneassistant.core.runtime.TrustedApprover
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalProofTest {

    private val now: Instant = Instant.parse("2026-11-02T10:00:00Z")
    private val phone = keyPair()

    private val envelope: JsonObject = buildJsonObject {
        put("v", 1)
        put("id", "0193f0a8-9c1b-7000-8000-000000000001")
        put("intent", "save_note")
        put("sig", "A".repeat(86))
    }

    private fun approvers() = mapOf("phone-1" to TrustedApprover("phone-1", 3, jwk(phone)))

    @Test
    fun createdProofVerifies() {
        val proof = ApprovalProof.create(envelope, 2, "phone-1", "nonce-aaaaaaaaaaaaaaaa", now, phone.private)

        val error = ApprovalValidator.verify(proof, envelope, 2, approvers(), emptySet(), now, 120)

        assertNull(error)
    }

    @Test
    fun tamperedProofFailsSignature() {
        val proof = ApprovalProof.create(envelope, 2, "phone-1", "nonce-bbbbbbbbbbbbbbbb", now, phone.private)
        val tampered = JsonObject(proof.toMutableMap().apply { put("level", JsonPrimitive(1)) })

        assertEquals("APPROVAL_SIGNATURE", ApprovalValidator.verify(tampered, envelope, 1, approvers(), emptySet(), now, 120))
    }

    @Test
    fun proofForAnotherEnvelopeIsRejected() {
        val other = buildJsonObject {
            put("v", 1)
            put("id", "0193f0a8-9c1b-7000-8000-000000000002")
            put("intent", "chat")
            put("sig", "B".repeat(86))
        }
        val proof = ApprovalProof.create(envelope, 2, "phone-1", "nonce-cccccccccccccccc", now, phone.private)

        assertEquals("APPROVAL_HASH", ApprovalValidator.verify(proof, other, 2, approvers(), emptySet(), now, 120))
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
