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

## 6. Phase 0（契约与脚手架）执行报告 — 2026-11-02 晚

依据：`DEVELOPMENT.md`（v1，Claude 方案 + 用户修订），第一周开工清单 Day 1–5。

### 先同步仓库

- 拉取 `origin/main`（新增 `DEVELOPMENT.md`，其余仍是同一份基线），与本地未提交的首次构建修复合并；合并后本地修复全部保留。
- 本地由此第一次有了 git 历史：`25a4718`（本地修复）+ `c74337b`（合并远程）。**未推送**。

### 按目标结构重组（Day 1）

- `phone-agent/` → `apps/phone/`；`watch-agent/` → `apps/watch/`；`pc-agent/` → `apps/desktop/`（搜索存根移到 `apps/desktop/capabilities/`）；`earphone-agent/README.md` → `docs/devices/earphone.md`。
- Gradle 改为根级多模块：`:core:kotlin`（纯 JVM）+ `:apps:phone:app`，wrapper/version catalog/gradle.properties 上移到根。
- 旧原型 `protocol/AgentEnvelope.kt`、`envelope.py`、`envelope.schema.json` 移到 `docs/legacy/`（未删除）。
- **没有创建 `services/hub/`**：DEVELOPMENT.md 明确要求云端代码不进本仓库。Python 侧放在 `tools/vector-runner/py/`，标注为参考实现。

### 协议契约（Day 2–3）

- `protocol/schema/`：`envelope.v1.json`、9 个意图 params schema（全部 `additionalProperties: false`）、`receipt.v1.json`、`approval.v1.json`、`capability.v1.json`。
- `protocol/policy/intent-policy.v1.yaml`：基础级、升级规则、污点、handoff 规则、全局约束。
- `protocol/spec/`：JCS 子集、签名与密钥、状态机、错误码（含优先级）、版本协商；`protocol/CHANGELOG.md` 记录与旧原型的差异（大写意图名 → 小写，status 移出信封）。

### 金标准向量

`protocol/vectors/` 共 **183 条**：valid 35、invalid 92、policy 26、injection 15、signing 15。覆盖：越界/未知字段/缺字段/类型错误、`javascript:`/`file:`/`data:`/`http:` 危险 scheme、超长、策略升级与"发送方不能降级"、污点加级与封顶、handoff 深度、ES256 篡改/过期/时钟偏移/重放。

向量由 `tools/vector-runner/py/gen_vectors.py` 生成（期望值按 spec 手写，不是由校验器反推）；固定 P-256 测试私钥只用于向量。

### 双实现（Day 4–5）

- `core/kotlin`：`Jcs.kt`（JCS 子集）、`EnvelopeValidator.kt`（严格结构 + 策略重算 + ES256 验签）、`ValidationContext.kt`、`ModelIntentParser.kt`（原手机校验器整体迁入，含 11 个用例）。
- `tools/vector-runner/py`：与 Kotlin 逐条对应的参考实现 + JUnit 之外的另一入口。
- 手机 App 改为依赖 `:core:kotlin`，删除原 `IntentValidator.kt` 与 `model/Intent.kt`。

### 已核验事实

- Kotlin：`./gradlew :core:kotlin:test --rerun-tasks` → `VectorTest.allVectorsPass` PASSED，`ModelIntentParserTest` 11 例全部 PASSED。
- Python：`uv run --with cryptography python tools/vector-runner/py/runner.py` → `TOTAL 183 cases, 0 failed`，退出码 0。
- 阳性对照（验收门禁）：把 `valid/chat_min` 的期望 level 改成 99 后，Python 退出码 1，Kotlin `VectorTest` FAILED；恢复后两侧重新全绿。
- 手机：`:apps:phone:app:assembleDebug` BUILD SUCCESSFUL，APK 位于 `apps/phone/app/build/outputs/apk/debug/app-debug.apk`。

### 未验证 / 未完成

- **CI 从未在 GitHub 上跑过**：`.github/workflows/ci.yml` 已写好（两侧向量 + Android 构建 + gitleaks），本地用同样命令验证过；没有推送，也没有 Actions 运行记录。
- **无真机**：手机界面、意图跳转、Keystore 仍无实机证据。
- 没有云端 hub、没有 device-sim、没有传输层/签名签发方；`signing/` 向量验证的是设备侧验签，不是"云真的签了"。
- schema 与两份手写实现靠向量对齐，**没有**引入通用 JSON Schema 校验器做三方交叉验证。
- ADR 0006（电脑文件搜索是否接入）仍待用户拍板，默认不接入。
- Phase 0 剩余：`spec/` 可继续补协议升级演练、`tools/codegen`、夜跑评测目录；这些不影响当前向量门禁。

## 7. Phase 1 设备侧运行时 — 2026-11-02 晚（续）

云端部署、大模型服务商、托管方式都还没定，所以先做不依赖云端的一半：设备收到信封之后怎么处理。

### 实现了什么

- `core/kotlin/.../runtime/DeviceRuntime.kt`：收信封 → 校验/验签 → 按 `id` 幂等去重 → L1 直接执行、L2+ 进入 `awaiting_confirm` → 回执；`sweepExpired()` 定期把过期信封标成 `expired`；`reject()` 处理用户拒绝。
- `core/kotlin/.../runtime/ApprovalValidator.kt`：审批证明（`approval.v1`）校验——字段严格、审批方受信、级别不低于要求且不超过审批方权限（手表/电脑上限 L2）、绑定信封哈希、时间窗口、nonce 防重放、ES256 验签。
- `core/kotlin/.../Es256.kt`：把验签与 SHA-256 抽出来，信封与审批共用；`EnvelopeValidator` 改为调用它。
- `protocol/spec/error-codes.md` 增补运行时/审批错误码。

### 已核验

- `./gradlew :core:kotlin:test`：**26 个测试全绿**（11 个模型输出解析 + 1 个 183 条向量 + 14 个运行时）。
- 运行时用例覆盖：重复投递 3 次只执行 1 次且回执一致；L2 不执行等审批；审批后执行；手表批 L3 被拒（`APPROVAL_LEVEL`）；审批绑定别的信封被拒（`APPROVAL_HASH`）；审批签名被篡改（`APPROVAL_SIGNATURE`）；nonce 重放被拒（`APPROVAL_REPLAY`）；过期信封被拒；`voice_command` 不执行；信封参数被篡改（`BAD_SIG`）；级别低于策略（`LEVEL_BELOW_POLICY`）；用户拒绝后不能再审批；sweep 标过期；审批不存在的信封。
- 手机 APK 仍可构建（运行时是纯 JVM，未改 Android 依赖）。

### 明确没做

- **手机还没接传输层**：没有 WS/FCM 收信封、没有本地持久化（Room）——运行时目前只在单元测试里被驱动。
- **没有把协议意图接到系统 Intent**：协议参数（`app_id`、`begin_ms`）与现有模型输出参数（`appName`、`date/startTime`）不同，需要端上应用目录与映射层，属下一步。
- 审批的 L3 生物识别没做：纯 JVM 层只校验"审批方有 L3 权限"，真正调用 `BiometricPrompt` 是手机 UI 的事。
- 云端与 device-sim 仍未开始。

## 8. 手机端成品化 v0.2.0 + 模拟器端到端验证 — 2026-11-02 晚（续）

按用户要求先把手机端做成可交付的成品，云端卡点往后放。

