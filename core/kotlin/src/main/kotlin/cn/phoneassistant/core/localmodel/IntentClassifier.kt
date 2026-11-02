package cn.phoneassistant.core.localmodel

import cn.phoneassistant.core.AssistantIntent

/**
 * 待分类的输入。
 *
 * @property text 原始文本（用户输入或语音转写结果）。
 * @property originKind 输入来源，取值对齐协议信封的 origin.kind：
 *   user_text | user_voice | scheduled | derived。
 * @property tainted 是否被污点传播标记（本轮上下文含不可信内容时为 true，
 *   见开发文档第 2 节污点传播规则）。
 */
data class ClassifiedInput(
    val text: String,
    val originKind: String,
    val tainted: Boolean,
)

/**
 * 一次分类的产出。注意这是“意图提案”，不是可执行的信封：
 * 确认级由策略引擎另行计算，分类器无权定级，也不碰元数据
 * （硬规则见开发文档第 2 节）。
 *
 * @property proposal 识别出的意图提案；认不出来时为 null。
 * @property confidence 分类器自报的置信度；规则引擎没有概率概念，可为 null。
 * @property source 产出该提案的分类器标识（如 "local_rule"），供审计与路由决策使用。
 * @property tainted 输入污点标记的原样透传：tainted 输入产出的提案必须带上污点
 *   语义，由上层（策略引擎）据此加级；分类器本身不修改该值。
 */
data class ClassificationResult(
    val proposal: AssistantIntent?,
    val confidence: Double?,
    val source: String,
    val tainted: Boolean = false,
)

/**
 * 本地模型层的统一分类接口。
 *
 * 实现方只做一件事：把 [ClassifiedInput] 变成 [ClassificationResult]（意图提案），
 * 或者返回 null 表示“我认不出来”。实现方不得执行任何副作用，不得触碰元数据，
 * 不得决定确认级；产出与本地模型/云端大模型一样只是“建议”，必须经过
 * 统一安全策略复核（开发文档第 2、7 节）。
 *
 * TODO: 未来接入 ONNX 端上分类模型或小参数量 LLM 时，实现本接口并注册进
 *  [LocalModelRouter] 的降级链，模型选择与供应商由配置决定，不要写死在代码里。
 */
interface IntentClassifier {
    /**
     * 对输入做意图分类。
     *
     * @return 携带意图提案的结果；认不出来时返回 null（不是失败，只是让位给链上
     *   其他分类器）。
     */
    fun classify(input: ClassifiedInput): ClassificationResult?
}
