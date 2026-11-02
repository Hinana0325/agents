package cn.phoneassistant.core.property

import cn.phoneassistant.core.Es256
import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.runtime.DeviceRuntime
import cn.phoneassistant.core.runtime.EnvelopeState
import cn.phoneassistant.core.runtime.ExecutionOutcome
import cn.phoneassistant.core.runtime.IntentExecutor
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.security.PrivateKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DEVELOPMENT.md 第 10 节不变量 5：同一 id 投递 N 次，副作用恰好 1 次。
 *
 * 参照 DeviceRuntimeTest 的基建构造带计数执行器的 DeviceRuntime：
 * 同一份已签名信封重复投递 N 次（N ∈ 2..10），执行器必须只被调用 1 次，
 * 且每次投递返回同一份回执（第二次起被幂等去重）。
 */
class IdempotencyPropertyTest : FunSpec({

    val hub = generateP256()

    test("同一信封 id 重复投递 N 次副作用恰好 1 次") {
        val ids = PropertyFixtures.IdGenerator()
        checkAll(500, Arb.int(2, 10), Arb.string(1..16, Codepoint.alphanumeric())) { n, noise ->
            val calls = mutableListOf<String>()
            val runtime = DeviceRuntime(
                deviceId = "phone-1",
                deviceRole = "phone",
                executor = IntentExecutor { intent, _ ->
                    calls += intent
                    ExecutionOutcome(true)
                },
                hubPublicKeys = mapOf("hub-test-1" to toJwk(hub)),
                clock = { java.time.Instant.parse("2026-11-02T10:00:30Z") },
            )
            val envelope = signedChatEnvelope(ids.next(), noise, hub.private)

            val receipts = (1..n).map { runtime.submit(envelope) }

            withClue("n=$n noise=$noise") {
                // 副作用恰好 1 次：执行器只被调用一次 chat。
                calls shouldBe listOf("chat")
                // 每次投递返回同一份回执。
                receipts.distinct().size shouldBe 1
                receipts.first()["status"]!!.jsonPrimitive.content shouldBe "done"
                runtime.stateOf(PropertyFixtures.str(envelope["id"])!!) shouldBe EnvelopeState.DONE
            }
        }
    }

    test("不同 id 的信封各自独立投递与去重") {
        val ids = PropertyFixtures.IdGenerator()
        checkAll(500, Arb.int(2, 5)) { copiesPerId ->
            val calls = mutableListOf<String>()
            val runtime = DeviceRuntime(
                deviceId = "phone-1",
                deviceRole = "phone",
                executor = IntentExecutor { intent, _ ->
                    calls += intent
                    ExecutionOutcome(true)
                },
                hubPublicKeys = mapOf("hub-test-1" to toJwk(hub)),
                clock = { java.time.Instant.parse("2026-11-02T10:00:30Z") },
            )

            // 两个不同 id 各投递 copiesPerId 次：副作用应为 2 次（每个 id 恰好 1 次）。
            val first = signedChatEnvelope(ids.next(), "one", hub.private)
            val second = signedChatEnvelope(ids.next(), "two", hub.private)
            (1..copiesPerId).forEach { runtime.submit(first) }
            (1..copiesPerId).forEach { runtime.submit(second) }

            withClue("copiesPerId=$copiesPerId") {
                calls.sorted() shouldBe listOf("chat", "chat")
            }
        }
    }
})

/** 构造一份真实签名（ES256 over JCS payload）的 chat 信封。 */
private fun signedChatEnvelope(id: String, text: String, privateKey: PrivateKey): JsonObject {
    val payload = buildJsonObject {
        put("v", 1)
        put("id", id)
        put("ts", "2026-11-02T10:00:00Z")
        put("ttl_s", 300)
        putJsonObject("from") {
            put("role", "hub")
            put("kid", "hub-test-1")
        }
        putJsonObject("to") { put("device", "phone-1") }
        put("intent", "chat")
        putJsonObject("params") { put("text", "幂等-$text") }
        putJsonObject("origin") {
            put("kind", "user_text")
            put("tainted", false)
        }
        put("level", 1)
    }
    val signed = Es256.signRaw(payload, privateKey)
    return JsonObject(payload.toMutableMap().apply { put("sig", JsonPrimitive(signed)) })
}
