# Protocol CHANGELOG

## v1 — 2026-11-02

首个冻结版本（Phase 0 起点）。

- 信封：`schema/envelope.v1.json`，字段 `v, id, trace?, ts, ttl_s, from, to, intent, params, origin, level, parent?, sig`。
- 意图白名单 9 种：`open_app, set_alarm, create_event, set_timer, web_search, save_note, chat, task_handoff, voice_command`，params 见 `schema/intents/*.v1.json`，全部 `additionalProperties: false`。
- 回执 `schema/receipt.v1.json`、审批证明 `schema/approval.v1.json`、能力声明 `schema/capability.v1.json`。
- 策略 `policy/intent-policy.v1.yaml`；规范 `spec/`。
- 金标准向量 `vectors/`，Kotlin 与 Python 两份实现必须 100% 通过。

### 与旧原型的关系

旧的原型定义（`docs/legacy/AgentEnvelope.kt`、`envelope.py`、`envelope.schema.json`）使用大写意图名（`OPEN_APP` 等）和 `confirmation_tier/status/agent_id/device_id` 字段，已被本版本取代。迁移时意图名改为小写，`status` 移出信封（进入回执与状态机），`agent_id` 合并为 `from.role`。
