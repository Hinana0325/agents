# ADR 0013：手机端先用 HTTPS 拉取式传输，WS 后续

状态：已采纳（2026-11-02）

## 背景

计划的目标传输是「设备出站 WSS + FCM 唤醒后拉取」。但云端 hub 还不存在，
FCM 需要 Google 服务与项目配置；如果先写 WS 客户端，没有任何对端可以验证。

## 决定

- 先实现 **HTTPS 拉取式** `HttpHubClient`（OkHttp），接口保持不变，后续 WS 只换实现：
  - `GET  {base}/v1/device/inbox?device_id=…` → `{"envelopes":[…],"cursor":"…"}`
  - `POST {base}/v1/device/receipts` → 回执
  - `Authorization: Bearer <设备令牌>`（令牌只存本机加密存储，不写日志）。
- 设置页新增「编排服务（可选）」：Hub 地址 + 设备令牌；未配置时只用本机能力。
- 用 MockWebServer 做单测，验证路径、查询参数、鉴权头、回执 JSON 与错误码处理。
- 设备端处理信封仍走 `core` 的 `DeviceRuntime`（验签、策略重算、去重、L2 审批），
  拉取客户端只负责搬运。

## 后果

- 传输层可以在没有云端的情况下先把设备侧协议细节锁定并测试。
- 还没有真正的 hub 端点、设备注册/令牌签发、FCM 唤醒与 WS；当前设置只是准备工作，
  拉取后的处理/同步 UI 下一步接。
- 轮询频率、退避与前台限制在接入 hub 时一并设计（不在本 ADR 里假装已定）。
