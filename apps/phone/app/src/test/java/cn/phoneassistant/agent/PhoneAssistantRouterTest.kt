package cn.phoneassistant.agent

import cn.phoneassistant.core.IntentType
import cn.phoneassistant.core.LocalIntentParser
import cn.phoneassistant.core.localmodel.ClassificationResult
import cn.phoneassistant.core.localmodel.ClassifiedInput
import cn.phoneassistant.core.localmodel.IntentClassifier
import cn.phoneassistant.core.localmodel.ResourceBudget
import cn.phoneassistant.core.localmodel.RuleBasedClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端本地模型路由组装点的测试：
 * 1) 组装正确性——默认链上只有规则兜底，高优先级分类器插在它前面；
 * 2) 行为等价性——经 router 的解析结果与直接调 LocalIntentParser 逐样本对拍。
 */
class PhoneAssistantRouterTest {

    private val router = PhoneAssistantRouter()

    private fun classify(text: String, tainted: Boolean = false): ClassificationResult? =
        router.classify(ClassifiedInput(text = text, originKind = "user_text", tainted = tainted))

    /** 等价性样本：全部取自现有测试（core LocalIntentParserTest / RuleBasedClassifierTest / 本 app 离线快路径）。 */
    private val equivalenceSamples = listOf(
        // 命中样本
        "10分钟后提醒我",
        "定个五分钟计时器",
        "1小时30分钟倒计时",
        "半小时后提醒我",
        "设个7:30的闹钟",
        "明天早上七点叫我起床",
        "七点半的闹钟",
        "下午3点提醒我",
        "记一下：买牛奶",
        "帮我记住 明天交报告",
        "记一下：搜索怎么用",
        "打开微信",
        "帮我打开一下相机吧",
        "搜索今天天气",
        // 不命中样本（期望 null 对 null）
        "25小时计时",
        "我跑了30分钟",
        "你好，今天怎么样",
        "   ",
    )

    @Test
    fun defaultChainIsRuleBasedClassifierOnly() {
        val result = classify("打开微信")!!
        assertEquals(RuleBasedClassifier.SOURCE, result.source)
        assertEquals(IntentType.OPEN_APP, result.proposal!!.type)
        assertEquals(LocalIntentParser.parse("打开微信"), result.proposal)
    }

    @Test
    fun routerProposalsMatchLocalIntentParserOnEverySample() {
        for (text in equivalenceSamples) {
            assertEquals("样本不一致：$text", LocalIntentParser.parse(text), router.parse(text))
        }
    }

    @Test
    fun unknownTextYieldsNullFromChain() {
        assertNull(router.parse("你好，今天怎么样"))
        assertNull(router.parse("   "))
    }

    @Test
    fun higherPriorityClassifierBeatsRuleFallback() {
        val stub = FixedClassifier(
            ClassificationResult(
                proposal = LocalIntentParser.parse("打开微信"),
                confidence = 0.9,
                source = "local_stub",
                tainted = false,
            ),
        )
        val router = PhoneAssistantRouter(highPriorityClassifiers = listOf(stub))

        val result = router.classify(ClassifiedInput("打开微信", "user_text", false))!!

        assertEquals("local_stub", result.source)
    }

    @Test
    fun highPriorityNullFallsThroughToRule() {
        val router = PhoneAssistantRouter(highPriorityClassifiers = listOf(FixedClassifier(null)))

        val result = router.classify(ClassifiedInput("打开微信", "user_text", false))!!

        assertEquals(RuleBasedClassifier.SOURCE, result.source)
    }

    @Test
    fun highPriorityFailureDegradesToRule() {
        val router = PhoneAssistantRouter(highPriorityClassifiers = listOf(BrokenClassifier()))

        val result = router.classify(ClassifiedInput("打开微信", "user_text", false))!!

        assertEquals(RuleBasedClassifier.SOURCE, result.source)
        assertEquals(LocalIntentParser.parse("打开微信"), result.proposal)
    }

    @Test
    fun taintedInputIsPassedThroughUntouched() {
        val tainted = classify("打开微信", tainted = true)!!
        val clean = classify("打开微信", tainted = false)!!

        assertEquals(true, tainted.tainted)
        assertEquals(false, clean.tainted)
        // 污点只透传不改变提案本身。
        assertEquals(clean.proposal, tainted.proposal)
    }

    @Test
    fun budgetIsDeclaredForPhoneTier() {
        assertTrue(router.budget.maxMemoryMb > 0)
        assertTrue(router.budget.tier in setOf("flagship", "mid", "entry"))
    }

    @Test
    fun allOriginKindsAcceptedAtAssemblyPoint() {
        for (originKind in listOf("user_text", "user_voice", "scheduled", "derived")) {
            val result = router.classify(ClassifiedInput("打开微信", originKind, false))!!
            assertEquals(RuleBasedClassifier.SOURCE, result.source)
        }
    }

    private class FixedClassifier(private val result: ClassificationResult?) : IntentClassifier {
        override fun classify(input: ClassifiedInput): ClassificationResult? = result
    }

    private class BrokenClassifier : IntentClassifier {
        override fun classify(input: ClassifiedInput): ClassificationResult? =
            throw IllegalStateException("模型损坏（模拟单点故障）")
    }
}
