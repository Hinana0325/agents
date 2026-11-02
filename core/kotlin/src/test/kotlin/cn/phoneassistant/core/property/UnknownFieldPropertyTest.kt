package cn.phoneassistant.core.property

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.ValidationContext
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DEVELOPMENT.md 第 10 节不变量 1：接受则 schema 合法、意图在白名单、无未知字段。
 *
 * 设备端手写严格解码（ignoreUnknownKeys=false 语义）：对一份结构合法的信封，
 * 在任意一层（顶层信封 / params / origin / from / to）随机追加 1 个未知字段，
 * 校验器都必须以 UNKNOWN_FIELD 拒绝；未加字段的阳性对照必须通过。
 */
class UnknownFieldPropertyTest : FunSpec({

    val locations = listOf("envelope", "params", "origin", "from", "to")
    val arbValue = Arb.element(
        listOf(JsonPrimitive("串"), JsonPrimitive(42), JsonPrimitive(true), JsonPrimitive(-1)),
    )

    test("任意一层追加 1 个未知字段都必须被 UNKNOWN_FIELD 拒绝") {
        checkAll(
            500,
            Arb.element(locations),
            Arb.string(1..12, Codepoint.alphanumeric()),
            arbValue,
        ) { location, suffix, value ->
            val clean = validChatEnvelope()
            // 阳性对照：干净信封必须通过。
            EnvelopeValidator.validate(clean, ValidationContext()).ok shouldBe true

            val fieldName = "zz_${suffix}x" // zz_ 前缀保证不与任何白名单字段冲突
            val mutated = injectUnknownField(clean, location, fieldName, value)
            val result = EnvelopeValidator.validate(mutated, ValidationContext())
            withClue("location=$location field=$fieldName value=$value") {
                result.ok shouldBe false
                result.code shouldBe "UNKNOWN_FIELD"
            }
        }
    }

    test("未知字段注入到 params 时同样拒绝（对应 intents schema 的 additionalProperties=false）") {
        checkAll(500, Arb.string(1..12, Codepoint.alphanumeric())) { suffix ->
            val intent = "task_handoff"
            val clean = buildJsonObject {
                put("v", 1)
                put("id", "0193f0a8-9c1b-7001-8001-000000000001")
                put("ts", "2026-11-02T10:00:00Z")
                put("ttl_s", 300)
                putJsonObject("from") {
                    put("role", "hub")
                    put("kid", "hub-test-1")
                }
                putJsonObject("to") { put("device", "phone-1") }
                put("intent", intent)
                put("params", PropertyFixtures.MINIMAL_PARAMS.getValue(intent))
                putJsonObject("origin") {
                    put("kind", "user_text")
                    put("tainted", false)
                }
                put("level", 2)
                put("sig", "A".repeat(86))
            }
            EnvelopeValidator.validate(clean, ValidationContext()).ok shouldBe true

            // proposal 层也必须严格：未知字段藏在 proposal 里同样拒绝。
            val params = clean["params"] as JsonObject
            val proposal = params["proposal"] as JsonObject
            val poisoned = JsonObject(
                clean.toMutableMap().apply {
                    put(
                        "params",
                        JsonObject(
                            params.toMutableMap().apply {
                                put(
                                    "proposal",
                                    JsonObject(
                                        proposal.toMutableMap().apply { put("zz_${suffix}x", JsonPrimitive(1)) },
                                    ),
                                )
                            },
                        ),
                    )
                },
            )
            val result = EnvelopeValidator.validate(poisoned, ValidationContext())
            result.ok shouldBe false
            result.code shouldBe "UNKNOWN_FIELD"
        }
    }
})

/** 一份结构合法的 chat 信封（未签名占位 sig，默认上下文不验签）。 */
private fun validChatEnvelope(): JsonObject = buildJsonObject {
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
    putJsonObject("params") { put("text", "你好") }
    putJsonObject("origin") {
        put("kind", "user_text")
        put("tainted", false)
    }
    put("level", 1)
    put("sig", "A".repeat(86))
}

/** 把未知字段注入到指定层；location 决定改写哪一层。 */
private fun injectUnknownField(envelope: JsonObject, location: String, field: String, value: JsonElement): JsonObject {
    fun plus(obj: JsonObject, key: String, v: JsonElement) =
        JsonObject(obj.toMutableMap().apply { put(key, v) })

    return when (location) {
        "envelope" -> plus(envelope, field, value)
        "params" -> plus(envelope, "params", plus(envelope["params"] as JsonObject, field, value))
        "origin" -> plus(envelope, "origin", plus(envelope["origin"] as JsonObject, field, value))
        "from" -> plus(envelope, "from", plus(envelope["from"] as JsonObject, field, value))
        "to" -> plus(envelope, "to", plus(envelope["to"] as JsonObject, field, value))
        else -> error("unknown location $location")
    }
}
