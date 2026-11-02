package cn.phoneassistant.agent

import cn.phoneassistant.core.AssistantIntent
import cn.phoneassistant.core.localmodel.ClassificationResult
import cn.phoneassistant.core.localmodel.ClassifiedInput
import cn.phoneassistant.core.localmodel.IntentClassifier
import cn.phoneassistant.core.localmodel.LocalModelRouter
import cn.phoneassistant.core.localmodel.ResourceBudget
import cn.phoneassistant.core.localmodel.RuleBasedClassifier

/**
 * 手机端本地模型路由组装点：把 core 的 [LocalModelRouter] 按手机档位装起来，
 * 是手机上所有“本地意图分类”的唯一入口。
 *
 * 降级链组装（优先级从高到低，链头在前）：
 * - [highPriorityClassifiers]：链头预留位。未来接入端上 ONNX 分类模型或小参数量
 *   LLM 时，实现 [IntentClassifier] 后从这里传入，插在规则解析器前面优先命中。
 *   TODO: ONNX/LLM 本地分类器尚未接入——大模型服务商与模型选型是未定事项
 *   （DEVELOPMENT.md 第 11 节），不要写死服务商或模型名，落地时由配置决定。
 * - [RuleBasedClassifier]：当前默认链上唯一的成员（也是链头，等更高优先级分类器
 *   接入后退居链尾兜底）。包装 core 的 LocalIntentParser，覆盖计时、闹钟、备忘、
 *   打开应用、搜索五类口令，认不出返回 null。
 *
 * 硬边界（DEVELOPMENT.md 第 2 节）：
 * - 产出只是“意图提案”：不碰元数据、不定确认级；确认级由策略引擎另行计算，
 *   设备独立复核。分类结果里 [ClassificationResult.tainted] 是输入污点标记的
 *   原样透传，由上层据此加级。
 * - 全链无果返回 null：表示“本地处理不了，交上层走云端大模型或向用户澄清”，
 *   不是静默失败。
 *
 * @param highPriorityClassifiers 链头预留位：优先级高于规则兜底的本地分类器。
 * @param budget 本层模型的常驻内存预算（MB）与手机算力档位；当前只是声明，
 *   模型加载/卸载等资源管理在阶段 2“本地模型层完整实现”时补充
 *   （DEVELOPMENT.md 第 7 节）。
 */
class PhoneAssistantRouter(
    highPriorityClassifiers: List<IntentClassifier> = emptyList(),
    val budget: ResourceBudget = DEFAULT_BUDGET,
) {

    private val router = LocalModelRouter(highPriorityClassifiers + RuleBasedClassifier())

    /**
     * 对带来源标记的输入做本地意图分类，返回第一个命中的分类器结果；
     * 全链无果返回 null。
     */
    fun classify(input: ClassifiedInput): ClassificationResult? = router.classify(input)

    /**
     * 便捷入口：把聊天输入文本交给本地降级链，产出意图提案或 null。
     * 行为与直接调 LocalIntentParser.parse 完全等价（有对拍测试保证），
     * 聊天路径（ChatViewModel 的离线兜底）应经由这里而不是直调 parser。
     */
    fun parse(text: String): AssistantIntent? =
        router.classify(
            ClassifiedInput(text = text, originKind = ORIGIN_USER_TEXT, tainted = false),
        )?.proposal

    companion object {
        /** 聊天输入文本的来源标记，对齐协议信封的 origin.kind。 */
        const val ORIGIN_USER_TEXT = "user_text"

        /**
         * 手机档位默认预算：中端机（1B-3B 参数）留 512MB 常驻内存。
         * 占位声明值；真机按实际内存与模型大小调整，资源管理在阶段 2 补充。
         */
        val DEFAULT_BUDGET = ResourceBudget(maxMemoryMb = 512, tier = "mid")
    }
}
