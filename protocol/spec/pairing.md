# 配对与设备证书

手机是信任根。电脑/手表要成为受信节点，由手机签发一张设备证书（`schema/device-cert.v1.json`），
证书把 `device_id`、`role`、设备公钥与 `max_level` 绑定，手机用自己的身份密钥签名。

## 流程（v1，手工交换；扫码后续补）

1. 电脑生成身份密钥（P-256），`pair-request` 输出配对请求 JSON：
   `{ "v":1, "device_id":"pc-xxxx", "role":"pc", "max_level":2, "pubkey":{...} }`
2. 手机端核对请求里的 `device_id / role / max_level`，用手机身份密钥对证书字段签名，
   得到完整证书 JSON（`issued_by` 为手机 device_id，`expires_at` 由手机决定）。
3. 电脑 `trust-phone <手机公钥.json>` 记录信任根，再 `verify-cert <证书.json>`：
   验签、检查有效期与级别约束，通过后把证书保存到 `config.json` 的 `device_cert`。

## 校验规则

- 证书签名必须能被信任根（手机公钥）验过。
- `role` 为 `watch`/`pc` 时 `max_level` 不得为 3；3 只属于手机。
- `expires_at` 必须晚于 `issued_at`，且当前时间不得晚于 `expires_at`；签发时间允许 120 秒时钟偏移。
- 证书到期后，设备需重新配对；吊销由手机签发 `schema/revocation.v1.json`
  （绑定 `cert_hash` 与设备 id），电脑验签后把哈希列入本地吊销表并作废本机证书。

## 尚未实现

- 二维码实时相机扫描（当前是相册识别）。
- 吊销列表的云端分发、证书轮换、配对时的人工核对界面。