### 新增功能

- **对话历史持久化**：`data/ChatHistoryStore.kt`，应用私有目录 JSON，保留最近 200 条；重启不丢；设置页可清空。
- **备忘页**：`ui/NotesScreen.kt` + `NoteStore.list/read/delete`；列表按时间倒序，点开看全文，可删除；读取/删除只接受 notes 目录下的 `.txt`，拒绝路径分隔符。
- **设置校验与连接测试**：地址必须是 https（本机调试允许 localhost/127.0.0.1/10.0.2.2 的 http，与 `network_security_config` 一致）；新增 `LlmClient.checkConnection()`，只打 `/models`，不消耗对话额度；显示版本号。
- **release 签名**：keystore 生成在仓库外 `C:\Users\jq200\.android\keystores\phoneassistant.jks`，口令/路径写在 gitignored 的 `local.properties`；`assembleRelease` 产出已签名 APK。版本升到 0.2.0（versionCode 2）。
- 顶部栏改为 对话 / 备忘 / 设置 三页导航；系统拒绝时把原始异常换成可读中文。

### 模拟器端到端验证（一次性 AVD，Android 16，验证后已删除）

1. 安装签名 release APK，启动 → 界面正常（中文、中性灰白、右上角备忘/设置）。
2. 未配置时发消息 → 红色提示“还没配好…”，不会发网络请求。
3. `am force-stop` 后重启 → 历史消息仍在（持久化生效）。
4. 备忘页 → 列表、详情弹窗、删除按钮均正常（注入一条测试备忘验证读取与标题解析）。
5. 设置页填入 `http://10.0.2.2:8899/v1`（本机假服务）→“测试连接”返回绿色“连接成功（HTTP 200）”；保存后状态变为“密钥已设置；已配齐”。
6. 地址填 `http://example.com/v1` → 保存被红色错误拦截（明文只允许本机调试地址）。
7. **完整意图链路**：假 `/chat/completions` 返回 SET_ALARM 意图 → 应用显示回复与确认卡（动作/时间/备注）→ 点“确认执行” → 系统时钟被拉起，7:30、备注“测试闹钟”、弹出 “Alarm set” → 返回应用显示“已为你打开系统界面”。

### 验证中发现并修复的真机 bug

- 首次点确认后系统拒绝：`Permission Denial: ... requires com.android.alarm.permission.SET_ALARM`。
  AOSP 时钟的 `HandleSetAlarmApiCalls` 要求调用方持有该**普通权限**（安装即授予、无弹窗）。
  已在 Manifest 声明 `com.android.alarm.permission.SET_ALARM`，重装后同一操作成功拉起时钟。
  **这条只有在真实系统上跑才会暴露**，编译、单测、lint 都不会报。

### 产物与状态

- 签名包：`apps/phone/app/build/outputs/apk/release/app-release.apk`（20,896,855 字节，versionName 0.2.0）。
- debug 包：`apps/phone/app/build/outputs/apk/debug/app-debug.apk`。
- core 测试仍为 26 个全绿；APK 的 `aapt2 dump badging` 复核通过。
- **仍未在物理真机验证**；模拟器的 AOSP 时钟/日历不能代表小米等厂商实现。
- 签名私钥与口令只在本机，未进仓库、未打印到日志。

## 9. 电脑端 v1（无头服务）— 2026-11-02 晚（续）

按 DEVELOPMENT.md 把电脑端作为 P1 执行节点推进。没有云端 hub，所以先做“能独立运行”的那一半。

### 做了什么

- 新模块 `:apps:desktop:service`（Kotlin/JVM，复用 `core/kotlin`，无 Android 依赖）。
- **本地信封通道**：`inbox/` 投递 → 校验/验签/去重 → 执行 → `outbox/` 回执；L2 待确认放 `pending/`。
  这是 WS 传输就绪前的替代；换成 WSS 拉取后处理逻辑不变。
- **执行器**：`chat`、`save_note`（写本地备忘）、`web_search`（端上固定搜索模板）、
  `open_app`（端上应用目录映射 url/command）、`set_timer`（进程内计时 + 托盘通知）。
  日历/闹钟按策略只属于手机，电脑返回失败而不是假装成功。
- **确认**：L1 自动执行；L2 在 Compose 主界面弹确认框，用户点确认后用**本机 P-256 审批密钥**签名
  `approval.v1`（绑定信封哈希 + 时间窗 + nonce）再执行；L3 电脑没有审批权，留 pending 并提示去手机。
- **界面**：Compose Multiplatform 1.7.3 + Material3 主窗口（设备信息、hub 公钥、inbox/pending/outbox 计数、运行日志）+ 系统托盘（显示主界面 / 退出，关闭窗口最小化）。
  最初一版是 AWT 托盘 + Swing 确认框；按用户要求改为文档第 202 行的 Kotlin + Compose Desktop，见 ADR 0008。
- **身份**：`pair` 输出 `device_id + 公钥 JWK`，为将来的手机扫码背书留接口。
- `tools/device-sim/sim.py`：以 hub 身份签发信封（ES256 + JCS），投递到电脑收件箱；支持 `init/install/send/receipts`。
- ADR 0007：记录三处偏离（先 AWT 托盘不做 Compose Desktop、本地目录通道、密钥明文文件）及原因。
- CI 增加 desktop job（test + installDist）。

### 已核验

- `:apps:desktop:service:test`：**8 个用例全绿**（备忘执行、L2 待确认、L2 批准后执行、L2 拒绝不执行、
  错设备拒绝、篡改 BAD_SIG、重复投递只执行一次、不支持意图失败）。
- 本机端到端（临时目录，`dry_run=true`）：
  1. `sim install` 写入 hub 公钥 → `sim send save_note --level 1` → `service once` → 回执 `done`，备忘文件落盘，信封移入 `processed/`。
  2. `sim send save_note --level 2` → `service once` → 回执 `awaiting_confirm`，信封留在 `pending/`。
  3. `sim send web_search`（dry-run）→ 回执 `done`，没有真的打开浏览器。
  4. `service run` 启动 Compose 主窗口（截图核对：设备信息、计数、日志、托盘提示）；
     再发一条 L2 信封 → 界面弹出「确认执行」（动作/参数可见）→ 点确认 → 日志出现 `done`，
     回执 `done`，备忘写入，`pending/` 清空。
- 控制台中文乱码已修（JDK 17 默认 GBK → 分发包加 `-Dsun.stdout.encoding=UTF-8`；sim.py 也改用 UTF-8 输出）。

### 未验证 / 未完成

- **密钥保护不达标**：私钥是 `~/.dpa-desktop/identity-private.der` 明文 PKCS#8，DPAPI/TPM 未做；
- 本地目录通道不是安全传输，只用于开发联调；WS/FCM、手机扫码配对、设备吊销都未开始。
- 运行时去重状态只在内存；重启后靠 `pending/` 重放与“处理完即移走文件”兜底，接 WS 前必须持久化。
- 托盘图标常驻、通知点击、安装包/自启/自动更新、本地模型、相册整理、桌面自动化均未做。
- 电脑端只在中文 Windows 17 JDK 上跑过；计划要求“系统未定”，Linux/macOS 未验证。

## 10. 电脑端持久化与密钥保护 — 2026-11-02 晚（续）

补上 ADR 0007 里标记为 Phase 2 余项的两个缺口。

### 做了什么

