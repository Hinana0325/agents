# 本地模型层：接线说明

对应开发文档（DEVELOPMENT.md）第 7 节"本地小模型：独立能力层"的骨架实现。
阶段 1 交付物"本地模型初步接入（意图分类）"落在这一层。

## 代码位置

```
core/kotlin/src/main/kotlin/cn/phoneassistant/core/localmodel/
├── IntentClassifier.kt    # 分类接口 + ClassifiedInput / ClassificationResult
├── ResourceBudget.kt      # 设备算力档位与内存预算
├── LocalModelRouter.kt    # 有序降级链路由器
└── RuleBasedClassifier.kt # 规则解析适配器（包装 LocalIntentParser）
```

纯 JVM、纯 Kotlin 标准库，手机/手表/电脑三端共用（core 不依赖 Android）。

## 设计

本层只做一件事：把带来源标记的输入变成**意图提案**，不执行任何副作用。

- **接口**：`IntentClassifier.classify(ClassifiedInput): ClassificationResult?`。
  输入携带 `text`、`originKind`（对齐协议 `origin.kind`：user_text / user_voice /
  scheduled / derived）、`tainted`。输出携带 `proposal`（复用 core 的
  `AssistantIntent`）、`confidence`、`source`（分类器标识，供审计）和透传的 `tainted`。
- **路由**：`LocalModelRouter` 持有一条按优先级从高到低排列的分类器降级链。
  第一个产出非 null 提案的胜出；单个分类器抛异常被捕获并继续下一个（单点故障
  不炸整条链）；全链无果返回 null——null 表示"本地处理不了，交上层走云端大模型
  或向用户澄清"，是明确结果，不是静默失败。`register()` 可在运行期向链尾追加
  分类器。
- **预算**：`ResourceBudget(maxMemoryMb, tier)` 声明本层模型的常驻内存上限与设备
  算力档位。背景是 Android 17 起按设备总内存限制应用内存上限，端上 KWS/ASR/
  分类模型的常驻内存必须纳入预算并压测（DEVELOPMENT.md 第 6 节）。
- **降级**：本地（模型/规则）认不出来或全部故障时，上层收到 null 后转云端大模型
  或向用户澄清，与第 7 节"能力降级"一致。

## 设备接入

算力分档取值（`ResourceBudget.tier`）对齐 DEVELOPMENT.md 第 7 节的六档：
手机 `flagship` / `mid` / `entry`（3B-7B / 1B-3B / 0.5B-1B），
电脑 `pc_high` / `pc_mid` / `pc_entry`（7B-14B / 3B-7B / 1B-3B）。

- **手机端**（apps/phone）：构建一个 `LocalModelRouter`，链上先放未来的端上
  ONNX 分类模型（TODO，未接入），`RuleBasedClassifier` 常驻链尾兜底；预算按
  手机档位配置。飞行模式时链上本地分类器可用，本地快路径（计时/闹钟/备忘等）
  不依赖网络。
- **电脑端**（apps/desktop）：同样构造 router，但预算取 `pc_*` 档；电脑算力高，
  未来可加载更大的本地模型做文件整理、批量任务，接法相同——实现
  `IntentClassifier` 后 `register()` 进链或按优先级插入构造参数。
- 两端分类器的产出都只是提案，进入与云端提案完全相同的后续流程：
  schema 校验 → 策略引擎定级 → 执行器。

## 当前边界

- **规则实现已可用**：`RuleBasedClassifier` 包装 `LocalIntentParser`，覆盖计时、
  闹钟、备忘、打开应用、搜索五类口令，认不出返回 null；行为与现有离线快路径
  完全等价（有等价性测试）。
- **ONNX/LLM 分类器未接入**：属于 TODO（见 `IntentClassifier` 与
  `LocalModelRouter` 的注释）。大模型服务商与模型选型是未定事项，代码里不写死，
  落地时由配置决定。
- **资源管理未实现**：模型的加载/卸载、按预算控制常驻内存、按负载选模型，
  在阶段 2"本地模型层完整实现"时补充；当前 `ResourceBudget` 只做声明与校验。
- `RuleBasedClassifier` 的 confidence 恒为 1.0，是占位语义（规则命中无概率）。

## 安全约束

- **提案不定级**：分类器（含未来的本地大模型）只产出意图提案，不碰元数据、
  不定确认级；确认级一律由策略引擎按 origin/taint/速率计算，设备再独立复核
  （DEVELOPMENT.md 第 2 节硬规则）。
- **taint 透传**：`tainted` 输入的污点标记原样进入 `ClassificationResult`，由
  上层据此加级；分类器不修改、不丢弃该标记。
- **白名单不变**：提案的意图类型仍在 9 种白名单内（经 `AssistantIntent` /
  `IntentType`），本地实现不新增能力、不绕过统一安全策略。
