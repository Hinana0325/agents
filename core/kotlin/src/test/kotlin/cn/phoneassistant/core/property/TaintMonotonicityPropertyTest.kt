package cn.phoneassistant.core.property

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.ValidationContext
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DEVELOPMENT.md 第 10 节不变量 2：加 taint 或换成语音来源，级别只升不降。
 *
 * 对 protocol/vectors 中所有结构合法的信封语料，用仓库的策略重算入口
 * [EnvelopeValidator.computeLevel] 分别计算原始 origin、tainted=true、
 * origin.kind=user_voice 三种来源下的 level：
 * - tainted=true 后 level 必须不低于原 level（只升不降），且不超过上限 3；
 * - 换成语音来源（user_voice）后 level 同样不得降低。
 */
class TaintMonotonicityPropertyTest : FunSpec({

    val corpus = PropertyFixtures.loadValidEnvelopes()

    test("taint 与语音来源只升不降且上限 3（语料规模 ${corpus.size}）") {
        checkAll(1000, Arb.element(corpus)) { envelope ->
            val intent = PropertyFixtures.str(envelope["intent"])!!
            val params = envelope["params"] as JsonObject
            val origin = envelope["origin"] as JsonObject

            val base = EnvelopeValidator.computeLevel(intent, params, origin, ValidationContext())
            val taintedOrigin = PropertyFixtures.withTainted(envelope, true)["origin"] as JsonObject
            val tainted = EnvelopeValidator.computeLevel(intent, params, taintedOrigin, ValidationContext())
            val voiceOrigin = PropertyFixtures.withOriginKind(envelope, "user_voice")["origin"] as JsonObject
            val voice = EnvelopeValidator.computeLevel(intent, params, voiceOrigin, ValidationContext())

            // 不变量：taint 后级别只升不降，且策略上限为 3。
            withClue("intent=$intent origin=$origin base=$base tainted=$tainted") {
                (tainted >= base) shouldBe true
                (tainted <= 3) shouldBe true
                (base in 1..3) shouldBe true
            }

            // 不变量：换成语音来源（不引入 taint）级别不得降低。
            withClue("intent=$intent base=$base voice=$voice") {
                (voice >= base) shouldBe true
            }
        }
    }

    test("信封自带 level 低于 taint 后的重算值时，校验器必须拒绝（LEVEL_BELOW_POLICY）") {
        checkAll(500, Arb.element(corpus), Arb.int(1, 3)) { envelope, envelopeLevel ->
            val intent = PropertyFixtures.str(envelope["intent"])!!
            val params = envelope["params"] as JsonObject
            val taintedOrigin = PropertyFixtures.withTainted(envelope, true)["origin"] as JsonObject
            val computed = EnvelopeValidator.computeLevel(intent, params, taintedOrigin, ValidationContext())

            val downgraded = JsonObject(
                envelope.toMutableMap().apply {
                    put("origin", taintedOrigin)
                    put("level", JsonPrimitive(envelopeLevel))
                },
            )
            val result = EnvelopeValidator.validate(downgraded, ValidationContext())
            withClue("intent=$intent envelopeLevel=$envelopeLevel computed=$computed") {
                if (envelopeLevel < computed) {
                    result.ok shouldBe false
                    result.code shouldBe "LEVEL_BELOW_POLICY"
                } else {
                    result.ok shouldBe true
                    result.level shouldBe maxOf(envelopeLevel, computed)
                }
            }
        }
    }
})
