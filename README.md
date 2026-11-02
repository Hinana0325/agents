# 多设备个人助理（单仓、契约先行）

开发计划与硬规则见 [DEVELOPMENT.md](DEVELOPMENT.md)。本仓库包含协议契约、共享核心、各端 App 与工具；**云端编排代码不在本仓库**（`tools/vector-runner/py` 只是协议的 Python 参考实现，不是云端 hub）。

## 目录

```
protocol/        # 契约层：只放数据
  schema/        #   envelope.v1、9 个意图 params、receipt、approval、capability
  policy/        #   intent-policy.v1.yaml（人类可读的权威策略）
  vectors/       #   金标准向量（唯一一份）
  spec/          #   JCS、签名、状态机、错误码、版本协商
core/kotlin/     # 共享核心（纯 JVM）：JCS、严格校验器、策略重算、ES256、模型输出解析
apps/phone/      # Android（Kotlin + Compose），现有手机 MVP
apps/watch/      # 设计文档 only（轻量端，经手机中转）
apps/desktop/    # 电脑执行节点：无头服务（Kotlin/JVM）+ 只读文件搜索存根（默认不接入协议，ADR 0006）
tools/vector-runner/  # Kotlin/Python 跑同一份向量；Python 侧为参考实现
tools/device-sim/     # 以 hub 身份签发信封，投递到电脑端收件箱（本地联调）
docs/adr/        # 0001-0006
docs/devices/    # 耳机等设备的说明
docs/legacy/     # 被 v1 取代的旧原型定义
```

分层规则：`apps/* → core/* → protocol/`（只读）；apps 之间禁止互相依赖。

## 当前阶段

**Phase 0（契约与脚手架）已完成**；**Phase 1（手机竖切）只完成了设备侧运行时**，传输层与云端 hub 尚未开始。

已核验：

- `protocol/vectors` 共 **183 条**（valid 35 / invalid 92 / policy 26 / injection 15 / signing 15）。
- 同一份向量，Kotlin（`./gradlew :core:kotlin:test`）与 Python（`uv run --with cryptography python tools/vector-runner/py/runner.py`）**均 0 失败**。
- 阳性对照：故意改坏一条向量后，Python 退出码 1、Kotlin 测试 FAILED。
- 设备运行时（`core/kotlin/.../runtime/`）：按 id 幂等去重、状态机、TTL 过期、回执、L2 待确认、L3 审批（绑定信封哈希 + nonce 防重放 + 级别权限），14 个单元测试全绿。
- 手机 App v0.2.0：对话历史持久化、记录页（备忘 + 动作历史 + 备忘撤销）、Keystore 设备身份与二维码配对、地址校验、连接测试；**签名 release APK 已在 Android 16 模拟器上端到端跑通**（设置→测试连接→发消息→确认卡→拉起系统闹钟→重启后历史仍在；保存备忘→动作历史→撤销）。
- 电脑端 v1：**可独立运行的无头服务 + Compose Desktop 主界面/托盘**（本地信封通道 + SQLite 跨重启去重与处理历史 + DPAPI 保护私钥 + 设置页 + save_note/web_search/open_app/set_timer 执行器 + L2 界面确认与本机审批密钥签名 + jpackage 自带 JRE 打包 + Windows 登录自启 + 设备证书手工配对）；桌面 25 个单元测试全绿，并用 device-sim 在本机端到端跑通（含 Compose 弹窗批准与重复投递去重）。
- 信任链：手机（Keystore P-256）给电脑签发设备证书，电脑“配对”页显示二维码，手机从相册扫码/粘贴 JSON 后签名，电脑 `trust-phone` + `verify-cert` 验签；已在模拟器与电脑之间完成真实跨设备配对。相机实时扫码、吊销/轮换未做。
- 传输：手机端 `HttpHubClient`（HTTPS 拉取 inbox + 回执）+ 设置页「编排服务」+ 顶栏「同步」；
  已把信封接入 `DeviceRuntime`（L2 手机确认、Keystore 审批签名、回执回传），并用临时 mock hub 在模拟器上端到端跑通；
  终态回执落盘，**重启后重复投递只回执不执行**。没有真实 hub/注册/令牌签发/FCM/WS。
- core 共 26 个测试全绿，debug/release APK 均可打包。

未验证 / 未完成（不要当成已完成）：

- **手机未在物理真机验证**（不同厂商时钟/日历实现、省电策略、Keystore 行为可能不同）。
- **电脑端密钥在 Windows 上由 DPAPI 保护**（TPM 未做；非 Windows 仍是明文文件）；本地目录通道不是安全传输。
- **两端都没有真实传输层**：手机还没有 WS/FCM 收信封的代码；电脑用本地目录通道替代。
- **没有云端 hub**（`signing/` 向量验证的是设备侧验签，不是"云真的签了"）。
- CI 工作流已写（两侧向量 + Android 构建/lint + 桌面测试 + gitleaks），但**从未在 GitHub 上跑过**。
- schema 与两份手写实现靠向量对齐，没有引入通用 JSON Schema 校验器做交叉检查。
- 配对已支持二维码（桌面生成、手机从相册扫描）与复制 JSON；相机实时扫码、证书吊销与轮换未做。
- 手表/耳机仍只有设计文档；电脑文件搜索默认不接入（ADR 0006 待拍板）；本地模型、收件箱/发件箱持久化、MSI 安装包、自动更新都未开始。

## 怎么跑

```bash
# 协议向量（Python 参考实现）
uv run --with cryptography python tools/vector-runner/py/runner.py

# 协议向量（Kotlin）
./gradlew :core:kotlin:test

# 手机 APK
./gradlew :apps:phone:app:assembleDebug

# 电脑端服务（测试 + 分发包 + 自带 JRE 的 app-image）
./gradlew :apps:desktop:service:test :apps:desktop:service:installDist
./gradlew :apps:desktop:service:packageAppImage

# 电脑端端到端联调（另开终端；见 apps/desktop/README）
uv run --with cryptography python tools/device-sim/sim.py install --desktop-home ~/.dpa-desktop --dry-run
uv run --with cryptography python tools/device-sim/sim.py send --intent save_note --params '{"text":"hello"}'
```

## 本机构建注意事项

- 仓库目录名是中文时，AGP 会硬失败，且 Android 单元测试会因 worker classpath 编码问题报 `ClassNotFoundException`。先挂一个 ASCII 盘符：`subst V: "<本仓库>"`，再 `cd V:/` 构建。
- `services.gradle.org` 的发行版下载会 307 跳到 github.com，本机 Java 取不到；已用目录联接把缓存里的 Gradle 8.11.1 挂到对应哈希目录，`gradlew` 可离线启动。
- `android.overridePathCheck=true` 写在 `gradle.properties` 里；ASCII 路径下它没有副作用。

## 明确不做

- 不 root、不 shell、不无障碍、不短信/电话、不做常驻后台。
- 设备之间只交换结构化信封，不传自由执行指令。
- 不在代码里写死密钥、服务地址与未定事项（手表系统、电脑系统、云托管、模型服务商）。