- **SQLite 持久化**（`org.xerial:sqlite-jdbc`，表 `processed`）：只落终态回执（done/failed/rejected/cancelled）；
  `awaiting_confirm` 不落库，重启后重新处理。处理前按 `envelope_id` 查库，命中直接返回上次回执并移入 `processed/`。
- **DPAPI 保护私钥**（JNA `Crypt32Util`，当前用户作用域）：私钥写 `identity-private.dpapi`；
  旧的明文 `identity-private.der` 首次加载时自动加密并删除。非 Windows 暂明文，`status` 会标注。
- `status` 增加“已处理（持久化）”与“私钥保护”两行；ADR 0009 记录取舍。
- **处理历史接入界面**：Compose 主窗口新增“运行日志 / 已处理”页签，后者读 SQLite 回执历史（最近 80 条）。

### 已核验

- `:apps:desktop:service:test`：**13 个用例全绿**（原 8 个 + 跨重启去重 + DPAPI 往返 + 明文迁移 + 历史查询）。
- 本机端到端（临时目录）：
  1. 首次 `status` 生成身份 → 目录里只有 `identity-private.dpapi` 与 `identity-public.der`，**没有明文私钥**；
     `status` 显示“私钥保护：DPAPI（当前用户）”。
  2. 发一条 `save_note` → `once` → 回执 done、备忘落盘、`已处理（持久化）：1`。
  3. 把同一信封重新放进 inbox（模拟重启后重复投递）→ `once` → 输出 `DUPLICATE done`，
     备忘数量仍为 1，数据库计数仍为 1。
  4. 启动 Compose 界面 → 切到“已处理（1）”页签 → 显示 `done <id> <时间>`（截图核对）。

### 未完成

- TPM（CNG）与跨平台密钥库未做；DPAPI 绑定当前 Windows 用户，换机/重装后需重新配对。
- SQLite 只存去重与回执；收件箱/发件箱持久化、重试队列、审计历史页未做。
- WS/FCM、手机扫码配对、设备吊销仍未开始。

## 11. 电脑端打包 — 2026-11-02 晚（续）

计划要求“打包加自启加自动更新”，先做打包。

### 做了什么

- Gradle 任务 `packageAppImage`：`installDist` → `jpackage --type app-image --name DpaDesktop`，
  输出自带 JRE 的目录式应用（`build/jpackage/DpaDesktop/DpaDesktop.exe`）。
- 版本 0.2.0；GUI 版不带控制台，`status/once/pair` 继续走 `installDist` 的 `service.bat`。
- ADR 0010：本机缺 WiX，MSI 暂缓；装好 WiX 后加 `--type msi` 即可。

### 已核验

- `packageAppImage` BUILD SUCCESSFUL，产物 `DpaDesktop/DpaDesktop.exe` + `app/` + `runtime/`。
- 直接启动打包后的 exe（临时数据目录）：
  1. 未配置 hub 公钥时窗口正常打开，界面显示“hub 公钥：未配置（不会接收信封）”与指引日志；
  2. 写入 hub 公钥后重启，device-sim 发 `save_note` → 回执 done、备忘落盘、界面日志出现 `done`、
     “已处理（1）”页签可查（截图核对）。
- 打包暴露并修复了一个真实缺陷：GUI 模式缺 hub 公钥时原本直接 `return`，窗口一闪就退出；
  现在会开窗并给出配置指引。

### 未完成

- 没有 MSI/安装器、开始菜单、卸载登记、自动更新（自启已用 HKCU Run 实现，见下方补记）。
- 打包只有 Windows app-image；macOS/Linux 未验证。

### 补记：登录自启（同日）

- `install-autostart` / `remove-autostart` 命令：写/删 `HKCU\...\Run\DpaDesktop`，
  指向数据目录的 `autostart.cmd`；命令由 `java.home` + `java.class.path` 拼成，installDist 与 jpackage 都适用。
- 已核验：安装后注册表值可读回；直接执行生成的 `autostart.cmd` 能启动应用窗口；
  `remove-autostart` 后注册表值消失、`status` 显示“未启用”，测试数据目录已清理。
- 修了一个真实缺陷：最初用 `ProcessHandle.arguments()` 拼命令，Windows 上拿不到参数，
  生成的 .cmd 只有 `java.exe run`；改为从 JVM 属性拼。
- 未做自动更新（需要更新源与签名策略，等云端/发布方式定了再说）。

## 12. 电脑端设置页 — 2026-11-02 晚（续）

补上计划里电脑端 UI 的“设置”：

### 做了什么

- Compose 主窗口新增“设置”页签：设备信息、hub 公钥状态、dry_run 开关、
  应用目录（JSON：app_id → url 或 command）、URL 允许清单、搜索 URL 模板。
- 保存前由 `ConfigEditor` 校验：app_id 命名、每个应用必须有 url（https）或 command（1–8 个非空参数）、
  允许清单 host 格式、搜索模板必须 https + 含 `{q}`；失败给中文原因，不写文件。
- 保存写入 `config.json`，界面提示“已保存，重启服务后生效”（运行中的运行时不会热加载）。

### 已核验

- `ConfigEditorTest` 7 个用例（合法往返、默认字段显式落盘、非法 app id、缺 url/command、http url、
  模板缺占位符、非法 host）；桌面端合计 **20 个测试全绿**。
- 界面实测：切到“设置”页截图核对；点击“保存设置”后显示成功提示；
  `config.json` 包含 `role/hub_pubkey/hub_kid/app_catalog/url_allowlist/web_search_template/dry_run` 全部字段。
- 修了一个实现问题：kotlinx.serialization 默认 `encodeDefaults=false`，只改默认值以外的字段会“看起来没写入”；
  改为显式写出全部字段，便于用户直接查看和手改配置。

### 未完成

- 应用目录还是 JSON 文本编辑，不是逐项表单；没有“新增应用”的可视化交互。
- 设置改动需要重启服务才生效（没有热重载/运行时重建）。
- 设置页没有编辑 hub 公钥（配对流程还没做，目前用 device-sim/手工写）。

## 13. 设备证书与手工配对（电脑端）— 2026-11-02 晚（续）

按计划“手机是信任根，给电脑签发设备证书”，先落契约与电脑端验证，扫码后补。

### 做了什么

- `protocol/schema/device-cert.v1.json` + `protocol/spec/pairing.md`：
  证书绑定 `device_id/role/max_level/pubkey/issued_by/issued_at/expires_at`，
  手机身份密钥对 JCS 签名；电脑/手表 `max_level ≤ 2`。
- `core/kotlin`：`DeviceCert.create/verify`；`Es256.signRaw`（DER→raw，兼容 Android Keystore 的签名格式）。
- 电脑端命令：`pair`（配对请求）、`trust-phone <公钥.json>`（信任根）、
  `verify-cert <证书.json>`（验签并保存到 config）；`status` 显示信任根与证书。
- ADR 0011 记录“先手工 JSON、扫码稍后”的取舍。

### 已核验

- 核心 `DeviceCertTest` 7 例：有效、篡改签名、电脑 max_level=3、过期、签发时间在未来、错误信任根、缺字段。
- 桌面 `PairingTest` 5 例：信任根与证书持久化、无信任根失败、篡改证书 `CERT_SIGNATURE`、换密钥签名被拒、非法公钥被拒。
- 真实 CLI 端到端（临时目录）：`pair` 输出请求 → Python 用测试手机密钥签发证书 →
  `trust-phone` → `verify-cert` 输出“证书有效：pc-xxxx（role=pc，max_level=2，到期 …）”；
  篡改 `max_level` 后验证输出“验证失败：CERT_SIGNATURE”；配置里 `phone_pubkey` 与 `device_cert` 均已写入。
