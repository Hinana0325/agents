# Protocol CHANGELOG

## v1.1 — 2026-11-03

sync（数据同步）契约层。遵循"小版本只增不改"：只新增 `schema/sync/`、`spec/sync.md`、`vectors/sync/`，未修改任何既有 schema、意图或错误码语义；sync 使用独立的 `SYNC_*` 错误码，不与信封错误码混用。

- 新增 `schema/sync/sync-entity.v1.json`：同步实体壳 `kind, id, rev, deleted?, payload, device_id, ts`。`kind` 为 8 类数据枚举；墓碑（`deleted=true`）不携带 payload；`payload` v1 为宽松对象（按 kind 展开子 schema 留 TODO）。
- 新增 `schema/sync/sync-batch.v1.json`：增量批 `device_id, base_rev, entries(≤500), cursor?, next`，经 `$defs/$ref` 引用实体 schema；push 请求带 `base_rev` 做冲突检测，pull 响应带 `cursor`。
- 新增 `spec/sync.md`：API 形状（`POST /v1/sync/push`、`GET /v1/sync/pull?cursor=`）、409 冲突语义、server seq 单调排序不依赖设备时钟、墓碑语义与保留期（TODO）、8 类单写者表、`SYNC_*` 错误码表、proto_minor 兼容（小版本只增可选字段、未知字段拒绝）。
- 新增 `vectors/sync` 金标准向量 22 条（valid 5 / invalid 17）：最小合法批、cursor+next、空 entries、墓碑合法批、8 kind 全覆盖；未知顶层/实体字段、kind 越界、id 非 UUIDv7、rev 非正整数、base_rev 缺失/为负、entries 超 500（501 条）、ts 非法、payload 非对象/缺失、重复 id、墓碑带 payload、缺 next、device_id 为空。Kotlin `SyncValidator` 与 Python `validate_sync` 同跑同过（总计 252 条）。
- 闹钟/计时器不经同步实体传输（权威方为执行设备，真实状态以回执为准，ADR 0004），故 kind 枚举不含 alarm/timer。

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
- 新增 `vectors/approval`（15 条）：审批证明的有效 L2/L3、降级、越权、过期、重放、
  错哈希、错信封、nonce 篡改、错误签名者、字段/类型错误；Python 侧新增 `verify_approval`。
- 新增 `vectors/receipt`（12 条）：回执的有效 done/awaiting/failed、未知字段/状态、
  空 envelope_id、非法时间、error 结构错误、超长 detail、版本类型错误、缺字段；
  Python 侧新增 `verify_receipt`，core 新增 `ReceiptValidator` 并用于运行时自检。

### 与旧原型的关系

旧的原型定义（`docs/legacy/AgentEnvelope.kt`、`envelope.py`、`envelope.schema.json`）使用大写意图名（`OPEN_APP` 等）和 `confirmation_tier/status/agent_id/device_id` 字段，已被本版本取代。迁移时意图名改为小写，`status` 移出信封（进入回执与状态机），`agent_id` 合并为 `from.role`。
