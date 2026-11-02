# 信封状态机

```
created → queued → delivered → awaiting_confirm → executing → done
                                        │             │
                                        ├→ cancelled  └→ failed
                                        └→ rejected
任意未终态 → expired（TTL 到）
```

- 只有**回执**能推进状态；非法转移丢弃并写审计。
- 终态：`done`、`failed`、`expired`、`rejected`、`cancelled`。
- `delivered → awaiting_confirm` 仅当重算后 level ≥ 2。
- 幂等：`id` 是幂等键。同一 id 重复投递只执行一次；设备保留已处理 id 的时间不少于最大 TTL 加偏移窗口。
- `scheduled` 来源的 L2+ 任务只能进入 `awaiting_confirm`，不得静默执行。
- 确认端不可达时**绝不默认通过**，等到 TTL 过期。
- 电脑离线时信封在云端排队到 TTL，过期回执 `expired`，手机提示"未送达"。

TTL 建议：语音来源 120 秒；待确认 5 分钟；定时预置不超过 24 小时。
