# 开发轮次报告：手机端本地模型接入 + sync 契约（2026-11-03）

分支：agent/phone-local-model + agent/sync-contract（已合入 main，推送到 origin/main）
方式：2 个并行子代理独立 worktree 开发，全量验证后合并。
依据：DEVELOPMENT.md 阶段 1 交付物"本地模型初步接入（意图分类）"、第 3/4 节 sync 目标结构与 ADR 0004、契约先行方法论。
云端 hub（托管方式未拍板）、手表（系统未定）、local_search 接入（A/B 未拍板）继续搁置。

## 交付内容

### 1. 手机端本地模型接入（apps/phone）
- 新增 `agent/PhoneAssistantRouter.kt`：组装 LocalModelRouter（链头预留高优先级本地分类器位，ONNX/LLM TODO 不写死服务商；RuleBasedClassifier 兜底；ResourceBudget 512MB/tier=mid）
- `chat/ChatViewModel.kt` 离线快路径由直调 LocalIntentParser 改为经 router，**行为完全等价**（18 个样本逐条对拍，含 null）
- 新增 9 个单测（组装/等价/降级/污点透传），`:apps:phone:app:testDebugUnitTest` 15 个全绿，现有测试零破坏
- 环境备注：沙箱已装 Android SDK（platform-35 / build-tools 34.0.0，匹配 AGP 8.7.3、compileSdk 35）；local.properties 不入库

### 2. sync 同步契约（protocol + core + tools 三层对齐）
- `protocol/schema/sync/`：sync-entity.v1.json（kind/UUIDv7 id/rev/墓碑/payload）、sync-batch.v1.json（base_rev/entries≤500/cursor/next，$ref 引用实体）
- `protocol/spec/sync.md`：push/pull API 形状、墓碑语义、server seq 单调、8 类数据单写者表、14 个错误码（SYNC_*）+ 服务端 409（SYNC_CONFLICT）
- 金标准向量 22 条（valid 5 / invalid 17）：Kotlin `SyncValidator`（严格手写校验）与 Python `validate_sync` 同过同一份向量
- 向量总数 230 → **252**（README 已更新）；CHANGELOG 记 v1.1（小版本只增不改）

## 验证证据（合并后全量）

- Gradle `:core:kotlin:test`（166）+ `:apps:phone:app:testDebugUnitTest`（15）：BUILD SUCCESSFUL
- Python 向量：252 cases, 0 failed；属性测试 10 条 OK；eval 门禁 --strict 通过

## 边界（未验证 / 未完成，不要当成已完成）

- 手机端仅在单测层验证，未跑模拟器/真机；router 链头尚未接入任何真实本地模型
- sync 契约只有设备侧校验与向量，无服务端实现（云端 hub 待拍板）；payload 按 kind 的子 schema、墓碑保留期为 TODO
- sync 向量与 eval-data workflow 首次进 CI，结果见下节补记

## CI 补记

（推送后回填：GitHub Actions 运行结果）
