# Agents（多设备个人助理的 Agent 部分，单独成库）

本库只放 Agent 部分，不放云端编排。云端编排（`cloud-orchestrator/`）留在多设备架构的另一处，不在本库。

## 目录

- `phone-agent/`：已实现的手机 Agent，Kotlin/Compose 源码（从手机 MVP 原样复制，30 文件 + 本库的映射说明 `MAPPING.md`）。应用级、无 root，7 种白名单意图 + 确认分级，密钥只存在本机 Keystore 加密存储。**已在本机首次编译通过，11 个单元测试全绿并产出 debug APK；仍未在真机验证。**
- `watch-agent/`：设计文档 only，无手表 App，手表型号和系统未定。
- `pc-agent/`：设计文档 + 一个只读文件搜索存根 `file_search.py`（仅搜命令行指定的目录，拒绝目录外路径，不写文件）。无 PC App，PC 系统未定。
- `earphone-agent/`：设计文档 only，无耳机软件；耳机只是语音出入口，模型跑在手机或电脑上。
- `protocol/`：所有 Agent 共用的意图信封定义（JSON Schema、Kotlin、Python 三份）：任务 ID、设备 ID、Agent ID、意图类型、参数、确认分级、任务状态。Agent 之间只传这种信封，不传自由执行指令。

## 明确不做的事

- 不含云端编排代码，不部署云服务器，不花云费用。
- 不含任何密钥、服务地址和个人信息；密钥由用户在使用时在设备上填写。
- 不做 root、shell、无障碍、短信、电话、后台服务。

## 状态

已推送到私有库 Hinana0325/agents，主分支 main。手机 Agent 已在本机首次编译通过（JDK 17 + Android SDK 35 + Gradle 8.11.1），11 个单元测试全绿，debug APK 可打包；仍未在真机验证。手表、电脑、耳机节点目前只有设计文档和存根。

## 构建手机 Agent（本机踩过的坑）

```bash
subst V: "<本仓库所在目录>"     # 必须是纯 ASCII 路径
cd V:/phone-agent
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- 仓库目录名带中文时，`:app:testDebugUnitTest` 会报 `ClassNotFoundException`（测试类实际已编译出来，是 worker classpath 的路径被编码搞坏）；AGP 还会先因非 ASCII 路径硬失败。先 `subst` 一个 ASCII 盘符再构建。
- `services.gradle.org/distributions/…zip` 会 307 跳到 github.com，本机 Java 取不到（curl 能通），所以 `gradlew` 首次下载发行版会失败；已用目录联接把缓存里现成的 Gradle 8.11.1 挂到该 URL 对应的哈希目录上。
- 加密库 `androidx.security:security-crypto` 已从 `1.1.0-alpha06` 升到稳定版 `1.1.0`；该版本起 `EncryptedSharedPreferences`/`MasterKey` 被官方标记 deprecated，换实现方式是后续决定，不在本次改动里。
