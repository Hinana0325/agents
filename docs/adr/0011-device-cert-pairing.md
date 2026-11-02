# ADR 0011：设备证书与手工配对（v1）

状态：已采纳（2026-11-02）

## 背景

计划规定“手机是信任根：电脑和手表由手机扫码背书，手机签发设备证书 {pubkey, role, max_level, expiry}”。
扫码需要手机相机 + 二维码库，且手机端还没有签名 UI；先把证书格式、签名与校验做成可执行契约，
再用复制 JSON 的方式完成 v1 配对，扫码后续替换传输方式即可。

## 决定

1. 新增 `protocol/schema/device-cert.v1.json` 与 `protocol/spec/pairing.md`：
   证书字段 `v/device_id/role/max_level/pubkey/issued_by/issued_at/expires_at/sig`，
   签名为手机身份密钥对 `JCS(cert minus sig)` 的 ES256，raw `r||s`。
2. `core/kotlin` 新增 `DeviceCert.create/verify` 与 `Es256.signRaw`（DER→raw 转换，
   这样 Android Keystore 的 `SHA256withECDSA` 也能用）。
3. 校验规则：电脑/手表的 `max_level ≤ 2`；`expires_at > issued_at`；过期拒绝；
   签发时间允许 120 秒偏移；验签必须用信任根（手机公钥）。
4. 电脑端命令：`pair`（输出请求）、`trust-phone <公钥.json>`（记录信任根）、
   `verify-cert <证书.json>`（验签并保存到 `config.json` 的 `device_cert`）。
5. v1 用复制 JSON 交换，不引入相机/二维码依赖。

## 后果

- 信任链在电脑端已可完整验证（真实 CLI + 证书签名实测通过）。
- **手机端签名 UI 尚未实现**：目前只能用测试密钥完成链路验证；下一步做手机 Keystore 身份密钥与配对页。
- 扫码、吊销列表、证书轮换、配对时的人工核对界面都未做。
