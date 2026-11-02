package cn.phoneassistant.core.localmodel

import cn.phoneassistant.core.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelRouterTest {

    /** 测试用的假分类器：按预设脚本返回结果或抛异常。 */
    private class FakeClassifier(
        private val source: String,
        private val behavior: (ClassifiedInput) -> ClassificationResult?,
    ) : IntentClassifier {
        override fun classify(input: ClassifiedInput): ClassificationResult? = behavior(input)
    }

    private fun hit(source: String): ClassificationResult =
        ClassificationResult(
            proposal = cn.phoneassistant.core.AssistantIntent(
                IntentType.CHAT_ONLY, emptyMap(), reply = null,
            ),
            confidence = 0.5,
            source = source,
        )

    private fun input() = ClassifiedInput(text = "打开微信", originKind = "user_text", tainted = false)

    @Test
    fun firstAvailableClassifierWins() {
        val first = FakeClassifier("first") { hit("first") }
        val second = FakeClassifier("second") { hit("second") }
        val router = LocalModelRouter(listOf(first, second))

        val result = router.classify(input())!!

        assertEquals("first", result.source)
    }

    @Test
    fun lowerPriorityWinsWhenHigherReturnsNoProposal() {
        val first = FakeClassifier("first") { null }
        val second = FakeClassifier("second") { hit("second") }
        val router = LocalModelRouter(listOf(first, second))

        val result = router.classify(input())!!

        assertEquals("second", result.source)
    }

    @Test
    fun lowerPriorityWinsWhenHigherReturnsNullProposal() {
        val first = FakeClassifier("first") {
            ClassificationResult(proposal = null, confidence = null, source = "first")
        }
        val second = FakeClassifier("second") { hit("second") }
        val router = LocalModelRouter(listOf(first, second))

        val result = router.classify(input())!!

        assertEquals("second", result.source)
    }

    @Test
    fun degradedToNextWhenHigherThrows() {
        val first = FakeClassifier("first") { throw IllegalStateException("模型加载失败") }
        val second = FakeClassifier("second") { hit("second") }
        val router = LocalModelRouter(listOf(first, second))

        val result = router.classify(input())!!

        assertEquals("second", result.source)
    }

    @Test
    fun degradedToEndOfChainWhenAllBeforeThrow() {
        val broken = FakeClassifier("broken") { throw IllegalStateException("boom") }
        val last = FakeClassifier("last") { hit("last") }
        val router = LocalModelRouter(listOf(broken, broken, broken, last))

        assertEquals("last", router.classify(input())!!.source)
    }

    @Test
    fun returnsNullWhenWholeChainHasNoResult() {
        val first = FakeClassifier("first") { null }
        val second = FakeClassifier("second") { throw IllegalStateException("boom") }
        val router = LocalModelRouter(listOf(first, second))

        assertNull(router.classify(input()))
    }

    @Test
    fun registeredClassifierIsAppendedToChainTail() {
        val head = FakeClassifier("head") { null }
        val router = LocalModelRouter(listOf(head))
        router.register(FakeClassifier("tail") { hit("tail") })

        assertEquals("tail", router.classify(input())!!.source)
    }

    @Test
    fun registeredClassifierDoesNotOverrideHeadPriority() {
        val head = FakeClassifier("head") { hit("head") }
        val router = LocalModelRouter(listOf(head))
        router.register(FakeClassifier("tail") { hit("tail") })

        assertEquals("head", router.classify(input())!!.source)
    }

    @Test
    fun emptyChainIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelRouter(emptyList())
        }
    }

    @Test
    fun taintedInputReachesClassifierUntouched() {
        var seen: ClassifiedInput? = null
        val spy = FakeClassifier("spy") {
            seen = it
            hit("spy")
        }
        LocalModelRouter(listOf(spy)).classify(
            ClassifiedInput(text = "帮我打开相机", originKind = "user_voice", tainted = true),
        )

        assertEquals("帮我打开相机", seen!!.text)
        assertEquals("user_voice", seen!!.originKind)
        assertEquals(true, seen!!.tainted)
    }
}
