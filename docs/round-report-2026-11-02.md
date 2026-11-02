# 开发轮次报告：本地模型层 + eval 基线 + 属性测试（2026-11-02）

分支：feature/local-model-eval-fuzz（已合回 main，未推送远程）
方式：3 个并行子代理在独立 worktree/分支开发，集成分支全量验证后合并。
依据：DEVELOPMENT.md 第 7 节（本地模型）、阶段 3 eval 交付物、第 10 节属性测试不变量。
云端 hub / 手表 / 文件搜索接入因未定事项（托管方式、手表系统、A/B 方案）本轮未动，待用户拍板。

## 交付内容

| 工作块 | 分支 | 内容 |
|--------|------|------|
| 本地模型层骨架 | agent/local-model-layer | core/kotlin 新增 `localmodel/` 包：IntentClassifier 接口（只出提案、taint 透传）、LocalModelRouter 有序降级链、ResourceBudget 预算声明、RuleBasedClassifier（包装既有 LocalIntentParser）；docs/local-model.md 接线说明 |
| eval 评测基线 | agent/eval-baseline | eval/ 话术集 327 条（9 意图各 ≥30 + clarify/refuse 33）+ 注入集 104 条（5 载体、6 手法）；rule_runner.py 纯标准库数据门禁（动态读 schema 校验 params 键）；nightly.sh；CI workflow eval-data.yml |
| 属性测试 | agent/property-fuzz | JVM：kotest-property 覆盖 5 条不变量（taint 单调、签名变异必败、handoff 深度≤1、同 id 幂等、未知字段拒绝），JUnitPlatform + vintage 保住旧测试；Python：Hypothesis 10 条性质镜像 |

## 验证证据（集成分支全量）

- `gradle :core:kotlin:test`：BUILD SUCCESSFUL，165 PASSED（旧 131 + 新 34，旧测试零丢失）
- Python 金标准向量：230 cases, 0 failed
- Python 属性测试：10 tests OK
- `python3 eval/runners/rule_runner.py --strict`：门禁通过（话术/注入数量与分层达标，重复文本 0）
- eval 数据人工抽查：话术自然（口语/长句/错别字变体）、槽位与 schema 对齐、注入载荷真实

## 边界（未验证 / 未完成，不要当成已完成）

- 本轮全部改动未在 GitHub CI 上执行过（CI 此前从未跑过）；未过真机。
- 本地模型层未接入任何真实模型（ONNX/LLM 分类器为 TODO，未定服务商不写死）。
- eval 打分后端未实现（待云端大模型网关落地）；本轮只交付数据与质量门禁。
- 属性测试未发现既有实现的可复现 bug（构造性失败均为测试自身问题，已修正）。

## 环境备注（沙箱）

- 构建用 /opt/gradle-8.11.1（wrapper 下载不稳）；JDK 17 经 apt 安装；~/.gradle/init.gradle 注入腾讯镜像（settings 级，保持 FAIL_ON_PROJECT_REPOS 语义）。
