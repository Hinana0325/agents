# 手机助理 v0.1

应用级、不需要 root 的个人手机助理最小可用原型。
包名 `cn.phoneassistant`，应用名“手机助理”，界面为简体中文、中性灰白配色。

> 状态说明（请先读）：源码已在本机首次编译通过（JDK 17 + Android SDK 35 + Gradle 8.11.1），
> 校验相关的 11 个单元测试已迁到 `core/kotlin` 并全绿，debug APK 可打包。
> **仍未在真机验证**：没有安卓设备可用，界面与 7 种意图的实际跳转只在静态层面成立。
> 校验规则不再在本目录：模型输出解析在 `core/kotlin/.../ModelIntentParser.kt`，
> 协议信封校验在 `core/kotlin/.../EnvelopeValidator.kt`。

## v0.1 能做什么

- 对话界面：消息列表、输入框、思考中提示。
- 意图卡片：需要确认的动作先展示“动作 + 参数”，点“确认执行”才执行，
  点“取消”则不产生任何副作用。
- 大模型客户端：OpenAI 兼容接口，服务地址和模型名在设置里填写，
  密钥由用户在设置里输入。
- 结构化意图（只有这 7 种，模型输出其他类型一律被拒绝）：
  | 意图 | 说明 | 确认方式 |
  | --- | --- | --- |
  | CHAT_ONLY | 纯聊天，只展示回复 | 不执行动作 |
  | WEB_SEARCH | 打开系统搜索，搜指定关键词 | 直接执行（低风险，不改动任何数据） |
  | OPEN_APP | 按显示名称找应用，用户在候选列表里选中后打开 | 必须点确认 |
  | SET_ALARM | 打开系统闹钟设置界面（小时、分钟、备注） | 必须点确认 |
  | SET_TIMER | 打开系统计时设置界面（秒数） | 必须点确认 |
  | CREATE_CALENDAR_EVENT | 打开系统日程新建界面（标题、日期、开始时间、时长、说明、地点） | 必须点确认 |
  | SAVE_NOTE | 把备忘保存到应用私有目录 `files/notes/` | 必须点确认 |
- 打开应用时不猜测：如果按名称找不到应用，会直接告诉用户换一个更准确的名字；
  如果有多个同名应用，会列出候选让用户自己选。
- 闹钟、日程、计时最终都在系统界面里再确认一次：本应用只负责把参数填进
  公开 Intent，不会绕过系统界面悄悄改动。

## v0.1 明确不做什么

- 不需要 root，不提权执行，不调用任何 shell。
- 不读取其他应用的界面或数据，不接管系统，不常驻后台服务。
- 不发送短信、不打电话、不付款、不删除或修改文件（备忘只写应用自己的私有目录）。
- 不收集、不上报、不接广告、不接统计；网络只发往用户自己在设置里填写的大模型服务地址。
- 不做离线本地模型、不做语音、不做跨应用连续操作、不做日程/闹钟的直接写入
  （一律走系统新建界面，需要用户在系统界面最后确认）。
- 不做任何需要危险权限的事：清单里唯一的权限就是联网。

## 密钥存放在哪里

- 全部设置（服务地址、模型名、密钥）都只放在 Android Keystore 保护的
  `EncryptedSharedPreferences`（见 `data/SettingsStore.kt`）中。
- 代码、构建配置、日志、聊天记录里都没有密钥，也没有写死的服务地址、模型名和个人信息。
- 密钥只在发起本次请求时放进 Authorization 请求头，用完即丢弃；
  界面只显示“已设置 / 未设置”，从不回显原文。
- 在设置里点“清除密钥”后，下一次对话会被直接拒绝，不会再调用接口。

## 怎么构建（需要 JDK 17 + Android SDK 35）

在仓库根目录运行（Gradle wrapper 与 version catalog 都在根上）：

```bash
subst V: "<本仓库所在目录>"     # 目录名含中文时必须；AGP 与单测 classpath 都会炸
cd V:
./gradlew :apps:phone:app:assembleDebug
./gradlew :core:kotlin:test        # 模型输出解析 11 例 + 协议向量 183 条
```

构建产物在 `apps/phone/app/build/outputs/apk/debug/`。首次构建需要联网下载依赖；
Gradle 发行版已缓存在本机，`gradlew` 可离线启动。

## 怎么设置密钥

1. 在手机上打开“手机助理”。
2. 点右上角“设置”。
3. 填写三项：服务地址（OpenAI 兼容接口的根地址，以 `/v1` 结尾）、模型名、密钥。
4. 点“保存设置”。三项有一项为空时，对话会被拒绝，并提示先去设置，
   不会发起任何网络请求。
5. 换密钥时在密钥框里重新输入并保存；留空则保持上次的设置不变。

## 目录结构

```
apps/phone/
├── README.md / MAPPING.md
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml        # 唯一权限：联网；<queries> 声明
        └── java/cn/phoneassistant/
            ├── MainActivity.kt         # 唯一入口
            ├── ui/Theme.kt             # 中性灰白主题
            ├── ui/ChatScreen.kt        # 对话页、意图卡片、设置页
            ├── chat/ChatViewModel.kt   # 状态机与确认分级
            ├── model/ChatMessage.kt    # 消息与待确认意图
            ├── llm/LlmClient.kt        # OpenAI 兼容客户端
            ├── llm/SystemPrompt.kt     # 只输出 JSON 的系统提示
            ├── intent/IntentExecutor.kt  # 只用公开 Intent 的执行器
            ├── data/SettingsStore.kt   # 加密设置存储
            └── data/NoteStore.kt       # 应用私有目录备忘存储
```

校验器与意图模型在共享核心 `core/kotlin/src/main/kotlin/cn/phoneassistant/core/`：
`ModelIntentParser.kt`（模型输出→意图）、`EnvelopeValidator.kt`（协议信封）、
`Jcs.kt`（签名规范化）、`ModelIntent.kt`（意图类型）。

## 已知限制（v0.1，诚实记录）

1. 已编译、已跑单元测试；**未在真机上验证**：界面、7 种意图的实际跳转、Keystore 存储都没有实机证据。
2. 模型输出质量决定意图质量：模型一旦返回非法 JSON 或非法参数，
   应用只会拒绝并提示重说，不会“尽力猜”着执行。
3. 打开应用依赖系统桌面可启动应用列表：有的应用没有桌面入口时无法打开，
   应用会如实报错，不会绕过。
4. 闹钟、日程、计时依赖手机上已安装的系统应用能处理对应公开 Intent；
   有的厂商系统改了行为时，可能需要用户在系统界面手动补填。
5. 备忘只存在本机，没有同步、没有导出、卸载即丢失。
6. 加密存储依赖设备 Keystore：建不出来时应用不会崩溃，也不会退回明文，
   而是明确提示“本机加密存储不可用”，设置无法保存。
7. `androidx.security:security-crypto` 为稳定版 `1.1.0`，但该版本起
   `EncryptedSharedPreferences`/`MasterKey` 已被官方标记 deprecated；
   换用 Keystore 直管 AES-GCM（或 Tink）是后续任务。
8. 手机尚未接入协议信封：`HANDOFF_TASK`/`VOICE_COMMAND` 对应的
   `task_handoff`/`voice_command` 还没有收发代码，属 Phase 1。
