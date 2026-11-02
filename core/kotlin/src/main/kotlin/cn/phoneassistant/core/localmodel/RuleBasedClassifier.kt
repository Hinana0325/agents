package cn.phoneassistant.core.localmodel

import cn.phoneassistant.core.LocalIntentParser

/**
 * 规则解析适配器：把现有 [LocalIntentParser]（离线规则快路径）包装成
 * 本地模型层的分类器，作为降级链的常驻兜底。
 *
 * 识别范围与 [LocalIntentParser] 一致：计时、闹钟、备忘、打开应用、搜索；
 * 认不出的文本返回 null，交给链上下一个分类器或上层。
 *
 * confidence 恒为 1.0，这是占位语义：规则引擎是确定性匹配，没有概率概念，
 * 用 1.0 表示“规则命中即唯一来源”，不要把它当真实置信度用。
 */
class RuleBasedClassifier : IntentClassifier {

    override fun classify(input: ClassifiedInput): ClassificationResult? {
        val proposal = LocalIntentParser.parse(input.text) ?: return null
        return ClassificationResult(
            proposal = proposal,
            confidence = CONFIDENCE_PLACEHOLDER,
            source = SOURCE,
            tainted = input.tainted,
        )
    }

    companion object {
        /** 分类器标识，写进 [ClassificationResult.source] 供审计与路由决策使用。 */
        const val SOURCE = "local_rule"

        /** 规则命中的固定置信度占位值，无概率含义，见类 KDoc。 */
        const val CONFIDENCE_PLACEHOLDER = 1.0
    }
}