- 当前测试总数：core 33、desktop 25。

### 未完成

- **手机端签名 UI 未做**：手机还没有 Keystore 身份密钥与配对页，链路目前只能用测试密钥验证。
- 二维码展示/扫描、吊销列表、证书轮换、配对时的人工核对界面都未做。

## 14. 手机端配对 UI 与跨设备验证 — 2026-11-02 晚（续）

### 做了什么

- `data/DeviceIdentity.kt`：Android Keystore P-256 签名密钥 + 稳定 `device_id`（存在普通 prefs，非密钥）；
  私钥不可导出，只在 Keystore 内签名。
- “配对”页：展示设备 id 与公钥 JWK，可复制；粘贴电脑配对请求 → 校验（只接受 pc/watch、max_level ≤ 2）→
  用 Keystore 签发一年期设备证书；“签名自检”验证签名/验签一致；公钥与最近证书导出到 `files/pairing/`。
- 公共逻辑抽到 core：`Jwk.validate`、`PairingRequests.parse`（手机/电脑共用）。
- ADR 0012：ES256 验签统一走 DER（见下）。

### 发现并修复的真机兼容问题

- 首次“签名自检”在 Android 16 上失败（`CERT_SIGNATURE`），桌面 JVM 同样代码通过。
  根因：验签用了 `SHA256withECDSAinP1363Format`，Android 的 Conscrypt 不提供，异常被吞成验签失败。
- 修复：协议仍是 raw `r||s`，验签时把 raw 编回 DER 再用 `SHA256withECDSA`；签名方向同样统一 DER→raw。
  **只有真机自检能暴露这个问题**，JVM 单测与编译都不会报。

### 已核验（真实跨设备）

1. 桌面 `pair` 生成请求（`pc-b8ad5540`）。
2. 模拟器上把请求粘进配对页 → 手机用 Keystore 密钥签发证书（界面显示“已为 pc-b8ad5540（pc，最高 L2）签发证书”）。
3. `adb pull` 出手机公钥与证书 → 桌面 `trust-phone`、`verify-cert` →
   “证书有效：pc-b8ad5540（role=pc，max_level=2，到期 2027-11-02T12:52:28Z）”，`status` 同步显示。
4. 签名自检在设备上通过；core 测试 39 个、desktop 25 个全绿。

### 未完成

- 二维码展示/扫描（当前复制 JSON）；证书吊销、轮换、有效期续期；配对时人工核对设备信息的界面。
- 手机端导出的证书在应用私有目录，需要 root/调试方式取出；正式版应加“分享/发送到电脑”。

## 15. 二维码配对 — 2026-11-02 晚（续）

把“复制 JSON”升级成二维码，桌面生成、手机从相册识别。

### 做了什么

- `core/kotlin`：`QrCode.encode`（ZXing → 黑白像素，不把 ZXing 类型暴露给各端）与
  `QrCode.decodeLuminance`；单测覆盖“编码→解码往返”和“垃圾图返回 null”。
- 桌面：`pair-qr <输出.png>` 导出二维码；主界面新增“配对”页签，展示二维码、请求 JSON、
  信任根与设备证书状态。
- 手机：配对页新增“从相册扫描”（`GetContent` 选图片 → 解码二维码 → 填入请求框）；
  相机实时扫码未做。

### 已核验（真实跨设备，模拟器 + 桌面）

1. 桌面 `pair-qr` 生成 PNG（21,076 字节，肉眼/单测确认可解码）。
2. `adb push` 到模拟器并触发媒体扫描，配对页“从相册扫描”→ 选中图片 →
   请求框自动填入 `pc-f2f98842` 的请求，界面提示“已从二维码读取配对请求”。
3. 手机点“签发设备证书”→ 拉回公钥与证书 → 桌面 `trust-phone`、`verify-cert` →
   “证书有效：pc-f2f98842（role=pc，max_level=2，到期 2027-11-02T13:00:59Z）”。
4. 测试：core 41 个（新增 QR 2 个）、desktop 25 个全绿；APK 与桌面分发包构建通过。

### 未完成

- 相机实时扫码（现在必须先把二维码存成图片）；二维码里没有短链/有效期，内容就是请求 JSON。
- 证书吊销/轮换；手机端“把证书分享到电脑”的一键入口（目前从应用私有目录取出）。

## 16. 手机端动作历史与备忘撤销 — 2026-11-02 晚（续）

### 做了什么

- `core/kotlin`：`AuditLog`（只追加、保留最近 200 条、文件损坏按空处理），单测覆盖追加/重载/上限/坏文件。
- 手机：执行意图后记录 `intent / 参数摘要 / 时间 / 成败 / artifact`；`SAVE_NOTE` 成功时把文件名带进结果。
- “记录”页改为两个页签：备忘 + 动作历史；动作历史里 `SAVE_NOTE` 成功项可“撤销”
  （删除对应备忘文件、追加 UNDO 记录）；已被撤销的条目不再显示撤销按钮。
- 设计上只记录摘要，不保存密钥或完整聊天内容。

### 已核验（模拟器 + 假 LLM）

1. 设置指向本机假 LLM（返回 SAVE_NOTE）→ 发消息 → 确认卡 → 确认执行 → 备忘写入。
2. 记录 → 动作历史：出现“保存备忘 / content=…，title=… / 成功”，带“撤销”。
3. 点撤销：备忘文件被删除（`files/notes` 为空），历史顶部新增“撤销备忘 / 删除备忘 <file> / 成功”，
   原“保存备忘”条目的撤销按钮消失（用 uiautomator 边界定位按钮，避免坐标误判）。
4. 测试：core 44 个（新增 AuditLog 3 个）、desktop 25 个全绿。

### 未完成

- 历史里只有“撤销备忘”一种可逆动作；打开应用/闹钟/日历等不可逆或由系统接管的动作不提供撤销。
- 历史没有搜索/筛选/导出；也未与云端审计打通（传输层还没做）。

## 17. 手机端传输前置件：审批证明生成 + Hub 客户端 — 2026-11-02 晚（续）

### 做了什么

- core：`ApprovalProof.create`（绑定信封哈希、级别、审批设备、时间、nonce，手机身份密钥签名），
  与既有 `ApprovalValidator` 正反两面配套；3 个单测（有效/篡改/换信封）。
- 手机：`transport/HubClient` 接口 + `HttpHubClient`（OkHttp）：
  `GET /v1/device/inbox?device_id=…`、`POST /v1/device/receipts`、`Authorization: Bearer`；
  `MockWebServer` 3 个单测覆盖路径/查询参数/鉴权头/回执 JSON/HTTP 错误。
- 设置存储与设置页新增「编排服务（可选）」：Hub 地址 + 设备令牌（加密存储，界面不回显）；
  URL 规则与模型地址一致（https，本机调试允许 localhost/10.0.2.2 的 http）。
- ADR 0013：先 HTTPS 拉取、WS 后续；避免在没有云端时写无法验证的 WS 客户端。

### 已核验

