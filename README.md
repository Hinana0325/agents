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
apps/desktop/    # 设计文档 + 只读文件搜索存根（默认不接入协议，见 ADR 0006）
tools/vector-runner/  # Kotlin/Python 跑同一份向量；Python 侧为参考实现
docs/adr/        # 0001-0006
docs/devices/    # 耳机等设备的说明
docs/legacy/     # 被 v1 取代的旧原型定义
```

分层规则：`apps/* → core/* → protocol/`（只读）；apps 之间禁止互相依赖。

## 当前阶段

**Phase 0（契约与脚手架）已落地核心部分**，Phase 1（手机竖切）尚未开始。

已核验：

- `protocol/vectors` 共 **183 条**（valid 35 / invalid 92 / policy 26 / injection 15 / signing 15）。
- 同一份向量，Kotlin（`./gradlew :core:kotlin:test`）与 Python（`uv run --with cryptography python tools/vector-runner/py/runner.py`）**均 0 失败**。
- 阳性对照：故意改坏一条向量后，Python 退出码 1、Kotlin 测试 FAILED。
- 手机 App 编译通过，core 的 11 个模型输出解析用例 + 向量用例全绿，debug APK 可打包。

未验证 / 未完成（不要当成已完成）：

- **没有真机**：手机 App 的界面、7 种意图跳转、Keystore 存储只在静态层面成立。
- CI 工作流已写（`.github/workflows/ci.yml`），但**从未在 GitHub 上跑过**；本地用同样命令验证过。
- 没有云端 hub、没有 device-sim、没有传输层；信封由谁签发、如何唤醒拉取都还是设计。
- schema 与两份手写实现靠向量对齐，没有引入通用 JSON Schema 校验器做交叉检查。
- 手表/电脑/耳机仍只有设计文档和存根；电脑文件搜索默认不接入（ADR 0006 待拍板）。

## 怎么跑

```bash
# 协议向量（Python 参考实现）
uv run --with cryptography python tools/vector-runner/py/runner.py

# 协议向量（Kotlin）
./gradlew :core:kotlin:test

# 手机 APK
./gradlew :apps:phone:app:assembleDebug
```

## 本机构建注意事项

- 仓库目录名是中文时，AGP 会硬失败，且 Android 单元测试会因 worker classpath 编码问题报 `ClassNotFoundException`。先挂一个 ASCII 盘符：`subst V: "<本仓库>"`，再 `cd V:/` 构建。
- `services.gradle.org` 的发行版下载会 307 跳到 github.com，本机 Java 取不到；已用目录联接把缓存里的 Gradle 8.11.1 挂到对应哈希目录，`gradlew` 可离线启动。
- `android.overridePathCheck=true` 写在 `gradle.properties` 里；ASCII 路径下它没有副作用。

## 明确不做

- 不 root、不 shell、不无障碍、不短信/电话、不做常驻后台。
- 设备之间只交换结构化信封，不传自由执行指令。
- 不在代码里写死密钥、服务地址与未定事项（手表系统、电脑系统、云托管、模型服务商）。
