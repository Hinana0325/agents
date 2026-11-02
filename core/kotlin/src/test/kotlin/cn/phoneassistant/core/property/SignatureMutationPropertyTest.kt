package cn.phoneassistant.core.property

import cn.phoneassistant.core.Es256
import cn.phoneassistant.core.EnvelopeValidator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DEVELOPMENT.md 第 10 节不变量 3：任何一位变异则验签失败。
 *
 * 自生成 P-256 密钥对，对随机 payload 做 ES256 签名（JCS 序列化字节）：
 * - 阳性对照：未变异的信封必须验签通过；
 * - 对签名字节翻转 1 个比特 / 异或 1 个字节、对 payload 的字符串/整数做
 *   单点变异（必然改变 JCS 规范化字节），验签都必须失败。
 */
class SignatureMutationPropertyTest : FunSpec({

    val keyPair = generateP256()
    val jwk = toJwk(keyPair)

    test("对 JCS 字节的任意单点变异都会导致验签失败") {
        val mutationKinds = listOf("sig_bit_flip", "sig_byte_xor", "text_append", "level_change", "ts_append")
        checkAll(
            500,
            Arb.element(mutationKinds),
            Arb.int(0, 511),
            Arb.string(1..12, Codepoint.alphanumeric()),
            Arb.boolean(),
        ) { kind, bitIndex, noise, withTrace ->
            val payload = buildPayload(noise, withTrace)
            val signed = signEnvelope(payload, keyPair.private)

            // 阳性对照：未变异的信封必须验签通过。
            withClue("未变异信封验签应当通过 kind=$kind payload=$payload") {
                EnvelopeValidator.verifyEs256(signed, jwk) shouldBe true
            }

            val mutated = when (kind) {
                // 签名 raw r||s 字节中翻转 1 个比特。
                "sig_bit_flip" -> {
                    val sig = PropertyFixtures.str(signed["sig"])!!
                    val raw = Base64.getUrlDecoder().decode(sig).also { it[bitIndex / 8] = (it[bitIndex / 8].toInt() xor (1 shl (bitIndex % 8))).toByte() }
                    replaceSig(signed, encodeSig(raw))
                }
                // 签名 raw 字节中异或 1 个字节。
                "sig_byte_xor" -> {
                    val sig = PropertyFixtures.str(signed["sig"])!!
                    val raw = Base64.getUrlDecoder().decode(sig).also { it[bitIndex % 64] = (it[bitIndex % 64].toInt() xor 0x5A).toByte() }
                    replaceSig(signed, encodeSig(raw))
                }
                // params.text 追加 1 个字符（必然改变 JCS 字节）。
                "text_append" -> {
                    val params = signed["params"] as JsonObject
                    val newText = PropertyFixtures.str(params["text"])!! + "x"
                    JsonObject(
                        signed.toMutableMap().apply {
                            put("params", JsonObject(params.toMutableMap().apply { put("text", JsonPrimitive(newText)) }))
                        },
                    )
                }
                // level 1 -> 2（必然改变 JCS 字节）。
                "level_change" -> JsonObject(signed.toMutableMap().apply { put("level", JsonPrimitive(2)) })
                // ts 追加 1 个字符（必然改变 JCS 字节）。
                "ts_append" -> JsonObject(signed.toMutableMap().apply { put("ts", JsonPrimitive(PropertyFixtures.str(signed["ts"])!! + "0")) })
                else -> error("unknown mutation kind $kind")
            }

            withClue("kind=$kind 变异后验签必须失败 payload=$payload") {
                EnvelopeValidator.verifyEs256(mutated, jwk) shouldBe false
            }
        }
    }

    test("随机 64 字节假签名必须验签失败") {
        checkAll(500, Arb.string(6..30, Codepoint.alphanumeric())) { noise ->
            val payload = buildPayload(noise, withTrace = false)
            val signed = signEnvelope(payload, keyPair.private)
            EnvelopeValidator.verifyEs256(signed, jwk) shouldBe true

            val raw = ByteArray(64)
            kotlin.random.Random(noise.hashCode().toLong()).nextBytes(raw)
            val forged = replaceSig(signed, encodeSig(raw))
            EnvelopeValidator.verifyEs256(forged, jwk) shouldBe false
        }
    }
})

/** 构造一份随机的、结构合法的 chat 信封 payload（不含 sig）。 */
private fun buildPayload(noise: String, withTrace: Boolean): JsonObject = buildJsonObject {
    put("v", 1)
    put("id", "0193f0a8-9c1b-7001-8001-000000000001")
    put("ts", "2026-11-02T10:00:00Z")
    put("ttl_s", 300)
    putJsonObject("from") {
        put("role", "hub")
        put("kid", "hub-test-1")
    }
    putJsonObject("to") { put("device", "phone-1") }
    put("intent", "chat")
    putJsonObject("params") { put("text", "属性测试-$noise") }
    putJsonObject("origin") {
        put("kind", "user_text")
        put("tainted", false)
    }
    put("level", 1)
    if (withTrace) put("trace", "trace-$noise")
}

private fun signEnvelope(payload: JsonObject, privateKey: java.security.PrivateKey): JsonObject =
    JsonObject(payload.toMutableMap().apply { put("sig", JsonPrimitive(Es256.signRaw(payload, privateKey))) })

private fun replaceSig(envelope: JsonObject, sig: String): JsonObject =
    JsonObject(envelope.toMutableMap().apply { put("sig", JsonPrimitive(sig)) })

private fun encodeSig(raw: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

fun generateP256(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

fun toJwk(pair: KeyPair): JsonObject {
    val publicKey = pair.public as ECPublicKey
    fun b64(value: BigInteger): String {
        val bytes = value.toByteArray()
        val fixed = when {
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> bytes
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed)
    }
    return buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", b64(publicKey.w.affineX))
        put("y", b64(publicKey.w.affineY))
    }
}