- core 测试 47 个（新增 ApprovalProof 3 个）、桌面 25 个、手机单测 3 个全绿。
- 模拟器截图核对设置页「编排服务」区块：Hub 地址、设备令牌、状态、保存/清除按钮。
- APK 构建通过；CI 增加了手机单元测试步骤。

### 未完成

- **没有真实 hub 端点**：拉取/回执只对 MockWebServer 验证过；设备注册、令牌签发、FCM 唤醒、WS 都未做。
- 拉取到的信封还没有接入手机 `DeviceRuntime`（执行器映射、L2 审批弹窗、回执回传），下一步做。
- 轮询频率/前台限制/退避策略未设计。

## 18. 手机端接上设备运行时（mock hub 端到端）— 2026-11-02 晚（续）

### 做了什么

- core：`ProtocolMapping`（协议意图+params → 现有 `AssistantIntent`；create_event 换算展示字段）6 个单测。
- 手机：`PhoneAgent` 装配 `DeviceRuntime`（hub 公钥验签、按设备策略、L2 审批用 Keystore 签 `approval.v1`），
  执行器映射到现有 Android Intent 执行器；`DeviceIdentity.createApproval`。
- 顶栏新增“同步”：拉取一次 → 逐条提交 → L2 弹确认 → 执行 → 终态回执回传；结果写聊天与动作历史。
- 设置页补 Hub 公钥（JWK）与 kid；`isHubConfigured` 要求地址/令牌/公钥齐全。

### 已核验（模拟器 + 临时 mock hub，mock hub 未入库）

1. 电脑起临时 mock hub（用 `~/.dpa-sim` 的测试密钥签名信封），手机设置 Hub 地址/令牌/公钥。
2. 点“同步”：拉到一条 L2 `save_note` → 弹“远端任务待确认（L2）” → 点确认 →
   Keystore 签审批 → 执行 → 备忘 `20261102-132213_远端任务.txt` 落盘。
3. mock hub 收到回执：`{"status":"done","device_id":"phone-c1949faa",...}`；
   聊天显示“同步完成：0193f0a8-…：done”。
4. 测试：core 53 个（新增 ProtocolMapping 6 个）、desktop 25、手机 3，全绿；APK 构建通过。

### 未完成 / 已知限制

- **没有真实 hub**：设备注册、令牌签发、吊销、FCM 唤醒、WS 均未做；当前靠手动“同步”。
- 手机运行时去重状态在内存，重启后同一信封会重新处理（桌面端已有 SQLite 去重）。
- `open_app` 的 `app_id` 暂按包名处理，还没有端上应用目录映射 UI。
- `voice_command` / `task_handoff` 在手机上直接拒绝（语音/接力属后续阶段）。
- 回执发送失败只吞掉异常，没有重试队列。

## 19. 手机端跨重启幂等 — 2026-11-02 晚（续）

### 做了什么

- core：`ProcessedStore`（终态回执落盘、按 id 替换、上限 500、坏文件按空处理），4 个单测。
- 手机：`syncNow` 在执行前查 `processed.json`，命中直接回执（`duplicate`）不再执行；
  终态（done/failed/rejected/cancelled）写入 store；待确认的不落库。

### 已核验（模拟器 + mock hub 每次重投同一信封）

1. 第一次同步：L2 确认 → 执行 → 备忘 `20261102-132644_幂等.txt` 落盘 → 回执 done。
2. `am force-stop` 后重启 App，再同步：聊天显示 `…：duplicate`，没有弹确认框，**没有第二份备忘**。
3. mock hub 收到两条回执（均为 done，来自存储的终态），设备侧只执行了一次。
4. 测试：core 57 个（新增 4 个）、desktop 25、手机 3，全绿。

### 未完成

- 回执发送失败仍无重试队列/发件箱。
- store 只保留最近 500 条；更长期的去重窗口需要按 TTL 计算与持久化索引（接真实 hub 时再定）。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 20. 手机端回执发件箱与启动补发 — 2026-11-02 晚（续）

### 做了什么

- core：`Outbox`（先落盘再发送、指数退避、同 key 幂等、最多 8 次、坏文件按空处理），5 个单测。
- 手机：回执统一走 `deliverReceipt`（入队 → 发送 → 成功出队，失败记一次退避）；
  每次同步前补发，App 启动时也补发一次。
- 修了一个并发缺陷：`SettingsStore` 懒初始化未同步，启动时并发读取可能拿到 null，
  导致 `isHubConfigured` 假阴性、启动补发被跳过；改为 `synchronized` 初始化。

### 已核验（模拟器 + mock hub 第一次回执返回 500）

1. 同步执行后回执 POST 失败 → `outbox.json` 出现该回执（attempts=1，带下次重试时间）。
2. 重启 App：日志显示 `flush start configured=true outbox=1`、`flush end sent=Success(1) outbox=0`；
   hub 收到回执，`outbox.json` 变为 `[]`。
3. 修复前同一场景日志为 `configured=false`、队列不动——这是本轮定位到的真实竞态。
4. 测试：core 62 个（新增 Outbox 5 个）、desktop 25、手机 3，全绿；APK 构建通过。

### 未完成

- 重试只在 App 运行/下次同步时发生，没有 WorkManager 后台重试；退避上限 5 分钟、最多 8 次。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 21. 设备证书吊销 — 2026-11-02 晚（续）

### 做了什么

- 协议：新增 `protocol/schema/revocation.v1.json`（device_id、cert_hash、issued_by、
  issued_at、reason?、sig）。撤销单由手机（信任根）签名，引用被吊销证书的
  `cert_hash = SHA-256(JCS(cert 去掉 sig))`。
- core：`DeviceCert.hash()`；`DeviceCert.verify(..., revokedHashes)` 命中返回
  `CERT_REVOKED`；`Revocation.create/verify`（吊销单自身验签 + 时间窗校验）。
- core：`IssuedCertStore`（手机侧已签发证书台账，支持标记吊销并保存吊销单），4 个单测。
- 手机：`DeviceIdentity.createRevocation`；配对页新增「已签发的证书」列表，每项可
  「吊销」，吊销单导出到 `files/revocations/<cert_hash>.json`。
- 电脑：配置新增 `revoked_certs`；`Pairing.revoke` + `revoke-device <cert_hash>` 命令；
  `pair`/`pair-qr` 在校验证书时带上已吊销集合，命中即拒绝；`status` 显示已吊销数量。

### 已核验（模拟器 + 电脑端真实联调）

1. 配对成功：`verify-cert` → `证书有效：pc-abb2f79a`。
2. 手机端点「吊销」，电脑执行 `revoke-device`：
   `已吊销证书：1 个；本机证书已作废`。
3. 再 `verify-cert` → `验证失败：CERT_REVOKED`；`status` 显示
   `设备证书：无`、`已吊销证书：1 个`。
4. 篡改吊销单（改 reason 后重新序列化）被拒绝：`REVOCATION_SIGNATURE`。
5. 测试：core 71 个（新增 Revocation 5、IssuedCertStore 4）、desktop 27、手机 3，
   0 失败 0 错误；debug/release APK 与桌面 app-image 均构建通过。

### 未完成

- 吊销状态目前只落在电脑端配置文件与手机台账里，没有跨设备同步（真实 hub 应下发吊销列表）。
- 证书过期/轮换未做；吊销后重新配对需要手机重新签发新证书（当前 UI 已可再签，但没有轮换入口）。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 22. 手机回执发件箱接入 WorkManager — 2026-11-02 晚（续）

