# ADR 0005：确认分级与"发送方不能降级"

状态：已采纳（2026-11-02）

## 背景

大模型只应提出意图，不应决定权限。云端可能被攻破或提示注入成功；设备必须能独立判断"这条信封最低要几级确认"。

## 决定

- L1 自动执行；L2 任一受信界面确认；L3 仅手机界面 + 生物识别。
- 级数由**策略引擎**算：基础级 + 意图特定升级（如 open_app 的 app_class/URL、create_event 的 attendees、set_alarm 的 repeat）+ 污点加 1（上限 3）。
- 设备收到信封后**独立重算**，取 `max(信封.level, 本机.level)`；若信封 level **低于**重算结果，整条拒绝（`LEVEL_BELOW_POLICY`），不是"提升后执行"。
- `app_class`、URL 允许清单来自端上目录，不来自 params——发送方无法自证清白。
- `voice_command` 不是可执行意图（`executable=false`），必须先解析成具体意图。
- handoff 提案深度 ≤ 1，不得嵌套 handoff/voice_command；级别 `max(2, proposal.level)`。

## 后果

策略规则同时存在于 `protocol/policy/intent-policy.v1.yaml`（人类可读权威）与两份校验器（可执行）。三者靠 `policy/` 与 `injection/` 向量对齐；改规则必须同时改向量。

## 实现（2026-11-02 晚）

- 手机身份密钥（`dpa-identity`）在运行时只登记为 L2 审批方，**不能**批准 L3。
- L3 使用单独的审批密钥（`dpa-approval`）：Android Keystore、P-256、
  `setUserAuthenticationRequired(true)` + `AUTH_BIOMETRIC_STRONG`，每次使用都要认证，
  生物识别重新录入后旧密钥作废。
- UI 用平台 `BiometricPrompt` + `CryptoObject` 解锁该密钥并签名；审批证明的
  `approver_device` 为 `<deviceId>:approval`，运行时只信任这个单独条目（最高 L3）。
- 设备没有安全锁屏或未录入生物识别时，审批密钥建不出来，L3 一律拒绝（fail closed）。
- Manifest 增加 `USE_BIOMETRIC`（普通权限，安装即授予）。
- 验证：`L3ApprovalBiometricTest` 是交互式仪器测试，默认跳过；
  用 `-e interactiveBiometric true` 显式启用并在提示期间按指纹（模拟器 `adb emu finger touch 1`）。
