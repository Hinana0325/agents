# Agents 工作报告

日期：2026-11-02
仓库：Hinana0325/agents（私有库，主分支 main）
范围：本库只放 Agent 部分，不放云端编排。云端编排留在多设备架构的另一处。

## 1. 做了什么

### 方向演变
- 起点是单机手机助手：应用级、无 root。
- 2026-11-02 晚方向改为多设备、多 Agent：手机、手表、电脑、耳机、云服务器。
- 手机 MVP 不废弃，直接成为 Phone Agent；WorkBuddy 编译手机版一事先放下（不推库、重构优先），之后再说。
- 本库就是按“Agent 部分单独拿出来”整理的结果。

### 本库内容
- `phone-agent/`：手机 Agent 全套 Kotlin/Compose 源码，从手机 MVP 原样复制，另附映射说明 `MAPPING.md`。
  - 包名 `cn.phoneassistant`，应用名“手机助理”，界面简体中文、中性灰白。
  - 技术栈：Kotlin 2.1.0、AGP 8.7.3、Compose BOM 2024.10.01、compileSdk 35、minSdk 29、targetSdk 35。
  - 7 种白名单意图：OPEN_APP、SET_ALARM、CREATE_CALENDAR_EVENT、SET_TIMER、WEB_SEARCH、SAVE_NOTE、CHAT_ONLY。
  - 确认分级：纯聊天和网页搜索可直接执行；打开应用、闹钟、计时、存备忘必须先出意图卡片、用户点确认才执行；日历还要再过一次系统界面确认。
  - 密钥只存在本机 Keystore 保护的加密存储里，设置页只显示“已设置 / 未设置”，不回显原文，代码里没有写死的密钥和服务地址。
  - 清单里唯一权限是联网；无 root、无 shell、无无障碍、无短信、无电话、无后台服务。
- `watch-agent/`：设计文档 only，无手表 App，手表型号和系统未定；手表不独立跑大模型，只做快捷指令、提醒确认、一瞥信息和向手机/电脑接力。
- `pc-agent/`：设计文档 + 一个只读文件搜索存根 `file_search.py`（仅搜命令行指定的目录，拒绝目录外路径，不写文件）。无 PC App，PC 系统未定；写操作以后也只能走最高一级确认。
- `earphone-agent/`：设计文档 only，无耳机软件；耳机只是语音出入口，唤醒、语音识别和合成跑在手机或电脑上，敏感操作必须语音复述并确认，高风险操作推到手机界面确认。
- `protocol/`：所有 Agent 共用的意图信封定义（JSON Schema、Kotlin、Python 三份）。
  - 字段：任务 ID、设备 ID、Agent ID、意图类型、参数、确认分级（1/2/3）、任务状态（待确认 / 执行中 / 已完成 / 已取消）、创建时间。
  - 意图类型共 9 种：手机的 7 种 + HANDOFF_TASK（任务接力）+ VOICE_COMMAND（语音指令）。
  - 规则：Agent 之间只传这种信封，不传自由执行指令；未知类型、未知参数、缺必填参数一律拒绝；接力和语音指令不能直接执行，必须先转成具体意图并确认。

### 建库和推送过程（已核验）
- 仓库名和可见性由用户定：私有库 `agents`。
- 连接器直接建库报 403（权限不够，不能新建库），改走浏览器在已登录账号里建了一个空私有库，未勾选 README、未加 license 和 .gitignore。
- 首次快照推送 38 个文件，提交 4b5cba83d2c0f4667694e2fae0503519ea2fe11c；随后把 README 里一句过时的“尚未推送”状态改掉，提交 ebd9e434c2df3fefd312c893334930be6d17bc9e。
- 推送后回读核验：根目录为 README、phone-agent、watch-agent、pc-agent、earphone-agent、protocol；README 内容与本地一致。
- 同步记录不在库内，存在本地（按快照推送的常规做法，同步元数据不进仓库）。

## 2. 已核验的事实

- 手机 Agent 源码文件数：本地整理时 30 个文件、Kotlin 1436 行；本库 phone-agent 在此基础上多 1 个映射说明文件。
- 本库首次推送文件数：38 个（不含二进制文件，见下一节）。
- 协议 Python 文件和云端骨架（不在本库）在本地通过 Python 编译检查；Kotlin 未编译。
- 源码 grep 检查：无 root、无 shell、无无障碍、无短信电话相关调用；无写死的密钥和服务地址（仅有示例任务 ID 和 localhost 示例）。

## 3. 明确未验证、未完成的事

- 手机 Agent 未编译、未测试、未在真机验证。本机无 Java/Android SDK，首次 Gradle 构建很可能暴露编译错误，需在 JDK 17 + Android SDK 35 的机器上跑通。
- 二进制文件 `gradle-wrapper.jar` 本次未推送（快照推送只支持文本文件，与之前 system-apps 的处理相同）。克隆下来后需补这个 jar，或用本机 Gradle 8.11.1 编译。
- 快照推送会丢文件可执行权限，克隆后 `gradlew` 可能需先 `chmod +x` 才能直接跑。
- 加密库用的是 alpha 版，正式发布前建议换稳定版；只有意图校验器有 11 个单元测试，执行器和网络客户端没有对应测试。
- 手表、电脑、耳机节点目前只有设计文档和存根，不是已实现的产品；云端编排不在本库，也未部署、未接真实大模型、鉴权只是占位，绝不能直接暴露到公网。
- 手表系统和型号、电脑系统、云服务器托管方式、大模型服务商均未定，本报告不替用户定。