### 做了什么

- 手机：新增 `OutboxRetryWorker`（网络可用时补发 `outbox.json` 里的回执）与
  `OutboxScheduler.ensureScheduled`（唯一任务 `outbox-retry`，WorkManager 指数退避，KEEP 不重置退避）。
  回执首次发送失败、以及 App 启动补发后仍有剩余时都会登记该任务。
- 把补发逻辑抽成 `runOutboxPass(context)`，worker 与仪器测试共用；返回 SUCCESS / RETRY / FAILED。
- core：`Outbox` 的锁从实例级改为进程级——界面和后台 worker 各有一个实例，只锁实例会丢写。
- 仪器测试（真实 SettingsStore + 真实 HTTP + 真实 WorkManager 登记，不依赖系统 JobScheduler 的执行时机）：
  `OutboxScheduler` 登记的任务存在且未结束；随后直接跑一次补发，回执 POST 到 `/v1/device/receipts`
  并带上 Bearer 令牌，发件箱清空。

### 已核验（Android 16 模拟器）

1. 仪器测试日志：`OutboxScheduler: enqueued outbox-retry` →
   `OutboxRetryWorker: start outbox=1 configured=true` → `end sent=1 remaining=0`。
2. MockWebServer 收到 `POST /v1/device/receipts`，`Authorization: Bearer test-token`，body 含测试信封 id。
3. 首次尝试“等系统真的执行任务”失败：模拟器 Wi-Fi 探测不到外网（`PROBE_HTTPS www.google.com` 超时），
   `dumpsys` 显示任务已交给 JobScheduler 但 60 秒内未执行——这是测试环境的调度延迟，不是 worker 逻辑问题；
   改为直接调用同一条补发路径后稳定通过。
4. 单元测试：core 71、桌面 27、手机 6（新增 `OutboxSyncTest` 3 个）+ 仪器测试 1，0 失败 0 错误；
   release APK 构建通过。

### 未完成

- 后台补发依赖系统调度窗口；国产 ROM 的省电策略可能进一步延后，仍需真机过夜验证。
- 只有回执走发件箱；其他上行消息（未来同步推送）还没有队列。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 23. 手机离线本地规则快路径 — 2026-11-02 晚（续）

### 做了什么

- core：新增 `LocalIntentParser`，把常见中文口令解析成与 `ModelIntentParser` 同构的
  `AssistantIntent`（参数键与取值范围一致）：计时（含“半/一/十/二十三”等中文数字与
  “1小时30分钟”累加）、闹钟（冒号/“点/点半”、下午/晚上 24 小时换算）、备忘、打开应用、网页搜索。
  认不出就返回 null，不做猜测。
- 手机：`ChatViewModel.send` 在“未配置大模型”和“请求抛异常”两条路径上调用本地解析；
  命中后先说明“本地规则处理”，再走原有确认卡片与执行器，不联网、不绕过确认。
  未配置且认不出时，错误提示补充离线可用的五类口令。
- 测试：`LocalIntentParserTest` 18 个单测；新增 `OfflineFallbackTest` 2 个仪器测试，
  直接构造真 `ChatViewModel` 发送中文口令，断言生成 SET_TIMER(600) 与 SAVE_NOTE 卡片。

### 已核验（Android 16 模拟器）

1. 仪器测试 3 个全绿（离线快路径 2 + WorkManager 补发 1），`tests=3 failures=0 errors=0`。
2. 本地解析与手机界面接线均在设备上跑通：`10分钟后提醒我` → SET_TIMER/600；
   `记一下：离线备忘` → SAVE_NOTE/离线备忘。
3. 单元测试：core 89（新增 18）、桌面 27、手机 6，0 失败 0 错误。

### 未完成

- 本地规则只覆盖五类简单口令；建日历、复杂时间（“下周三”）、多意图一句话仍需模型。
- 未在真机/飞行模式下手动验证；仪器测试覆盖的是“未配置模型”分支，飞行模式走的是同一分支。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 24. 本机审计日志哈希链 — 2026-11-02 晚（续）

### 做了什么

- core：`AuditEntry` 增加 `prevHash` / `hash`；`AuditLog.append` 计算
  `hash = SHA-256(JCS(除 hash 外的字段))` 并链接上一条，返回带链字段的记录。
- `AuditLog.verify()`：逐条重算哈希（发现改内容 → `AUDIT_TAMPERED`），
  校验 `prevHash` 是否等于上一条 `hash`（发现删中间条/换序 → `AUDIT_CHAIN_BROKEN`）。
- 兼容两件事：旧版本没有 hash 的记录按“未链接前缀”跳过；日志按条数截断后，
  从保留区第一条重新起链，不会把正常截断误报为断链。
- 手机：动作历史页显示“审计链：完整（SHA-256 哈希链）”或具体异常；
  `ChatViewModel` 每次刷新动作历史时同步校验。

### 已核验

1. 单测 8 个（原 3 + 新增 5）：链接正确、篡改内容、删除中间条、旧日志兼容、截断后仍完整。
2. 模拟器真机界面（Android 16）读取到“审计链：完整（SHA-256 哈希链）”。
3. 测试：core 94、桌面 27、手机 6、仪器测试 3，0 失败 0 错误。

### 未完成

- 哈希链只能发现本机文件被改，不能防止攻击者整段重写/回滚；要防回滚需把链头锚定到可信位置（真实 hub 或系统备份）。
- 电脑端动作历史还没接同一条链（目前只有手机记录页使用 `AuditLog`）。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 25. 设备证书与吊销的金标准向量 — 2026-11-02 晚（续）

### 做了什么

- `gen_vectors.py` 增加第二把固定测试密钥，生成 `protocol/vectors/cert`（11 条）与
  `protocol/vectors/revocation`（9 条），期望值按 `protocol/spec/pairing.md` 手写。
- Python 参考实现新增 `verify_cert` / `verify_revocation`，检查顺序与 Kotlin 的
  `DeviceCert.verify` / `Revocation.verify` 一致；`runner.py` 按目录分派。
- Kotlin `VectorTest` 同步分派 cert/revocation，同一份向量两侧跑。

### 已核验

1. Python：`TOTAL 203 cases, 0 failed`（新增 cert 11、revocation 9）。
2. Kotlin：`VectorTest > allVectorsPass PASSED`（矢量总数门槛仍为 ≥150）。
3. 阳性对照：把 `revocation_valid` 的期望改成失败后，Python 报
   `FAIL [revocation] revocation_valid`，Kotlin `VectorTest` FAILED；重新生成后两侧恢复全绿。
4. 覆盖：过期、未生效、角色越权、篡改、未知字段、错误信任根、已吊销、错误签名者、
   超长 reason、类型错误。

### 未完成

- 向量只覆盖证书/吊销单条校验，不覆盖“先吊销再验证书”的组合流程（该流程由单测与真机联调覆盖）。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 26. 手机端 open_app 用本机应用目录解析 app_id — 2026-11-02 晚（续）

### 做了什么

- core：新增 `AppCatalog`（严格校验 app_id 与包名、最多 200 项）与 7 个单测。
- 手机：设置页新增“应用目录（JSON：app_id → 包名）”；保存时校验，同步前再校验一次。
  `PhoneAgent` 处理协议 `open_app` 时只接受本机目录里的映射，目录缺失直接拒绝——
  不再把云端给的 app_id 当包名启动应用。
