# ADR 0008：电脑端 UI 采用 Compose Desktop（修订 ADR 0007）

状态：已采纳（2026-11-02）。取代 ADR 0007 中“先用 AWT 托盘、Compose Desktop 推迟”的部分。

## 背景

ADR 0007 为了先跑通设备侧，用了 AWT `SystemTray` + Swing `JOptionPane`。用户明确要求按
`DEVELOPMENT.md:202` 的选型执行："电脑：Kotlin/JVM + Compose Desktop（托盘加确认弹窗），复用 core"。

## 决定

- 引入 Compose Multiplatform `1.7.3` + `compose.material3`，服务模块同时应用
  `org.jetbrains.compose` 与 `org.jetbrains.kotlin.plugin.compose`。
- 主界面：设备信息、hub 公钥状态、inbox/pending/outbox 计数、运行日志。
- 系统托盘：图标 + 菜单（显示主界面 / 退出）；关闭窗口最小化到托盘。
- L2 确认：Compose `AlertDialog`，默认按钮是“取消”；确认后仍由本机 P-256 审批密钥签名
  `approval.v1` 再执行（安全路径不变，只换了 UI）。
- 通知：Compose 托盘 `sendNotification`，失败时退回日志。
- 无头模式 `once` 不启动任何 Compose 窗口，保持 CI/脚本可用。

## 后果

- 与文档选型一致；AWT/Swing 代码删除。
- 桌面 UI 需要图形环境；`once` 仍是纯命令行。
- 后续按文档继续补 SQLite、JNA/FFM、DPAPI/TPM、MSI 打包。
