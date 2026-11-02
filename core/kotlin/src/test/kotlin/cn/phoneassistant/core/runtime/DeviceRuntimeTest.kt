package cn.phoneassistant.core.runtime

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.Jcs
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRuntimeTest {

    private var clockNow: Instant = Instant.parse("2026-11-02T10:00:30Z")
    private val calls = mutableListOf<String>()

    private val hub = keyPair()
    private val phone = keyPair()
    private val watch = keyPair()

    private var idCounter = 0

    private fun runtime(): DeviceRuntime = DeviceRuntime(
        deviceId = "phone-1",
        deviceRole = "phone",
        executor = IntentExecutor { intent, _ ->
            calls += intent
            ExecutionOutcome(true)
        },
        hubPublicKeys = mapOf("hub-test-1" to jwk(hub)),
        approvers = mapOf(
            "phone-1" to TrustedApprover("phone-1", 3, jwk(phone)),
            "watch-1" to TrustedApprover("watch-1", 2, jwk(watch)),
        ),
        clock = { clockNow },
    )

    // ---------------------------------------------------------------- tests

    @Test
    fun l1ExecutesOnceAndDuplicateDeliveryIsIdempotent() {
        val rt = runtime()
        val env = signEnvelope(envelope("chat", buildJsonObject { put("text", "hi") }, level = 1))
        val id = idOf(env)

        val first = rt.submit(env)
        val second = rt.submit(env)
        val third = rt.submit(env)

        assertEquals("done", status(first))
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(listOf("chat"), calls)
        assertEquals(EnvelopeState.DONE, rt.stateOf(id))
    }

    @Test
    fun l2WaitsForApprovalAndDoesNotExecute() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        val receipt = rt.submit(env)

        assertEquals("awaiting_confirm", status(receipt))
        assertTrue(calls.isEmpty())
        assertEquals(EnvelopeState.AWAITING_CONFIRM, rt.stateOf(idOf(env)))
    }

    @Test
    fun approvalExecutesPendingEnvelope() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        rt.submit(env)

        val receipt = rt.approve(idOf(env), approval(env, "phone-1", 3, phone.private, nonce()))

        assertEquals("done", status(receipt))
        assertEquals(listOf("create_event"), calls)
    }

    @Test
    fun watchCannotApproveL3Envelope() {
        val rt = runtime()
        val env = signEnvelope(
            envelope(
                "create_event",
                buildJsonObject {
                    put("title", "x")
                    put("begin_ms", 1)
                    put("end_ms", 2)
                    put("attendees", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a@b.c"))))
                },
                level = 3,
            ),
        )
        rt.submit(env)

        val tooLow = rt.approve(idOf(env), approval(env, "watch-1", 2, watch.private, nonce()))
        assertEquals("APPROVAL_LEVEL", errorCode(tooLow))
        assertEquals(EnvelopeState.AWAITING_CONFIRM, rt.stateOf(idOf(env)))

        // 手表就算伪造 L3 级别，也超过它的权限上限。
        val overMax = rt.approve(idOf(env), approval(env, "watch-1", 3, watch.private, nonce()))
        assertEquals("APPROVAL_LEVEL", errorCode(overMax))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun l3RequiresTheSeparateApprovalKey() {
        val approvalKey = keyPair()
        val rt = DeviceRuntime(
            deviceId = "phone-1",
            deviceRole = "phone",
            executor = IntentExecutor { intent, _ ->
                calls += intent
                ExecutionOutcome(true)
            },
            hubPublicKeys = mapOf("hub-test-1" to jwk(hub)),
            approvers = mapOf(
                "phone-1" to TrustedApprover("phone-1", 2, jwk(phone)),
                "phone-1:approval" to TrustedApprover("phone-1:approval", 3, jwk(approvalKey)),
            ),
            clock = { clockNow },
        )
        val env = signEnvelope(
            envelope(
                "create_event",
                buildJsonObject {
                    put("title", "x")
                    put("begin_ms", 1)
                    put("end_ms", 2)
                    put(
                        "attendees",
                        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a@b.c"))),
                    )
                },
                level = 3,
            ),
        )
        rt.submit(env)

        val identityTry = rt.approve(idOf(env), approval(env, "phone-1", 3, phone.private, nonce()))
        assertEquals("APPROVAL_LEVEL", errorCode(identityTry))
        assertTrue(calls.isEmpty())

        val approvalTry = rt.approve(
            idOf(env), approval(env, "phone-1:approval", 3, approvalKey.private, nonce()),
        )
        assertEquals("done", status(approvalTry))
        assertEquals(listOf("create_event"), calls)
    }

    @Test
    fun approvalBoundToAnotherEnvelopeIsRejected() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        val other = signEnvelope(eventEnvelope(level = 2))
        rt.submit(env)

        val receipt = rt.approve(idOf(env), approval(other, "phone-1", 3, phone.private, nonce()))

        assertEquals("APPROVAL_HASH", errorCode(receipt))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun tamperedApprovalFailsSignature() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        rt.submit(env)

        val valid = approval(env, "phone-1", 3, phone.private, nonce())
        val tampered = JsonObject(valid.toMutableMap().apply { put("nonce", JsonPrimitive("X".repeat(24))) })
        val receipt = rt.approve(idOf(env), tampered)

        assertEquals("APPROVAL_SIGNATURE", errorCode(receipt))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun approvalNonceCannotBeReplayed() {
        val rt = runtime()
        val first = signEnvelope(eventEnvelope(level = 2))
        val second = signEnvelope(eventEnvelope(level = 2))
        rt.submit(first)
        rt.submit(second)

        val shared = nonce()
        assertEquals("done", status(rt.approve(idOf(first), approval(first, "phone-1", 3, phone.private, shared))))
        val replay = rt.approve(idOf(second), approval(second, "phone-1", 3, phone.private, shared))

        assertEquals("APPROVAL_REPLAY", errorCode(replay))
        assertEquals(listOf("create_event"), calls)
    }

    @Test
    fun expiredEnvelopeIsRejected() {
        val rt = runtime()
        val env = signEnvelope(envelope("chat", buildJsonObject { put("text", "hi") }, level = 1, ttl = 10))

        val receipt = rt.submit(env)

        assertEquals("EXPIRED", errorCode(receipt))
        assertEquals(null, rt.stateOf(idOf(env)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun voiceCommandIsNotExecuted() {
        val rt = runtime()
        val env = signEnvelope(
            envelope("voice_command", buildJsonObject { put("transcript", "明天早上七点叫我") }, level = 2),
        )

        val receipt = rt.submit(env)

        assertEquals("VOICE_NOT_EXECUTABLE", errorCode(receipt))
        assertEquals(EnvelopeState.REJECTED, rt.stateOf(idOf(env)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun tamperedEnvelopeFailsSignature() {
        val rt = runtime()
        val signed = signEnvelope(envelope("chat", buildJsonObject { put("text", "hi") }, level = 1))
        val tampered = JsonObject(
            signed.toMutableMap().apply {
                put("params", buildJsonObject { put("text", "changed") })
            },
        )

        val receipt = rt.submit(tampered)

        assertEquals("BAD_SIG", errorCode(receipt))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun levelBelowPolicyIsRejected() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 1))

        assertEquals("LEVEL_BELOW_POLICY", errorCode(rt.submit(env)))
    }

    @Test
    fun rejectMarksEnvelopeRejectedAndBlocksLateApproval() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        rt.submit(env)

        assertEquals("rejected", status(rt.reject(idOf(env))))
        assertEquals(EnvelopeState.REJECTED, rt.stateOf(idOf(env)))
        assertEquals("NOT_AWAITING_CONFIRM", errorCode(rt.approve(idOf(env), approval(env, "phone-1", 3, phone.private, nonce()))))
    }

    @Test
    fun sweepMarksPendingEnvelopeExpired() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))
        rt.submit(env)

        clockNow = Instant.parse("2026-11-02T10:10:00Z")
        val expired = rt.sweepExpired()

        assertEquals(listOf(idOf(env)), expired)
        assertEquals(EnvelopeState.EXPIRED, rt.stateOf(idOf(env)))
        assertEquals("expired", status(rt.receiptOf(idOf(env))!!))
    }

    @Test
    fun approvingUnknownEnvelopeIsRejected() {
        val rt = runtime()
        val env = signEnvelope(eventEnvelope(level = 2))

        assertEquals("UNKNOWN_ENVELOPE", errorCode(rt.approve(idOf(env), approval(env, "phone-1", 3, phone.private, nonce()))))
    }

    // -------------------------------------------------------------- helpers

    private fun eventEnvelope(level: Int): JsonObject = envelope(
        "create_event",
        buildJsonObject {
            put("title", "评审")
            put("begin_ms", 1762081200000L)
            put("end_ms", 1762084800000L)
        },
        level = level,
    )

    private fun envelope(intent: String, params: JsonObject, level: Int, ttl: Long = 300): JsonObject {
        idCounter++
        val id = "0193f0a8-9c1b-7%03x-8%03x-%012x".format(idCounter, idCounter, idCounter)
        return buildJsonObject {
            put("v", 1)
            put("id", id)
            put("ts", "2026-11-02T10:00:00Z")
            put("ttl_s", ttl)
            putJsonObject("from") {
                put("role", "hub")
                put("kid", "hub-test-1")
            }
            putJsonObject("to") { put("device", "phone-1") }
            put("intent", intent)
            put("params", params)
            putJsonObject("origin") {
                put("kind", "user_text")
                put("tainted", false)
            }
            put("level", level)
            put("sig", "A".repeat(86))
        }
    }

    private fun signEnvelope(envelope: JsonObject): JsonObject {
        val payload = JsonObject(envelope.filterKeys { it != "sig" })
        return JsonObject(envelope.toMutableMap().apply { put("sig", JsonPrimitive(sign(payload, hub.private))) })
    }

    private fun approval(
        envelope: JsonObject,
        deviceId: String,
        level: Int,
        key: PrivateKey,
        nonce: String,
    ): JsonObject {
        val payload = buildJsonObject {
            put("v", 1)
            put("envelope_hash", EnvelopeValidator.envelopeHash(envelope))
            put("decision", "approve")
            put("level", level)
            put("approver_device", deviceId)
            put("ts", "2026-11-02T10:00:30Z")
            put("nonce", nonce)
        }
        return JsonObject(payload.toMutableMap().apply { put("sig", JsonPrimitive(sign(payload, key))) })
    }

    private fun sign(payload: JsonObject, key: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(key)
        signer.update(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
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

    private fun nonce(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("nonce-" + idCounter + "-" + System.nanoTime()).toByteArray()).take(24)

    private fun idOf(envelope: JsonObject): String = envelope["id"]!!.jsonPrimitive.content

    private fun status(receipt: JsonObject): String = receipt["status"]!!.jsonPrimitive.content

    private fun errorCode(receipt: JsonObject): String? =
        (receipt["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
}
