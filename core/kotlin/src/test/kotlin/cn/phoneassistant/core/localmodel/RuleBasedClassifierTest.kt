package cn.phoneassistant.core.localmodel

import cn.phoneassistant.core.IntentType
import cn.phoneassistant.core.LocalIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleBasedClassifierTest {

    private val classifier = RuleBasedClassifier()

    private fun classify(text: String, tainted: Boolean = false): ClassificationResult? =
        classifier.classify(ClassifiedInput(text = text, originKind = "user_text", tainted = tainted))

    @Test
    fun timerUtteranceMatchesLocalIntentParser() {
        val result = classify("10分钟后提醒我")!!
        assertEquals(IntentType.SET_TIMER, result.proposal!!.type)
        assertEquals("600", result.proposal.params["seconds"])
        assertEquals(LocalIntentParser.parse("10分钟后提醒我"), result.proposal)
    }

    @Test
    fun alarmUtteranceMatchesLocalIntentParser() {
        val result = classify("七点半的闹钟")!!
        assertEquals(IntentType.SET_ALARM, result.proposal!!.type)
        assertEquals("7", result.proposal.params["hour"])
        assertEquals("30", result.proposal.params["minute"])
        assertEquals(LocalIntentParser.parse("七点半的闹钟"), result.proposal)
    }

    @Test
    fun noteUtteranceMatchesLocalIntentParser() {
        val result = classify("记一下：买牛奶")!!
        assertEquals(IntentType.SAVE_NOTE, result.proposal!!.type)
        assertEquals("买牛奶", result.proposal.params["content"])
        assertEquals(LocalIntentParser.parse("记一下：买牛奶"), result.proposal)
    }

    @Test
    fun openAppUtteranceMatchesLocalIntentParser() {
        val result = classify("打开微信")!!
        assertEquals(IntentType.OPEN_APP, result.proposal!!.type)
        assertEquals("微信", result.proposal.params["appName"])
        assertEquals(LocalIntentParser.parse("打开微信"), result.proposal)
    }

    @Test
    fun searchUtteranceMatchesLocalIntentParser() {
        val result = classify("搜索今天天气")!!
        assertEquals(IntentType.WEB_SEARCH, result.proposal!!.type)
        assertEquals("今天天气", result.proposal.params["query"])
        assertEquals(LocalIntentParser.parse("搜索今天天气"), result.proposal)
    }

    @Test
    fun unknownTextReturnsNull() {
        assertNull(classify("你好，今天怎么样"))
        assertNull(classify("   "))
    }

    @Test
    fun sourceIsFixedToLocalRule() {
        assertEquals("local_rule", classify("打开微信")!!.source)
    }

    @Test
    fun confidenceIsPlaceholderOne() {
        assertEquals(1.0, classify("打开微信")!!.confidence!!, 0.0)
    }

    @Test
    fun taintedInputIsPassedThroughToResult() {
        val tainted = classify("打开微信", tainted = true)!!
        val clean = classify("打开微信", tainted = false)!!
        assertEquals(true, tainted.tainted)
        assertEquals(false, clean.tainted)
        assertEquals(tainted.proposal, clean.proposal)
    }

    @Test
    fun everyOriginKindIsAccepted() {
        for (originKind in listOf("user_text", "user_voice", "scheduled", "derived")) {
            val input = ClassifiedInput(text = "打开微信", originKind = originKind, tainted = false)
            val result = classifier.classify(input)!!
            assertEquals("local_rule", result.source)
            assertEquals(IntentType.OPEN_APP, result.proposal!!.type)
        }
    }
}