## 4. 下一步（待用户定）

- 在有 JDK 17 + Android SDK 35 的机器上（例如 WorkBuddy）编译手机 Agent，修掉首版编译错误。
- 定手表、电脑、云服务器和大模型服务商，再往下做手表 companion、电脑 Agent 和云端编排。
- 定云端编排单独放哪里（是否另建一个库），再决定它和本库的关系。

## 5. 补充（2026-11-02 晚）：手机 Agent 首次构建通过

### 先撤回一条错误结论
第 3 节写的"本机无 Java/Android SDK"不成立。本机实测有 Zulu JDK 17.0.19（`C:\Program Files\Zulu\zulu-17`）、Android SDK（`%LOCALAPPDATA%\Android\Sdk`，platforms 含 android-35、build-tools 含 34.0.0）、Gradle 8.11.1 发行版已解压在 `~/.gradle/wrapper/dists`；dl.google.com 与 repo1.maven.org 直连返回 200。构建不需要另找机器。

### 已修
- A 类（不修就编不过）：`app/build.gradle.kts` 补 `kotlin { compilerOptions { jvmTarget = JVM_17 } }`（原来只有 Java 17，Kotlin 默认 1.8，`:app:compileDebugKotlin` 必报 JVM-target 不一致）；从 Gradle 发行版内嵌资源解出 `gradle/wrapper/gradle-wrapper.jar` 并补上 `Main-Class`（此前整个缺失，gradlew 不可用）；加 `local.properties`（gitignore 掉）与根 `.gitignore`。
- B 类（装上即崩/静默失效）：`IntentExecutor.startIfResolvable` 补 `FLAG_ACTIVITY_NEW_TASK`（调用方传的是 Application context，闹钟/计时/日历/搜索四个意图原本 100% 报"打开系统界面失败"）；Manifest 补 `<queries>`（Android 11+ 包可见性，原本 `queryIntentActivities`/`resolveActivity`/`getLaunchIntentForPackage` 被过滤成空，OPEN_APP 永远"没有找到应用"）；`SettingsStore` 改为懒加载 + 建不出来时记中文原因并拒绝读写（**不退回明文存储**），并在 IO 线程预热、意图执行与候选应用解析移出主线程；新增 `network_security_config.xml`，明文 HTTP 只对 localhost/127.0.0.1/10.0.2.2 放行，其余域名仍强制 HTTPS；`security-crypto` 从 `1.1.0-alpha06` 升到稳定版 `1.1.0`。
- 顺带：设置页"当前状态"改为保存后即时刷新，加密存储不可用时用中文显式报错，失败文案用红色而不是绿色。

### 已核验的事实
- 全量重建（`:app:clean` + `--no-build-cache`，停掉 daemon 后）所有 task 重新执行：BUILD SUCCESSFUL。
- `:app:testDebugUnitTest`：11 个用例，0 失败 0 跳过 0 错误。
- `:app:assembleDebug`：产物 `phone-agent/app/build/outputs/apk/debug/app-debug.apk`（25,488,321 字节）。
- `aapt2 dump badging`：包名 `cn.phoneassistant`、versionName 0.1.0、targetSdk 35、label「手机助理」、`uses-permission` 只有 INTERNET（外加 AndroidX 自动加入的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 内部权限）。
- `:app:lintDebug`：0 错误；告警为 UnusedResources、OldTargetApi、ObsoleteSdkInt、RedundantLabel、MonochromeLauncherIcon、DataExtractionRules 一类，不影响功能。
- 构建环境副作用（不在仓库里）：`subst V:` 把本仓库挂成 ASCII 盘符；`~/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1` 新加了指向已解压发行版的目录联接，并补了 `.zip.ok` 标记，让 `gradlew` 免下载可用。

### 仍未验证
- **没有真机**：本机 `adb devices` 为空，没插任何安卓设备，界面、7 种意图的实际跳转、Keystore 存储都只在静态层面成立。上面所有"修好"的判定依据是编译、单元测试、lint 和 APK badging，不是实机点击。
- 中文路径坑：直接在 `…\Desktop\分布式个人助理` 下跑单测会 `ClassNotFoundException`（类文件其实已生成在 `app/build/tmp/kotlin-classes/debugUnitTest/`）。绕法见 README「构建手机 Agent」。这是本机环境问题，不是代码问题。
- `security-crypto` 1.1.0 起把 `EncryptedSharedPreferences`/`MasterKey` 标为 deprecated，编译有告警；换实现方式是下一个决定，没在本次改动里做。
- 手表、电脑、耳机、云端编排的状态与第 3 节相同，本次没有任何改动。