- `ProtocolMapping` 的注释同步改为“app_id 只作展示，执行前必须由端上目录映射”。
- 本机模型/本地快路径的 `open_app`（按应用名让用户选）不受影响。

### 已核验

1. `AppCatalogTest` 7 个单测全绿（合法、空对象、非对象、非法 app_id、非法包名、非字符串、超限）。
2. 模拟器真机界面：设置页可见“应用目录（JSON：app_id → 包名）”，输入 `abc` 点保存后显示
   “应用目录必须是 JSON 对象，例如 {"wechat":"com.tencent.mm"}”，未写入存储。
3. 全量构建通过；core 测试 101 个、桌面 27、手机 6、仪器测试 3，0 失败。

### 未完成

- 手机端 `open_app` 暂不支持协议里的可选 `url` 深链；目前只按包名启动。
- 应用目录需要用户手工填包名；没有“从已安装应用里选”的图形界面。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 27. L3 生物识别审批密钥 — 2026-11-02 晚（续）

### 做了什么

- core：`Es256.signRawWith`（用已初始化的 Signature 签名）、`ApprovalProof.createSigned`
  （由调用方提供签名函数），原有 API 保持兼容。
- 手机：身份密钥在运行时只登记为 L2 审批方；新增独立的 `dpa-approval` 审批密钥
  （Keystore P-256，`setUserAuthenticationRequired(true)` + `AUTH_BIOMETRIC_STRONG`，
  每次使用都要认证，生物识别重新录入后失效）。审批证明的 `approver_device`
  为 `<deviceId>:approval`，运行时只信任这个最高 L3 的单独条目。
- 界面：L3 弹窗不再提供普通“确认执行”，改为平台 `BiometricPrompt` + `CryptoObject`；
  没有锁屏/未录入生物识别时按钮禁用，且 ViewModel 对任何非签名审批直接拒绝。
- 修了一个真机崩溃：Manifest 缺少 `USE_BIOMETRIC`，`canAuthenticate` 会抛
  `SecurityException`；这是交互式仪器测试第一次运行时发现的。

### 已核验（Android 16 模拟器 + 已录入指纹）

1. core：`l3RequiresTheSeparateApprovalKey`（身份密钥批 L3 → `APPROVAL_LEVEL`，
   单独审批密钥 → `done`）、`proofSignedThroughUnlockedSignatureVerifies` 通过。
2. 无锁屏时 App 日志：`approval key unavailable ... L3 will be refused`（fail closed）。
3. 设置 PIN 并录入指纹后，同一次同步日志变为 `approval key ready (biometric-gated)`。
4. 交互式仪器测试（`-e interactiveBiometric true`，期间 `adb emu finger touch 1`）：
   BiometricPrompt 解锁密钥 → 生成 L3 证明 → `ApprovalValidator` 校验通过，`OK (1 test)`。
5. 全量仪器测试：4 个用例，1 个交互式默认跳过，0 失败。

### 未完成

- 还没有用真实 L3 信封走完整 UI 弹窗（真实 hub 未就绪）；交互测试覆盖了密码学主链路。
- 审批密钥的生物识别失效/重新录入后的恢复流程未做（目前表现为签名失败）。
- 电脑端转发/验证手机 L3 证明的链路未接（没有 handoff 传输）。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 28. 设备侧速率限制升级 — 2026-11-02 晚（续）

### 做了什么

- `DeviceRuntime` 实现 policy 的 `envelopes_per_min > 10`：同一运行时实例 60 秒窗口内，
  第 11 条及以后的合法信封强制把确认级升到 L3（`max(计算级, 3)`）。
- 不丢弃信封、不自动执行；L1 也会变成待确认。没有可用 L3 审批方时任务停在
  `awaiting_confirm`，直到 TTL 过期——即 fail closed。
- 窗口按运行时实际时钟滑动；无效信封与重复投递不占额度。

### 已核验

1. `eleventhEnvelopeWithinAMinuteIsEscalatedToL3`：前 10 条 L1 执行，第 11 条
   `awaiting_confirm`、`requiredLevelOf=3`，L3 审批后才执行。
2. `rateLimitWindowSlidesAfterAMinute`：时钟前进 61 秒后，下一条恢复 L1 自动执行。
3. core 全量 105 个测试绿；桌面 27、手机 6、仪器测试 4（1 交互式跳过）不受影响。

### 未完成

- 窗口只在一个运行时实例内有效；手机每次“同步”会新建运行时，跨同步的洪水需要
  持久化窗口或由 hub 侧“暂停投递”来兜底（协议已写明 hub 责任）。
- 还没有在真实 L3 信封洪水的 UI 上做压力测试。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 29. 审批证明金标准向量 — 2026-11-02 晚（续）

### 做了什么

- `gen_vectors.py` 生成 `protocol/vectors/approval`（15 条），复用固定测试密钥与
  第二把“错误签名者”密钥；期望值按 `protocol/spec/error-codes.md` 手写。
- Python 参考实现新增 `verify_approval`，检查顺序与 Kotlin 的
  `ApprovalValidator.verify` 一致：结构 → 审批方 → 级别 → 时间窗 → nonce/重放 →
  信封哈希 → 签名。
- Kotlin `VectorTest` 与 `runner.py` 增加 `approval` 目录分派。

### 已核验

1. Python：`TOTAL 218 cases, 0 failed`（新增 approval 15）。
2. Kotlin：`VectorTest > allVectorsPass PASSED`。
3. 阳性对照：把 `approval_valid_l2` 的期望改为失败后，Python 报
   `FAIL [approval] approval_valid_l2`，Kotlin `VectorTest` FAILED；重新生成后恢复全绿。
4. 覆盖：有效 L2/L3、级别不足、超出审批方上限、未知审批方、过期、重放、
   错信封哈希、nonce 篡改、错误签名者、decision/v/字段/nonce 格式错误。

### 未完成

- 回执（receipt.v1）还没有金标准向量；它目前由运行时单元测试与端到端联调覆盖。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 30. 手机端 mock hub 与真实 L3 端到端 — 2026-11-02 晚（续）

### 做了什么

- `tools/device-sim/mock_hub.py`：用 `protocol/spec/signing.md` 的格式签发信封，
  提供 `GET /v1/device/inbox`、`POST /v1/device/receipts`、`POST /test/enqueue` 与
  `GET /test/state`；私钥落在系统临时目录，不进仓库；`selftest` 用 Python 参考校验器
  验证自己签出的信封。
- 修了一个设备侧真实缺陷：`DeviceRuntime` 之前不检查 `to.device`，现在目标不是本机的
  信封直接返回 `TARGET_NOT_ALLOWED`（与电脑端行为一致），新增单测。
- 修了一个 L3 UI 真实缺陷：`BiometricPrompt` 之前没有传 `CryptoObject`，生物识别会
  成功但审批密钥从未解锁；现在把签名器绑定到 `CryptoObject`。

### 已核验（Android 16 模拟器 + 已录入指纹 + mock hub）

1. 手机设置连接 mock hub（`http://10.0.2.2:8899`、Bearer 令牌、启动时打印的 JWK），
   点击“同步”拉取签名信封。
2. L1 `chat`：手机执行并回执，hub 日志
   `receipt 01a19ca9-…0001 done`，界面显示“同步完成：…：done”。
