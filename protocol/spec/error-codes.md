# 错误码

两侧校验器必须在同样的输入上给出同样的错误码；向量里的 `expect.code` 就是判据。

| code | 含义 |
| --- | --- |
| `UNKNOWN_FIELD` | 出现了 schema 未声明的字段（信封或 params） |
| `MISSING_FIELD` | 缺少必填字段 |
| `BAD_TYPE` | 类型不对（含出现浮点数） |
| `BAD_ENUM` | 枚举值不在白名单 |
| `BAD_RANGE` | 整数越界、数组超长、字符串超长 |
| `BAD_FORMAT` | 字符串/时间/UUID 格式不符 |
| `UNSUPPORTED_VERSION` | `v` 不是 1 |
| `UNKNOWN_INTENT` | intent 不在 9 种白名单内 |
| `BAD_PARAM` | 单个字段合法但组合非法（例如 end_ms <= begin_ms） |
| `VOICE_NOT_EXECUTABLE` | voice_command 被当作可执行意图（它不是） |
| `HANDOFF_DEPTH` | handoff 提案里再嵌套 handoff/voice_command |
| `LEVEL_BELOW_POLICY` | 信封携带的 level 低于设备重算结果 |
| `TARGET_NOT_ALLOWED` | 意图不支持投递到该设备角色 |
| `EXPIRED` | 超过 `ts + ttl_s` |
| `CLOCK_SKEW` | 时钟偏移绝对值超过 60 秒 |
| `ID_REPLAYED` | `id` 已在已处理集合中 |
| `BAD_SIG` | ES256 验签失败（含被篡改） |

优先级（同时命中多个时返回第一个）：版本 → 结构/类型 → 意图 → 参数 → 策略级别 → 目标 → 过期 → 时钟 → 重放 → 签名。

## 设备运行时的回执错误码

审批与状态机的错误码不参与信封校验，出现在回执的 `error.code` 里：

| code | 含义 |
| --- | --- |
| `UNKNOWN_ENVELOPE` | 本地没有这条信封 |
| `NOT_AWAITING_CONFIRM` | 当前状态不接受审批或拒绝 |
| `APPROVAL_INVALID` | 审批证明结构/字段非法 |
| `APPROVAL_UNKNOWN_APPROVER` | 审批方不在受信列表 |
| `APPROVAL_LEVEL` | 审批级别低于要求，或超出审批方权限（手表/电脑上限 L2） |
| `APPROVAL_HASH` | 审批绑定的信封哈希不匹配 |
| `APPROVAL_EXPIRED` | 审批超出时间窗口 |
| `APPROVAL_REPLAY` | 审批 nonce 已用过 |
| `APPROVAL_SIGNATURE` | 审批签名验不过 |
| `EXECUTOR_FAILED` | 执行器返回失败 |
| `USER_REJECTED` | 用户在界面上拒绝 |

非法状态转移不产生回执，直接丢弃并写审计（见 `spec/state-machine.md`）。
