package cn.phoneassistant.core.localmodel

/**
 * 有序降级链路由器：管理本设备所有可用的意图分类器。
 *
 * 链按优先级从高到低排列（构造顺序即优先级）；典型的链形如
 * “端上小模型分类器 → 规则解析器”，未来接入 ONNX/LLM 分类器时
 * 插在规则解析器前面（见 [IntentClassifier] 的 TODO）。
 *
 * 降级语义：
 * - 链上某个分类器返回 null（认不出来）→ 尝试下一个。
 * - 链上某个分类器抛异常（模型损坏、加载失败等单点故障）→ 捕获并继续下一个，
 *   绝不让单点故障炸掉整条链（能力降级，开发文档第 7 节）。
 * - 全链无果 → 返回 null。null 表示“本地处理不了，交上层走云端大模型或向
 *   用户澄清”，是明确的返回值而不是静默失败。
 *
 * 产出仍是意图提案：确认级由策略引擎另行计算，本路由器不做任何定级或安全判断。
 *
 * TODO: 资源管理（模型加载/卸载、按 [ResourceBudget] 控制常驻内存）和按设备
 *   算力档位自动选择模型，在阶段 2“本地模型层完整实现”时补充。
 */
class LocalModelRouter(classifiers: List<IntentClassifier>) {

    private val chain = classifiers.toMutableList()

    init {
        require(chain.isNotEmpty()) { "降级链至少要有一个分类器，全空的链没有意义" }
    }

    /**
     * 把一个分类器追加到链尾（优先级最低）。
     *
     * 典型用途：运行期动态接入新的本地模型实现，规则解析器通常常驻链尾兜底。
     */
    fun register(classifier: IntentClassifier) {
        chain.add(classifier)
    }

    /**
     * 按优先级依次尝试链上分类器，第一个产出非 null 提案的胜出。
     *
     * @return 胜出分类器的结果；全链无果时返回 null（交上层走云端大模型或
     *   向用户澄清，绝不静默失败）。
     */
    fun classify(input: ClassifiedInput): ClassificationResult? {
        for (classifier in chain) {
            val result = try {
                classifier.classify(input)
            } catch (_: Exception) {
                // 能力降级：单点故障只损失一个分类器，不能炸掉整条链。
                continue
            }
            if (result?.proposal != null) return result
        }
        return null
    }
}
