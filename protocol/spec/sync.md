# 同步协议（sync-batch v1）

权威设计见 `docs/adr/0004-single-writer-sync.md`（每类数据只有一个写者）与 `DEVELOPMENT.md` 第 4 节"传输与同步 / 冲突策略"。schema 见 `schema/sync/sync-entity.v1.json` 与 `schema/sync/sync-batch.v1.json`；金标准向量 `vectors/sync/`，Kotlin（`SyncValidator`）与 Python（`validate_sync`）两份实现必须给出完全一致的结果。

## API 形状

- `POST /v1/sync/push`：请求体是一个 `sync-batch`。`base_rev` 是客户端所基于的服务端 rev，服务端用它做冲突检测（见下）。`cursor` 不得出现在 push 请求里，`next` 恒为 `false`。
- `GET /v1/sync/pull?cursor=<游标>`：响应是一个 `sync-batch`。首次拉取不带 `cursor`；响应里 `entries` 按 `rev` 升序，`cursor` 是下次拉取游标（缺省或空串表示没有更多），`next` 表示是否还有更多增量，`base_rev` 是本批增量之前的服务端基线 rev。
- 客户端凭令牌只拉取/推送自己的数据；`batch.device_id` 必须与令牌设备一致（服务端侧校验，不在本 schema 范围）。

## 冲突检测与 409 语义

push 时服务端逐实体检查：若该实体所在数据流的当前服务端 rev 已超过请求 `base_rev`（即客户端基于旧版本推送），返回 HTTP 409，响应体 `{"code": "SYNC_CONFLICT", "current_rev": <服务端当前 rev>}`；客户端应先 pull 到 `current_rev` 再决定重放或放弃。同步层不做自动文本合并：备忘等按 ADR 0004 保留两个版本标"冲突副本"，由用户合并。

## server seq 与排序

- 服务端为每条入库实体分配全仓单调递增的 `rev`（即 server seq）；同步批内 `entries` 按 `rev` 升序。
- 排序与冲突判定只看 `rev`，不依赖设备时钟；实体 `ts` 仅作展示。
- 同一实体 id 重复 push 幂等：服务端已存在同 id 实体时不再产生新 rev。

## 墓碑语义

- 删除以 `deleted=true` 的实体表示（墓碑），不携带 payload；`rev` 照常递增，删除也是一次写入。
- 墓碑与正常实体走同一拉取通道，客户端收到墓碑后删除本地副本并保留墓碑记录用于去重。
- 墓碑保留期：TODO（需结合备份/审计保留策略定，倾向不少于 90 天；定稿前实现不得清墓碑）。

## 单写者表（kind 与权威方）

复制 ADR 0004 / DEVELOPMENT.md 第 4 节的权威方结论：

| kind | 数据 | 权威方 | 策略 |
| --- | --- | --- | --- |
| `chat_message` | 聊天记录 | 云 | 只追加，按 server seq 排序，无冲突 |
| `note` | 备忘 | 云 | 条目不可变 + 修订链；两端各改一次保留两个版本标"冲突副本"，由用户合并 |
| `setting` | 设置/偏好 | 云 | 字段级 LWW（混合逻辑时钟）；确认级相关设置只在手机本机生效，不经云下行 |
| `calendar_mirror` | 日历事件镜像 | 手机系统日历 | 云只存镜像与 event_uid 映射；创建带幂等键 |
| `scheduled_task` | 定时任务 | 云 | 单写者；端上修改等于发意图给云，不直接改 |
| `memory` | 长期记忆 | 云 | 端上只能提交"记忆建议"，由云合并；手机端可审阅/删除 |
| `presence` | 在线/电量/当前交互设备 | 各设备自报 | 软状态 + TTL，不做冲突解决 |
| `audit_ref` | 审计哈希链引用 | 云 | 只追加，指向本地审计链的锚点，用于完整性核对 |

闹钟/计时器不是同步实体：权威方为执行它的设备，云只记"已下发"，真实状态以设备回执为准（信封/回执通道），因此 v1 的 kind 枚举不含 alarm/timer。

## 字段约束

- `kind`：上述 8 类枚举，之外一律拒绝。
- `id`：UUIDv7，产生者设备生成；同一 batch 内不得重复（`SYNC_DUP_ID`）。
- `rev`：正整数；`base_rev`：非负整数（新设备为 0）。
- `entries`：最多 500 条（`SYNC_BAD_ENTRIES`）；可为空数组（pull 心跳/无增量）。
- `cursor`：≤512 字符的字符串，仅 pull 响应携带。
- `deleted`：缺省视为 false；true 时不得有 payload（`SYNC_TOMBSTONE_PAYLOAD`），非墓碑必须有 payload 且为对象（`SYNC_BAD_PAYLOAD`）。
- `payload`：v1 为宽松对象；具体子 schema 是 TODO（见 entity schema 内注释），展开时按"改协议必须同时改向量"执行。
- 不放浮点数：同步批里出现浮点即 `SYNC_BAD_TYPE`（硬规则与信封一致）。

## 错误码

校验顺序即下表优先级（batch 层先于 entry 层；entry 内先结构后取值）：

| code | 含义 |
| --- | --- |
| `SYNC_BAD_TYPE` | batch/entity 不是对象，或 entries/deleted/next/cursor/device_id 类型不符（含浮点数） |
| `SYNC_UNKNOWN_FIELD` | batch 或 entity 出现未声明字段 |
| `SYNC_MISSING_FIELD` | 缺必填字段（batch：device_id/base_rev/entries/next；entity：kind/id/rev/device_id/ts） |
| `SYNC_BAD_DEVICE_ID` | device_id 不是 1..64 长度的字符串 |
| `SYNC_BAD_BASE_REV` | base_rev 不是非负整数 |
| `SYNC_BAD_ENTRIES` | entries 超过 500 条 |
| `SYNC_BAD_KIND` | kind 不在 8 类枚举 |
| `SYNC_BAD_ID` | entity id 不是 UUIDv7 格式 |
| `SYNC_BAD_REV` | rev 不是正整数（含类型错误） |
| `SYNC_BAD_TS` | ts 不是 RFC 3339 UTC 秒级时间 |
| `SYNC_BAD_PAYLOAD` | 非墓碑实体缺 payload 或 payload 不是对象 |
| `SYNC_TOMBSTONE_PAYLOAD` | 墓碑实体携带 payload |
| `SYNC_DUP_ID` | 同一 batch 内出现重复 entity id |
| `SYNC_BAD_CURSOR` | cursor 超过 512 字符 |

`SYNC_CONFLICT` 只出现在服务端 409 响应里，不参与离线结构校验。

## 版本兼容（proto_minor）

- 遵循 `spec/versioning.md`：小版本只增**可选**字段，未知字段一律拒绝（`SYNC_UNKNOWN_FIELD`）——这是安全特性。
- 新增 kind 或展开 payload 子 schema 视为改协议：必须同时改 schema、本文件、向量与两侧校验器，向量失败即停。
- 设备在能力声明里报告支持的 proto_minor；云端只发设备认识的字段。

## 向量覆盖

`vectors/sync/cases.json` 共 22 条（valid 5 / invalid 17）：最小合法批、cursor+next 齐全、空 entries、墓碑合法批、8 kind 全覆盖；未知顶层/实体字段、kind 越界、id 非 UUIDv7、rev 非正整数（0 与字符串）、base_rev 缺失/为负、entries 超 500（501 条）、ts 非法、payload 非对象/缺失、重复 id、墓碑带 payload、缺 next、device_id 为空。
