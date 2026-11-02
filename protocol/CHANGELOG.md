# Protocol CHANGELOG

## v1 — 2026-11-02

首个冻结版本（Phase 0 起点）。

- 信封：`schema/envelope.v1.json`，字段 `v, id, trace?, ts, ttl_s, from, to, intent, params, origin, level, parent?, sig`。
- 意图白名单 9 种：`open_app, set_alarm, create_event, set_timer, web_search, save_note, chat, task_handoff, voice_command`，params 见 `schema/intents/*.v1.json`，全部 `additionalProperties: false`。
- 回执 `schema/receipt.v1.json`、审批证明 `schema/approval.v1.json`、能力声明 `schema/capability.v1.json`。
- 策略 `policy/intent-policy.v1.yaml`；规范 `spec/`。
- 金标准向量 `vectors/`，Kotlin 与 Python 两份实现必须 100% 通过。
- 新增 `schema/device-cert.v1.json`：手机签发的设备证书（`spec/pairing.md`），
  `core/kotlin` 提供 `DeviceCert.create/verify`；当前手工交换 JSON，扫码后续补。
- 新增 `schema/revocation.v1.json` 与 `vectors/cert`、`vectors/revocation` 金标准向量
  （设备证书 11 条、吊销记录 9 条），Kotlin 与 Python 参考实现同跑同过；覆盖过期、
  未生效、角色越权、篡改、未知字段、错误信任根、已吊销、错误签名者。

### 与旧原型的关系

旧的原型定义（`docs/legacy/AgentEnvelope.kt`、`envelope.py`、`envelope.schema.json`）使用大写意图名（`OPEN_APP` 等）和 `confirmation_tier/status/agent_id/device_id` 字段，已被本版本取代。迁移时意图名改为小写，`status` 移出信封（进入回执与状态机），`agent_id` 合并为 `from.role`。