3. L3 `create_event`（attendees 触发 L3）：手机弹出 L3 对话框 → “生物识别确认” →
   按指纹 → 审批密钥签名 → 执行 → hub 收到
   `receipt 01a19caa-…0003 done`，界面显示 done。
4. 目标校验：给 `phone-2` 的信封在运行时被拒（单测 `envelopeForAnotherDeviceIsRejected`）。
5. 已知环境问题：AOSP 模拟器的 Calendar 应用在 `ACTION_INSERT` 时自身
   `ClassNotFoundException` 崩溃；手机端 `startActivity` 已成功，回执按“已交给系统界面”
   记 done，日历编辑器本身没有可用的验收证据。

### 未完成

- 速率限制（第 11 条升 L3）还没有在真机 UI 上做洪水演练；目前由 core 单测覆盖。
- mock hub 的 inbox 是“投递即出队”，没有真实 hub 的 at-least-once 重投；跨重启去重
  仍由手机的 `processed.json` 单测/旧联调覆盖。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 31. 回执契约校验与向量 — 2026-11-02 晚（续）

### 做了什么

- core：新增 `ReceiptValidator`（严格字段集、状态枚举、RFC3339 时间、error 结构、
  长度上限）；`DeviceRuntime` 的坏信封回执不再产生空 `envelope_id`，改用 `unknown`
  （与电脑端一致），并在单测里遍历 done/awaiting/rejected 回执逐个过校验器。
- Python：新增 `verify_receipt`，检查顺序与 Kotlin 一致。
- 向量：`protocol/vectors/receipt` 12 条（有效 done/awaiting/failed、未知字段/状态、
  空 id、非法时间、error 缺 code/未知字段/超长 detail、版本类型、缺 ts）；两侧 runner 分派。

### 已核验

1. Kotlin：`ReceiptValidatorTest` 10 个 + `runtimeReceiptsMatchTheReceiptContract` 1 个通过。
2. Python/Kotlin：`TOTAL 230 cases, 0 failed`，`VectorTest > allVectorsPass PASSED`。
3. 阳性对照：把 `receipt_valid_done` 的期望改成失败后，Python 报
   `FAIL [receipt] receipt_valid_done`，Kotlin `VectorTest` FAILED；恢复后全绿。
4. 全量构建通过；core 测试 117 个，桌面 27、手机 6、仪器测试 4（1 交互式跳过）。

### 未完成

- 回执的语义推进（状态机、非法转移丢弃）仍由 hub 实现；本仓库只校验结构。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 32. 回执校验联调与速率限制真机演练 — 2026-11-02 晚（续）

### 做了什么

- mock hub 的 `POST /v1/device/receipts` 现在先用 `verify_receipt` 校验结构，
  非法回执返回 400 并打印 `INVALID receipt`。
- 修了一个测试污染问题：`SettingsStore` 的写入从 `apply()` 改为 `commit()`。
  仪器测试在 `finally` 里恢复设置时，异步 `apply()` 可能随测试进程退出而丢失，
  曾把真实设备的 Hub 地址/JWK 覆盖成 MockWebServer 的临时值（本轮联调就复现了）。
  设置写入不频繁，同步落盘更可靠。

### 已核验（Android 16 模拟器 + mock hub）

1. 一次性投递 11 条 L1 `chat`，点击“同步”：
   前 10 条执行并回执 `done`，第 11 条被运行时速率规则升到 L3，手机弹出 L3 对话框
   （`L3 必须用本机生物识别批准`）。
2. 点“取消”后第 11 条回执 `rejected`；hub 收到 10 条 `done` + 1 条 `rejected`，
   全部通过 `verify_receipt`（HTTP 200，没有 `INVALID receipt` 日志）。
3. 手机界面显示 11 条同步结果（10 done + 1 rejected）。
4. 全量单测/构建不受影响：core 117、桌面 27、手机 6、仪器测试 4（1 交互式跳过）。

### 未完成

- 第 11 条没有做“生物识别批准后执行”的洪水版本（取消路径已覆盖升 L3）。
- mock hub 仍是投递即出队，不复刻真实 hub 的重投/状态机。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 33. 设置存储改用 Keystore AES-GCM — 2026-11-02 晚（续）

### 做了什么

- 新增 `data/SecureSettings.kt`：每个设置值用 Keystore 里的 AES-256-GCM 密钥
  单独加密，`base64(iv || ciphertext)` 写入普通 SharedPreferences；字段名作为 AAD，
  密文不能在字段之间搬运。写入用 `commit()`。
- `SettingsStore` 全面改用 `SecureSettings`，公开 API 不变；删除
  `androidx.security:security-crypto` 依赖（连同 catalog 里的条目）。
- 没有做旧格式迁移：升级后设置显示为未配置，需要重填一次（预发布阶段的有意取舍，
  已在手机 README 的已知限制里写明）。
- 修正手机 README 两条过期描述：权限清单补上 `USE_BIOMETRIC`；
  “手机尚未接入协议信封”改为“task_handoff/voice_command 仍不执行”。

### 已核验（Android 16 模拟器）

1. `SecureSettingsTest` 4 个仪器测试通过：写入/读取/跨实例持久化/删除、
   原始 XML 不含明文、篡改密文回退默认值、密文换字段名无法解密（AAD 生效）。
2. 仪器测试总数 8（含 1 个默认跳过的交互式 L3 测试），0 失败。
3. 构建不再出现 `EncryptedSharedPreferences`/`MasterKey` 的弃用告警；
   core 117、桌面 27、手机 6 全绿，release APK 可打包。

### 未完成

- 旧加密设置没有迁移（升级即重置一次）；如要保留，需要一次性迁移代码并继续带旧依赖一个版本。
- 设置值仍在读取时于调用线程解密（与旧实现一致的现状）；后续可改成异步缓存。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。

## 34. 应用目录选择器 — 2026-11-02 晚（续）

### 做了什么

- core：`AppCatalog.addPackages` 把新包并入目录，自动生成未占用的 `app1/app2…` 逻辑 id，
  已存在的包不重复添加；4 个单测。
- 手机：`IntentExecutor.listLaunchableApps` 列全部可启动应用；设置页新增
  “从已安装应用添加” 多选对话框（显示名称 + 包名），确认后合并进目录 JSON，
  仍走原有的合法性校验与保存流程。
- 修了一个保存逻辑缺陷：`保存编排设置` 之前无条件校验 Hub 地址，Hub 是可选项，
  地址留空时会挡住应用目录的保存；现在空地址/空公钥视为“不配置 Hub”。

### 已核验（Android 16 模拟器）

1. 设置页点“从已安装应用添加”，选中 Calendar → “添加 1 项”，目录变为
   `{"app1":"com.android.calendar"}`；点保存显示“编排服务设置已保存。”。
2. 退出设置再进入，目录仍为 `{"app1":"com.android.calendar"}`
   （同时验证了 `SecureSettings` 的持久化）。
3. core 测试 121 个（新增 `AppCatalogTest` 4 个）全绿；仪器测试 8（1 交互式跳过）。

### 未完成

- 生成的 id 是 `app1/app2…`，用户想用语义化 id 仍需手工改 JSON；没有搜索/过滤框。
- 选择器只列桌面可启动的应用；没有桌面入口的应用无法加入。
- 真实 hub、注册/令牌签发、FCM/WS 仍未做。
