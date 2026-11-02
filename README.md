# Agents（多设备个人助理的 Agent 部分，单独成库）

本库只放 Agent 部分，不放云端编排。云端编排（`cloud-orchestrator/`）留在多设备架构的另一处，不在本库。

## 目录

- `phone-agent/`：已实现的手机 Agent，Kotlin/Compose 源码（从手机 MVP 原样复制，30 文件 + 本库的映射说明 `MAPPING.md`）。应用级、无 root，7 种白名单意图 + 确认分级，密钥只存在本机 Keystore 加密存储。**未编译、未测试、未在真机验证。**
- `watch-agent/`：设计文档 only，无手表 App，手表型号和系统未定。
- `pc-agent/`：设计文档 + 一个只读文件搜索存根 `file_search.py`（仅搜命令行指定的目录，拒绝目录外路径，不写文件）。无 PC App，PC 系统未定。
- `earphone-agent/`：设计文档 only，无耳机软件；耳机只是语音出入口，模型跑在手机或电脑上。
- `protocol/`：所有 Agent 共用的意图信封定义（JSON Schema、Kotlin、Python 三份）：任务 ID、设备 ID、Agent ID、意图类型、参数、确认分级、任务状态。Agent 之间只传这种信封，不传自由执行指令。

## 明确不做的事

- 不含云端编排代码，不部署云服务器，不花云费用。
- 不含任何密钥、服务地址和个人信息；密钥由用户在使用时在设备上填写。
- 不做 root、shell、无障碍、短信、电话、后台服务。

## 状态

本地整理完成，尚未推送到任何仓库；推送前需确认仓库名和公开/私有。
