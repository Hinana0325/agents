# ADR 0012：ES256 验签统一走 DER 编码

状态：已采纳（2026-11-02）

## 背景

手机端“签名自检”在 Android 16 模拟器上失败（`CERT_SIGNATURE`），桌面 JVM 上同样的代码通过。
原因：验签用了 `Signature.getInstance("SHA256withECDSAinP1363Format")`，这是 SunEC 的扩展格式，
**Android 的 Conscrypt 不提供**，`getInstance` 抛异常后被 `catch` 成 `false`，表现为“签名不对”。

## 决定

- 协议里的签名仍然是 raw `r||s` 的 base64url（线上格式不变）。
- 验签时把 raw 解成两个 32 字节整数，**编回 DER**（`SEQUENCE { INTEGER r, INTEGER s }`），
  再用所有平台都支持的 `SHA256withECDSA` 验证。
- 签名方向保持 `SHA256withECDSA`（DER），再转成 raw；Android Keystore 与 JVM 都可用。

## 后果

- 同一份 `Es256` 在桌面 JVM 与 Android 上行为一致；设备端不再依赖 `inP1363Format`。
- 多了一小段 DER 编解码代码（`core/kotlin/.../Es256.kt`），已由信封/审批/证书测试覆盖。
- 这个问题只有真机/模拟器自检才能暴露；编译、JVM 单测都不会报。
