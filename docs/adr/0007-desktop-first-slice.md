# ADR 0007：电脑端 v1 先做无头服务 + 本地目录通道

状态：已采纳（2026-11-02）

## 背景

计划里的电脑端是「托盘 + 无头服务 + Compose Desktop」。但当前没有云端 hub，
也没有手机配对通道；如果先做完整 UI 和 WS，会把时间花在无法端到端验证的部分。
电脑是 P1 执行节点，先让它**能独立收信封、能执行、能被确认**更有价值。

## 决定

1. **先做无头服务**（`:apps:desktop:service`，Kotlin/JVM，复用 `core/kotlin`）。
   托盘用 AWT `SystemTray`、L2 确认用 Swing `JOptionPane`，不引入 Compose Desktop。
   Compose Desktop 留给后续做设备列表、审计历史、设置等页面。
2. **传输先用本地目录通道**：`inbox/` 投递、`outbox/` 回执、`pending/` 待确认。
   云端 hub 就绪后替换为 WSS 拉取 + FCM 唤醒，`InboxAgent` 的“处理一条信封”逻辑不变。
3. **身份密钥先落文件**（`~/.dpa-desktop/identity-private.der`，PKCS#8），
   文档明确标注这不是最终方案；DPAPI/TPM 包装与手机扫码背书的设备证书属后续。
4. 运行时状态（已执行 id、待确认任务）只在内存；`pending/` 的重放靠“处理完即移走文件”兜底。
   接 WS 前必须换成持久化收件箱/发件箱。

## 后果

- 现在就能用 `device-sim` 端到端验证设备侧：验签、策略、执行、审批、回执。
- 与计划的技术选型有偏离：Compose Desktop 推迟；密钥保护不达标，**不能用于生产**。
- 本地目录通道不是安全传输（同一台机器上的文件权限即信任边界），只用于开发联调。
