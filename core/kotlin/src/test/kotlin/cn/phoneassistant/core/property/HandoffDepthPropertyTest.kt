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
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DEVELOPMENT.md 第 10 节不变量 4：handoff 提案深度不超过 1。
 *
 * task_handoff 的 params.proposal 里不得再嵌套 task_handoff / voice_command
 * （否则形成链式转交）；对全部 9 个意图做性质检查：
 * - 提案意图为 task_handoff / voice_command 时必须以 HANDOFF_DEPTH 拒绝；
 * - 提案意图为其余 7 个白名单意图且 params 合法时必须接受（深度恰好 1）。
 */
class HandoffDepthPropertyTest : FunSpec({

    val allIntents = listOf(
        "open_app", "set_alarm", "create_event", "set_timer",
        "web_search", "save_note", "chat", "task_handoff", "voice_command",
    )
    val allowedProposals = setOf(
        "open_app", "set_alarm", "create_event", "set_timer", "web_search", "save_note", "chat",
    )

    test("提案意图为 task_handoff/voice_command 必须拒绝，其余白名单意图必须接受") {
        checkAll(500, Arb.element(allIntents), Arb.int(1, 3)) { proposalIntent, proposalLevel ->
            // 外层 level 取 max(2, proposal.level)，保证不被 LEVEL_BELOW_POLICY 干扰本性质。
            val outerLevel = maxOf(2, proposalLevel)
            val envelope = buildJsonObject {
                put("v", 1)
                put("id", "0193f0a8-9c1b-7001-8001-000000000001")
                put("ts", "2026-11-02T10:00:00Z")
                put("ttl_s", 300)
                putJsonObject("from") {
                    put("role", "hub")
                    put("kid", "hub-test-1")
                }
                putJsonObject("to") { put("device", "phone-1") }
                put("intent", "task_handoff")
                putJsonObject("params") {
                    put("target_device_id", "pc-1")
                    putJsonObject("proposal") {
                        put("intent", proposalIntent)
                        put("params", PropertyFixtures.MINIMAL_PARAMS.getValue(proposalIntent))
                        put("level", proposalLevel)
                    }
                }
                putJsonObject("origin") {
                    put("kind", "user_text")
                    put("tainted", false)
                }
                put("level", outerLevel)
                put("sig", "A".repeat(86))
            }

            val result = EnvelopeValidator.validate(envelope, ValidationContext())
            withClue("proposal intent=$proposalIntent level=$proposalLevel") {
                if (proposalIntent in allowedProposals) {
                    result.ok shouldBe true
                    // 深度 1 的合法提案：重算级别 = max(2, proposal.level)。
                    result.level shouldBe maxOf(2, proposalLevel)
                } else {
                    result.ok shouldBe false
                    result.code shouldBe "HANDOFF_DEPTH"
                }
            }
        }
    }

    test("提案意图不在白名单时以 UNKNOWN_INTENT 拒绝（防绕过深度检查）") {
        checkAll(500, Arb.string(1..20, Codepoint.alphanumeric())) { bogus ->
            // 追加 'x' 保证一定不在 9 个意图白名单内。
            val unknownIntent = "${bogus}x"
            val envelope = buildJsonObject {
                put("v", 1)
                put("id", "0193f0a8-9c1b-7001-8001-000000000001")
                put("ts", "2026-11-02T10:00:00Z")
                put("ttl_s", 300)
                putJsonObject("from") {
                    put("role", "hub")
                    put("kid", "hub-test-1")
                }
                putJsonObject("to") { put("device", "phone-1") }
                put("intent", "task_handoff")
                putJsonObject("params") {
                    put("target_device_id", "pc-1")
                    putJsonObject("proposal") {
                        put("intent", unknownIntent)
                        put("params", PropertyFixtures.MINIMAL_PARAMS.getValue("chat"))
                        put("level", 1)
                    }
                }
                putJsonObject("origin") {
                    put("kind", "user_text")
                    put("tainted", false)
                }
                put("level", 3)
                put("sig", "A".repeat(86))
            }
            val result = EnvelopeValidator.validate(envelope, ValidationContext())
            withClue("bogus proposal intent=$unknownIntent") {
                result.ok shouldBe false
                result.code shouldBe "UNKNOWN_INTENT"
            }
        }
    }
})
