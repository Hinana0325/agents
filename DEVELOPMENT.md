# 多设备多 Agent 个人助理：开发文档（交给 Agent 执行用）

版本：v1，2026-11-02
依据：以 Claude（Sonnet 5.5）给出的项目结构与开发方案为准；ChatGPT 的版本不作为依据，两者不一致时以本文为准。
用户修订（2026-11-02，已融入本文；Claude 的修订版因免费额度用完、23:00 恢复后才生成，届时如与本文有出入，以修订版更新本文）：
优先级为手机第一、电脑第二、云端第三；耳机暂缓（唯一例外是苹果 AirPods，未来单独评估，不进主线）；手表只做轻量接收与确认，不做手机一样的 GUI 应用；电脑、云端、手机在硬件允许时都可跑本地小模型，做相册整理、文件夹分类等本地任务。
未定事项（Agent 不得默认已定）：手表系统与型号、电脑系统、云服务器托管方式、大模型服务商与密钥来源。

## 1. 产品定义

系统由手机、手表、电脑、耳机、云服务器组成。云服务器是编排中枢，负责推理、长期记忆、跨设备状态、定时任务、任务分解与派发；手机、手表、电脑各是一个 Agent 节点；耳机只是语音出入口，不跑模型，拾音与播音，唤醒和语音识别跑在手机或电脑上。

优先级与职责：
- P0 手机：主控制中心、信任根。负责交互、确认、安全、个人数据、本地业务。所有敏感操作必须经过手机确认。手机离线时基本功能仍然成立。
- P1 电脑：高算力执行节点。负责本地模型、文件操作、复杂任务、桌面自动化。电脑离线时，手机不能因此失去控制能力。
- P2 云端：远程智能与协调中心。负责大模型、长期记忆、跨设备任务、调度。云端不再是唯一大脑，只做本地做不了或不适合本地做的事；云端不可用时，已具备本地执行条件的任务仍可运行。
- P3 手表：轻量交互终端。只做接收命令、通知、提醒、轻量确认。手表不做复杂交互，不做手机一样的 GUI 应用；手表密钥只有 L2 审批权，不能批准 L3。
- 暂缓 耳机：可选音频入口，现阶段不进入主线。未来只单独评估苹果 AirPods（芯片性能较高），其他耳机暂缓。

## 2. 不可违背的约束（硬规则）

- 应用级，不 root。清单里唯一权限原则上是联网；无 shell、无无障碍、无短信、无电话、无后台随意拉起。
- Agent 之间只交换结构化的意图信封，不传自由执行指令。设备之间不直接互发，设备只接受云端签名的信封。
- 意图白名单，共 9 种：open_app（打开应用）、set_alarm（设闹钟）、create_event（建日历）、set_timer（计时）、web_search（网页搜索）、save_note（存备忘）、chat（纯聊天）、task_handoff（任务接力）、voice_command（语音指令）。
- voice_command 是“输入信封”，携带转写文本，不可直接执行，必须先解析为其他具体意图，产出的意图继承 voice 来源标记。
- task_handoff 是“向云端提交的跨设备转交请求”，内含一个意图提案，深度不超过 1，不得是 task_handoff 或 voice_command 本身；云端重新校验后签发新信封给目标设备，parent 指向原请求，级别取 max(2, 提案级别)，污点与语音标记随之继承。
- 大模型只提“意图提案”，不碰元数据，不定确认级。确认级由策略引擎算，设备永远独立验签并复核。
- 确认分级：L1 自动执行；L2 需本人确认（任一受信界面）；L3 仅手机界面加生物识别。用户设置可调高级别；调低仅限可放宽项，且永不低于硬下限；L2 以上的口头确认无效，必须在界面上点。
- 污点传播：本轮上下文含不可信内容（网页、备忘、日历标题、文件名里的指令等），产出的信封 tainted=true 且级别加 1，上限 L3；存下来的备忘读回来仍是不可信输入。
- 本地模型和云端模型都只能提出执行建议，不能绕过统一安全策略。每个设备本地也必须有独立的安全策略。
- 密钥只存本机加密存储（Keystore / DPAPI / TPM），不回显原文，不写死在代码里。大模型 API Key 只在云端，不上设备；设备密钥不上云、不备份，丢了就重新配对。
- 幂等：信封 id（UUIDv7）同时是幂等键，同一信封重复投递只执行一次；设备用本地已处理 id 去重，保留时间不少于最大 TTL 加偏移窗口。
- 最坏情况保证：云端被攻破或提示注入成功时，攻击者最多让设备执行 L1 白名单动作，且受参数约束和速率限制；L2/L3 需要设备本机密钥签的审批证明，云端伪造不了。

## 3. 项目结构（目标结构，单仓、契约先行）

改一个意图的字段，要同时改 schema、向量和各端校验器。分仓必然出现版本错位，而在这里版本错位就是安全洞。金标准向量只能有一份。

```
assistant/
├── protocol/                          # 契约层：只放数据，不放代码
│   ├── schema/
│   │   ├── envelope.v1.json           # 信封外壳：路由/签名/ttl/origin/tainted/level
│   │   ├── intents/                   # 9 个意图的 params schema，均 additionalProperties:false
│   │   ├── receipt.v1.json            # 设备回执
│   │   ├── approval.v1.json           # 用户确认证明（绑定信封哈希）
│   │   ├── capability.v1.json         # 设备能力声明
│   │   └── sync/                      # 同步实体与增量批
│   ├── policy/intent-policy.v1.yaml   # 每个意图：基础确认级、约束、升级规则、可达设备
│   ├── vectors/                       # 金标准向量：valid / invalid / policy / injection / signing
│   ├── spec/                          # JCS 规范化、签名字段、状态机、错误码、版本协商
│   └── CHANGELOG.md
├── core/kotlin/                       # 手机/手表/电脑共用，纯 JVM，不依赖 Android
│   └── envelope/ validator/ policy/ crypto/ approval/ testkit/
├── apps/
│   ├── phone/                         # Android（Kotlin + Compose），现有手机代码迁入
│   ├── watch/                         # 轻量端：通知/命令接收/提醒/轻量确认，经手机中转，不直连云
│   └── desktop/                       # 电脑 Agent
├── services/hub/                      # 云端编排中枢（Python）
├── tools/
│   ├── device-sim/                    # 假设备：说协议的 CLI，没真机也能测云端
│   ├── vector-runner/                 # Kotlin / Python 各一个入口，统一跑向量
│   └── codegen/                       # schema → Pydantic 模型
├── eval/                              # utterances/ injection/ audio/ runners/（夜跑）
├── infra/                             # compose/ backup/ secrets（只放 sops/age 加密后的密文）/ otel
├── docs/                              # architecture/ adr/ threat-model.md runbook/ devices/
└── .github/workflows/                 # 按路径触发；protocol/** 触发全部
```

耳机没有代码目录：它是手机/电脑里登记的 audio_endpoint 能力，未来只评估 AirPods。

现状与目标的对应关系（Agent 接手时先核对，不要假设已完成）：
- 本仓库现有 phone-agent/ 对应目标 apps/phone/ 的起点；watch-agent/、pc-agent/ 目前只有设计文档和只读存根；protocol/ 已有信封定义雏形。
- 云端编排不在本仓库内，另行存放；目标结构里的 services/hub/ 是云端应有的位置，落地时再决定是单仓合并还是独立仓库，不要擅自把云端代码塞进本仓库。
- 手机 Agent 目前未编译、未测试、未在真机验证；手表、电脑、耳机节点目前只有设计文档和存根，不是已实现的产品。

分层规则：依赖只向下，apps/* → core/* → protocol/（只读）；apps 之间禁止互相依赖；hub 只依赖 protocol/。
兼容性：v 是大版本；小版本只增可选字段；设备在能力声明里报告支持的 proto_minor，云端只发它认识的字段，设备对未知字段一律拒绝。
设备端校验器用手写的严格解码（kotlinx.serialization，ignoreUnknownKeys=false），不依赖通用 JSON Schema 库；JSON Schema 用于云端校验、约束大模型输出和文档，两边靠向量对齐。

### 手机端内模块

```
apps/phone/
├── app/                      # 入口、DI、导航
├── feature/
│   ├── chat/                 # 现有 Compose 聊天界面（迁入）
│   ├── confirm/              # L2 底部弹层；L3 全屏 + BiometricPrompt
│   ├── devices/              # 配对/吊销、权限与电池白名单引导
│   └── audit/                # 动作历史、撤销
├── agent/
│   ├── runtime/              # inbox/outbox、状态机、重试、去重
│   ├── executors/            # 一个意图一个执行器（统一接口）
│   ├── transport/            # WS 客户端、FCM 唤醒后拉取、手表桥
│   ├── voice/                # KWS/VAD/ASR/TTS、音频路由、会话服务
│   └── security/             # Keystore、签名、审批密钥、吊销缓存
└── data/                     # Room（收件箱/发件箱/审计/备忘）、加密存储
```

### 电脑端内模块

```
apps/desktop/
├── service/                  # 无头服务：transport、runtime、executors（复用 core）
├── ui/                       # 托盘、设置、L2 确认弹窗
├── platform-win/             # ShellExecute、Toast、DPAPI/TPM 适配（系统未定前为占位，勿默认已定 Windows）
├── capabilities/             # 现有只读文件搜索（迁入，受策略约束，见第 7 节）
└── installer/                # 打包、开机自启、自动更新
```

### 云端内模块

```
services/hub/src/hub/
├── api/            # http: enroll/sync/admin；ws_gateway
├── domain/         # 纯逻辑无 IO：envelope、policy、taint、plan（DAG/状态机）
├── orchestrator/   # understand（大模型→提案）· planner · router · dispatcher · reducer
├── llm/            # 网关：多档模型/超时/降级/预算；prompts/（带版本）；redact
├── memory/         # 事实库 + 检索 + “记忆写入提案”
├── scheduler/      # 持久化定时任务（once/cron，misfire 策略）
├── devices/        # 注册表、能力、presence、吊销
├── security/       # 签名、审计哈希链、限流、kill switch
└── storage/        # SQLAlchemy 模型、alembic 迁移
```

## 4. 信封与信任链

协议里只有三类消息：信封（唯一能触发执行的）、回执/审批（只推进状态）、同步增量（只改数据），后两类不含可执行内容。

信封示例字段：v、id（UUIDv7，同时是幂等键）、trace、ts、ttl_s、from（role、kid）、to（device）、intent、params、origin（kind、via、tainted）、level（策略引擎算出，大模型不参与）、parent（接力重新签发时指向原请求）、sig（ES256 over JCS，除 sig 外全部字段）。

生命周期：
1. 输入（文本/语音转写）带 origin 标记：user_text | user_voice | scheduled | derived。
2. 云端：understand（大模型只产出意图提案）→ schema 校验 → 策略引擎定级（origin/taint/速率）→ router 选设备 → 签名 → 入 device_inbox；WS 推送，设备离线/休眠则 FCM 唤醒后设备拉取。
3. 设备端：验云端签名、kid 未吊销 → TTL 未过期、id 未处理过 → 本机校验器独立复核（不信云）→ 本机策略重算 level，取 max(信封.level, 本机.level)。
   - L1：执行 → 回执。
   - L2：任一受信界面确认 → 审批证明（绑定信封哈希）→ 执行。
   - L3：仅手机界面加生物识别 → 审批密钥签名 → 执行；执行端不是手机时，证明随信封转发，由执行端自己验。
4. 云端 reducer：回执推进状态机 / 计划 DAG 的下一个节点。

传输与同步：
- 设备到云是出站长连（WSS），设备不开入站端口；手表 v1 经手机中转；耳机不入网。
- 唤醒：FCM 高优先级 data 消息只带 {hint:"pull"}，不带内容，设备醒来后用 HTTPS 拉取 inbox；静默高优先级消息可能被系统降级，拉取后尽量产生可见反馈。
- 投递：云端 device_inbox（device_id、seq、envelope、state、expires_at）至少一次投递；设备按 id 去重，保证幂等。
- 状态机：created → queued → delivered → awaiting_confirm → executing → done | failed | expired | rejected | cancelled；只有回执能推进状态，非法转移丢弃并记审计。
- 时钟：握手时估计偏移，设备用 now+offset 判过期，偏移超过 60 秒就拒收并提示校时；TTL 建议：语音来源 120 秒，待确认 5 分钟，定时预置不超过 24 小时。
- 发件箱：设备产生的消息先写本地 outbox，再由 WorkManager 指数退避加抖动重试，同样靠 id 幂等。
- 同步 API：POST /v1/sync/push（带 base_rev）、GET /v1/sync/pull?cursor=（增量加墓碑）；服务端分配单调递增 seq，排序不依赖设备时钟。

冲突策略（原则：每类数据只有一个写者）：
- 聊天记录：权威方为云，只追加，按 server seq 排序，无冲突。
- 备忘：权威方为云（端有副本），条目不可变加修订链；两端离线各改一次就保留两个版本并标“冲突副本”，由用户合并，不做自动文本合并。
- 设置/偏好：权威方为云，字段级 LWW（混合逻辑时钟）；例外：确认级相关设置只在手机本机生效，不经云下行。
- 日历事件：权威方为手机系统日历，云只存镜像和 event_uid 映射；创建带幂等键，避免重复；以系统日历为准。
- 闹钟/计时器：权威方为执行它的设备，云只记“已下发”，真实状态以设备回执为准。
- 定时任务：权威方为云，单写者；端上修改等于发一个意图给云，不直接改。
- 长期记忆：权威方为云编排器，端上只能提交“记忆建议”，由云合并；手机端可审阅/删除。
- 在线/电量/当前交互设备：各设备自报，软状态加 TTL，不做冲突解决。

离线降级：
- 手机断网：仍可用本地快路径（规则解析）：计时、闹钟、备忘、打开应用、建日历；已到 inbox 的信封照常验证与确认；chat 和需要大模型的请求明确提示离线；有副作用的意图不排队静默补执行（除非用户选“联网后再说”）。
- 手表离开手机：仍可用本地计时、最近动作缓存；L2 请求回落手机通知；手表不自行批准任何东西。
- 电脑离线：发往电脑的信封在云端排队到 TTL；过期则回执 expired，手机提示“未送达”。
- 云不可达：手机本地快路径；时间敏感的定时（闹钟）由云提前下发到设备本地系统闹钟，到点不依赖网络。
- 确认端不可达：绝不默认通过，等到 TTL 过期。

## 5. 确认策略（起手版，intent-policy.v1.yaml）

```
version: 1
levels: {1: auto, 2: confirm, 3: confirm_phone_biometric}
intents:
  chat:         {base: 1, targets: [phone, watch, pc]}
  web_search:   {base: 1, targets: [phone, pc], params: {query: {max_len: 200}}}
  set_timer:    {base: 1, targets: [phone, watch, pc], params: {seconds: {min: 1, max: 86400}}}
  set_alarm:    {base: 1, targets: [phone],
                 escalate: [{when: "repeat != none or created_24h > 5", to: 2}]}
  save_note:    {base: 1, targets: [phone, pc], params: {text: {max_len: 2000}}}
  open_app:     {base: 1, targets: [phone, pc],
                 params: {app_id: {enum_from: device_catalog}, url: {scheme: [https], optional: true}},
                 escalate: [{when: "app_class in [finance, payment, system_settings]", to: 2},
                            {when: "url present and host not in allowlist", to: 2}]}
  create_event: {base: 2, targets: [phone], relaxable: true,
                 escalate: [{when: "attendees > 0", to: 3}]}
  task_handoff: {base: 2, relaxable: true,
                 rule: "level = max(2, proposal.level); proposal 深度≤1 且不得是 task_handoff/voice_command"}
  voice_command: {executable: false, rule: "只能被解析为其它意图；产出的意图继承 origin=voice_*"}
global:
  - {when: "tainted", delta: +1}
  - {when: "origin.kind == scheduled", rule: "不得静默执行 L2+，只能进入待确认"}
  - {when: "envelopes_per_min > 10", rule: "暂停该设备投递并升到 3"}
  - {rule: "L2+ 的口头确认无效，必须在界面上点"}
  - {rule: "用户设置可调高级别；调低仅限 relaxable 项，且永不低于硬下限"}
```

## 6. 各端技术选型

- 云 hub：Python 3.12、FastAPI、Pydantic v2、SQLAlchemy 2 + Alembic、Postgres 16 + pgvector、Caddy、Docker Compose（单机）。队列与定时先用 Postgres（SKIP LOCKED 任务表或 procrastinate），v1 不上 Redis/Kafka/K8s/Temporal；presence 放内存加 LISTEN/NOTIFY；多实例再引入 Redis。区域选离常用设备近的（语音链路对延迟敏感）。
- 大模型网关：自写薄层，与供应商无关，小/中/大三档模型，结构化输出（把意图 schema 当工具 schema 传入），超时/重试/降级、日预算；模型名和提示词版本放配置并带 hash；低温度；输出必须过校验，失败最多重试一次，再转为向用户澄清。
- 手机：Kotlin + Compose（现有）、kotlinx.serialization、Room（SQLCipher）、OkHttp WebSocket、WorkManager、FCM、Android Keystore（加 Tink）、BiometricPrompt。无 GMS 机型：前台服务长连，或 UnifiedPush/ntfy。
- 手表：轻量端，经手机中转；v1 不直连云；非独立 GUI 应用。系统未定前不默认具体手表系统，落地时按实际硬件与系统再定。
- 电脑：Kotlin/JVM + Compose Desktop（托盘加确认弹窗），复用 core；Windows 适配层用 JNA/FFM；SQLite；密钥放 DPAPI/TPM(CNG)；打包出 MSI。系统未定前不默认已定 Windows；需要全局热键、UI Automation、系统搜索索引等深度集成时，改 C#/.NET 8 或 Rust，校验器靠向量对齐。
- 语音：sherpa-onnx（唤醒词加 VAD 加 ASR 一套库），TTS 先用系统 TTS。多语种混说选多语种模型；单一语言选流式小模型，延迟更低。备选唤醒：Porcupine、openWakeWord。
- 耳机：无代码，作为 audio_endpoint 登记；A2DP 播放加 HFP/LE Audio 麦克风；按键经 MediaSession 映射为“按键说话”；路由用 AudioManager.setCommunicationDevice()，不用已弃用的 startBluetoothSco()。HFP 窄带麦克风会推高识别错误率，先实测；不行就“手机/电脑麦克风收音加耳机播放”。本阶段暂缓，未来只评估 AirPods。
- 协议与密码学：JSON Schema 2020-12、RFC 8785 JCS、ES256（P-256）、UUIDv7（RFC 9562）、TLS 1.3 加证书固定（带备用 pin）。信封里不放浮点数，时间用 RFC 3339 字符串；P-256 是 Keystore/StrongBox、Windows TPM、Secure Enclave 的最大公约数。

平台与库事实（直接影响设计，Agent 落地前先复核）：
- 从 Android 14 起，开机广播接收器不能拉起麦克风类型的前台服务；Android 15 又把同样的限制扩大到数据同步、相机、媒体播放、电话、媒体投屏等类型。重启后唤醒监听无法自动恢复，需要用户可见的动作（比如打开 App；通知按钮是否可行请在真机上验证）。不要把“重启自动恢复唤醒词”写进验收标准。
- Android 17 起，后台的音频播放、音频焦点请求和音量调节必须由用户有意触发：不在有效生命周期内时，播放和音量调用会静默失败，焦点请求返回失败；对 targetSdk 37 的应用，前台服务还必须具备 while-in-use 能力（持有精确闹钟权限并使用闹钟用途音频流的情况除外）。语音会话要由用户动作拉起并放进前台服务；需要在后台主动出声的提醒，走闹钟/通知通道，不要指望后台 TTS。
- Android 17 引入了按设备总内存设定的应用内存上限，并提供 adb 的 memory-limiter 命令用于在测试机上调整或关闭限制，端上 ASR/KWS 模型的常驻内存要纳入预算并压测。同一版本还限制了每个 App 可持有的 Keystore 密钥数量，只保留少量长期密钥，不要按会话或信封生成密钥。
- 官方 API 参考已把 androidx.security.crypto 里的 EncryptedFile、EncryptedSharedPreferences 和 MasterKey 标为弃用，MasterKey 的替代建议是直接使用 Android Keystore。本地加密存储改用 Keystore 管理的 AES-GCM（或 Tink），Room 用 SQLCipher 或字段级加密。现有手机代码里如仍用该 alpha 加密库，正式发布前换稳定实现。
- sherpa-onnx 在本地提供流式与非流式语音识别、TTS、VAD 和关键词唤醒，覆盖 Android 和 Windows，并带 Kotlin、Java、C#、Python 等语言绑定，手机和电脑能共用同一套语音栈；模型库里有覆盖中、粤、英、日、韩的 SenseVoice 多语种模型，适合混说场景。

手机执行器落地要点（应用级，不 root）：
- open_app：端上维护“逻辑 app_id → 包名/Intent”的目录，云端只能给 id，不能给包名；queries 只声明目录里的包；后台不能随意拉起 Activity，App 不在前台时降级为“发通知，点按后打开”。
- set_alarm / set_timer：优先 AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER（交给系统时钟 App，最稳）；兜底自实现用 AlarmManager.setAlarmClock()，并在开机广播里重新登记。
- create_event：ACTION_INSERT 唤起系统日历编辑页，用户点“保存”就是内建的一次确认；静默写入（WRITE_CALENDAR）只在用户显式开启“受信日历”后使用。
- web_search：query 由云给，搜索引擎的 URL 模板固定在端上，云端不能指定 URL。
- save_note：写自有加密表，带 source/tainted 标签。
- 所有 Activity/Receiver 默认 exported=false，Intent 一律显式。

## 7. 本地小模型：独立能力层（用户修订新增）

本地模型层统一管理各设备的本地模型，包括模型选择、推理路由、资源管理、能力降级。本地模型同样受白名单和确认分级约束，不能因为“本地”就绕过安全策略；敏感数据尽量本地处理，不上传云端。

适合本地做的：相册整理、文件夹分类、文件重命名、本地搜索、简单问答、意图分类、轻量语音转文字、日程提醒、本地备忘整理。
必须走云端大模型的：复杂推理、多步骤任务分解、跨设备协调、长期记忆检索、需要广泛知识的问答、需要最新信息的搜索、复杂代码生成、情感理解与复杂对话。

按算力分档（在硬件承受范围内用能力最高的模型，不要为了追求大模型而牺牲响应速度和稳定性）：
- 旗舰手机（A17 Pro 及以上、骁龙 8 Gen 3 及以上）：可跑 3B-7B 参数模型，如 Qwen2.5-3B、Phi-3-mini；适合意图分类、简单问答、本地整理。
- 中端手机（A15-A16、骁龙 8 Gen 2）：可跑 1B-3B 参数模型，如 Qwen2.5-1.5B、Phi-3-mini 量化版；适合意图分类、轻量整理。
- 入门手机：只跑 0.5B-1B 参数模型，如 Qwen2.5-0.5B；只做意图分类和简单确认。
- 高性能电脑（M1 Pro 及以上、RTX 4060 及以上）：可跑 7B-14B 参数模型，如 Qwen2.5-7B、Llama-3.1-8B；适合文件整理、批量任务、复杂本地任务。
- 中端电脑（M1、GTX 1660 及以上）：可跑 3B-7B 参数模型，如 Qwen2.5-3B、Phi-3-medium；适合文件整理、简单批量任务。
- 入门电脑：只跑 1B-3B 参数模型；只做轻量整理。
- 云服务器：跑 70B 以上大模型，如 Qwen2.5-72B、Llama-3.1-70B；适合复杂推理、跨设备协调。

本地模型层的关键设计：模型路由（根据任务类型、设备算力、当前负载选择模型）；资源管理（模型加载、卸载、内存占用控制）；能力降级（本地模型处理不了时，自动转云端或提示用户）；隐私保护（敏感数据尽量本地处理）。

电脑现有的只读文件搜索不在 9 种白名单内，处理方式二选一，由用户拍板后执行，不要擅自接入：
- 选项 A：v1 不接入，最稳。
- 选项 B：协议 v1.1 新增第 10 种 local_search：只读、仅限用户配置的根目录、默认只回文件名/路径/短摘要、结果一律 tainted、不得直接流入 web_search/save_note/任何外发。文件内容是提示注入和外带的经典入口，必须先有污点机制。

## 8. 分阶段开发方案（按用户修订的优先级重排）

粗估按 1 人全职，总计约 19-22 周（Claude 原估算，未按重排重新估算；兼职乘 2-3，并留 30% 缓冲）。每阶段结束都是一个能日常使用的版本；每阶段硬时间盒，超时砍功能不砍测试；新增意图必须走 ADR 加 schema 加策略加向量加评测。

阶段 0 · 契约与脚手架（1-2 周）
- 目标：把“信封加校验加确认策略”冻结成可执行的契约。
- 交付物：protocol/schema/*（信封、9 个意图 params、回执、审批、能力声明）、intent-policy.v1.yaml、spec/；vectors/ 不少于 150 条（valid、invalid 越界/未知字段/危险 scheme/超长、policy 升级/单调性、injection 备忘/网页文本里夹带指令、signing 篡改/过期/重放）；现有手机校验器迁入 core/kotlin/validator 并通过全部向量；Python 侧 hub.domain 同样通过；monorepo 加 CI（两侧向量、gitleaks、lint）；ADR 0001-0006（单仓、协议格式、唤醒后拉取、单写者同步、确认分级、文件搜索处理）。
- 验收：同一份向量 Kotlin 与 Python 各 100% 通过；故意改坏一条向量，CI 必须变红。

阶段 1 · 手机核心（3-4 周，最关键的竖切）
- 目标：打字 → 云端真实大模型 → 信封 → 手机验签/复核/确认 → 执行 → 回执，全链路跑通；手机成为真正的控制中心，支持全部 9 种意图。
- 交付物：云：部署到一台 VM（Compose：Caddy 加 hub 加 Postgres）；/enroll（扫码配对）、WS 网关、device inbox、大模型网关（结构化输出、小/中两档、超时降级）、审计表、kill switch。手机：Keystore 身份密钥加审批密钥；WS 加 FCM 唤醒后拉取；6 个执行器（open_app / set_alarm / create_event / set_timer / web_search / save_note），走系统 Intent；L2 弹层、L3 全屏加生物识别；Room 收件箱/发件箱、动作历史页；tools/device-sim；本地模型初步接入（意图分类）。
- 验收：20 条脚本化场景在真机通过；同一信封重复投递 3 次只执行 1 次；飞行模式下本地快路径（计时/闹钟/备忘）可用；L3 不能用 L2 证明绕过；大模型输出非法时重试一次，仍失败就转为澄清，绝不执行；L1 文本到执行 p95 小于 3 秒（初始目标）。

阶段 2 · 电脑能力（3-4 周，按用户优先级提前）
- 目标：电脑成为高算力执行节点，支持本地模型和文件操作，能独立运行。
- 交付物：托盘加无头服务；由手机扫码背书配对（电脑公钥经手机签发证书）；执行器：open_app（含 https 链接）、web_search、set_timer（Toast）、save_note，并能接收接力来的信封；L2 本机确认弹窗；本地模型层完整实现；相册整理、文件夹分类功能；桌面自动化初步实现；只读文件搜索按第 7 节处理；打包加自启加自动更新。
- 验收：“手机 → 电脑接力”端到端跑通；电脑离线时信封排队，到 TTL 后手机收到“未送达”；电脑端对篡改/过期/重放/已吊销全部拒绝；电脑要能独立运行，不依赖手机和云端。

阶段 3 · 云端“脑”（3 周）
- 目标：长期记忆、定时任务、任务分解与路由、评测体系；云端只做本地做不了的事。
- 交付物：记忆库（事实表加向量检索加手机端可审阅/删除）；持久化调度器（once/cron、misfire 策略）；planner（DAG）加 router（能力/在线/策略）加 reducer（回执推进）；大模型脱敏层与多模型降级；eval/：话术集不少于 300（按实际会说的语言分层）加注入集不少于 100 加夜跑。
- 验收：先测基线，再逼近意图准确率不少于 95%、槽位不少于 90%；注入集里 L2 以上自动执行等于 0；杀掉 hub 重启后，定时任务不丢不重；主模型故障时降级到备用模型或规则快路径。

阶段 4 · 手表轻量（2 周，按用户修订降级）
- 目标：手表只做轻量接收和确认，不做复杂 GUI，不做手机一样的 GUI 应用。
- 交付物：轻量端应用，含快捷提问、最近动作、L2 确认界面、本地计时器；它经手机中转（v1 不直连云），手表密钥只有 L2 审批权。
- 验收：手表 L2 确认端到端小于 2 秒（初始目标）；蓝牙断开时 L2 请求回落到手机通知；手表无法批准 L3（有测试）。

阶段 5 · 语音与耳机（4 周，本阶段只做手机端语音；耳机主线暂缓）
- 目标：手机端语音闭环；耳机只做语音出入口，未来只评估 AirPods，不进本阶段主线。
- 交付物：手机端 KWS 加 VAD 加本地 ASR 加 TTS；蓝牙耳机音频路由与“按键说话”；voice_command 信封（origin=voice_headset，信任降级）；语音会话放进前台服务；eval/audio/ 语音测试集加指标看板。
- 评估项：注册为系统默认“数字助理”（VoiceInteractionService），换取侧键/手势唤起入口；它能否带来后台麦克风/音频豁免，阶段初期用真机验证后再决定。
- 验收（初始目标，按实测调整）：自录集上识别错误率有基线并逐版下降；24 小时背景音误唤醒有数且低于目标；说完话到首个 TTS 音频 p50 小于 1.8 秒；唤醒监听的耗电 %/小时写进 README。

阶段 6 · 硬化与内测（3 周）
- 交付物：威胁模型复盘（对照第 9 节）；校验器模糊测试、混沌测试；密钥轮换与设备吊销演练、备份恢复演练；可观测性大盘、两周自用日志。
- 验收：无 P0/P1；演练全通过；自用期误执行等于 0；每日确认弹窗次数在用户设定的上限内（避免确认疲劳）。

第一周开工清单：
- Day 1：建单仓，把手机/电脑/云三处旧代码按 git filter-repo 导入，CI 跑通 hello。
- Day 2：写 envelope.v1.json 和 9 个 intent schema（params 先写最小集，additionalProperties:false）。
- Day 3：写 intent-policy.v1.yaml（见第 5 节），手写 30 条 valid 加 30 条 invalid 向量。
- Day 4：手机校验器迁到 core/kotlin/validator 并跑向量；每条失败逐一判断是校验器的 bug 还是向量写错，结论记进 ADR。
- Day 5：Python 侧实现同样的校验并过向量，CI 两侧变绿，写 ADR 0001-0003。

## 9. 安全：威胁与对策

- 提示注入/混淆代理（网页、备忘、日历标题、文件名里夹带指令）：来源标记加污点传播；大模型只出提案，级别由策略引擎定，设备再复核。
- 云被攻破/令牌被盗：见第 2 节最坏情况保证；hub 公钥固定、kid 可轮换；每设备速率与日预算；kill switch（云侧一键停投加设备侧本地开关）。
- 重放/篡改：JCS 加 ES256；id 去重；TTL；审批证明绑定信封哈希加 nonce。
- 设备丢失/被盗：手机是信任根，吊销由手机签名发出；L3 需生物识别；设备密钥不可导出。
- 语音伪造/环境音：voice_* 来源信任降级；L2 以上口头确认无效；高风险动作无论来源都落到手机。
- 本机攻击面：组件 exported=false、Intent 显式；设备不开入站端口；WS 网关限流。
- 数据与隐私：发给大模型前脱敏（号码/卡号/密钥样式）；日志不落敏感明文；审计日志哈希链、只追加；备份加密；保留期与一键导出/删除。
- 供应链：依赖锁定加 SCA；gitleaks；模型文件校验 hash；安装包签名。

密钥与配对：
- 手机上两把密钥：身份密钥（每条消息签名，不要求生物识别）；审批密钥（只用于 L3 审批签名，每次使用都要生物识别）。
- 手机是信任根：电脑和手表由手机扫码背书，手机签发设备证书 {pubkey, role, max_level, expiry}，类似 Signal 的关联设备；hub 公钥在配对时随二维码下发并固定。
- 审批证明是对 {envelope_hash, decision, level, ts, nonce} 的签名；执行端自己验：审批方公钥是否受信、级别是否在其权限内（手表/电脑只有 L2）、哈希是否匹配、是否过期或重放；hub 因此无法伪造或挪用审批。
- 大模型 API Key 只在云端（KMS 或 sops 加密）；设备只有设备密钥和短期会话令牌；设备密钥不上云、不备份，丢了就重新配对。

## 10. 风险与测试

主要风险：
- 后台限制与厂商杀进程（前台服务启动限制、Doze、省电白名单、Android 17 后台音频收紧）：缓解为唤醒后拉取；重启后唤醒需用户动作恢复；后台出声走闹钟/通知通道；引导电池白名单；心跳加离线告警。早期信号：真机过夜测试（Pixel 加一台激进省电机型）：次日早晨收到率、投递延迟 p95。
- 提示注入/混淆代理：缓解为 origin 加 taint；策略引擎定级；设备复核；L2/L3 人在回路；速率与预算；云内部工具结果一律当数据。早期信号：注入集中“L2 以上自动执行次数”必须为 0。
- Kotlin 与 Python 校验漂移、协议升级错位：缓解为向量 CI 门禁；未知字段拒绝；proto_minor 协商；protocol/ 设 CODEOWNERS。早期信号：向量失败即阻断；升级演练（N-1 设备连 N 版云）。
- 语音链路：延迟、耗电、误唤醒、蓝牙窄带、端上模型内存：缓解为先“按键说话”后“免唤醒”；KWS 阈值保守；分段打点；模型内存预算。早期信号：自录集的识别错误率/误唤醒/延迟/耗电 %/小时。
- 大模型延迟、成本、不稳定、供应商故障：缓解为三档模型加降级；本地规则快路径；日预算加熔断；提示词与模型版本钉死加回归评测。早期信号：夜跑评测曲线、p95 延迟、日成本。
- 范围蔓延（多端多技术栈）：缓解为竖切加每阶段可日用；手表/耳机只做薄层；每阶段硬时间盒，超时砍功能不砍测试；新增意图必须走 ADR 加 schema 加策略加向量加评测。早期信号：阶段是否按期验收。
- 密钥与隐私失误：缓解为大模型 Key 仅在云端；设备密钥不可导出；gitleaks；发送大模型前脱敏；user_id 从第一天就带（单用户部署、多租户就绪）。早期信号：CI 扫描；抽查发往大模型的请求体；轮换演练记录。
- 单 VM 故障与数据丢失：缓解为每日加密 pg_dump 到对象存储加恢复演练；Compose 一键重建；设备本地快路径。早期信号：恢复演练耗时（目标小于 1 小时）。

测试方法：
- 契约测试（向量）：Kotlin、Python 两侧各跑同一份向量，任何一侧不过就阻断合并。
- 属性测试/模糊：JVM 用 Jazzer 或 kotest-property，Python 用 Hypothesis；不变量：接受则 schema 合法且意图在白名单且无未知字段；加 taint 或换成语音来源，级别只升不降；任何一位变异则验签失败；handoff 提案深度不超过 1；同一 id 投递 N 次，副作用恰好 1 次。
- 大模型评测与注入红队：话术集看每个意图的准确率、槽位 F1、澄清率、“应拒绝却执行”的误触发率；注入集覆盖网页、备忘、日历标题、文件名里的恶意指令；提示词或模型变更必须跑评测，回归超阈值就阻断。
- 集成/E2E 加混沌：docker compose 加 device-sim 加 toxiproxy，覆盖断网、延迟、丢包、重复、乱序；中途杀 hub；手机飞行模式；FCM 延迟；时钟偏移正负 5 分钟。Android 用 Gradle Managed Devices 加 UI Automator，另加真机冒烟；电脑端用 Windows runner（系统未定前为占位）。
- 语音：自录测试集（安静/嘈杂/蓝牙 HFP/LE Audio），指标是识别错误率、24 小时背景音误唤醒、分段延迟（说完 → ASR 终稿 → 信封发出 → 首个 TTS 音频）、耗电 %/小时；Android 17 机型上用 am memory-limiter 压测模型内存。
- 安全用例：篡改、重放、过期、错设备、已吊销、时钟偏移，以及确认绕过（L3 用 L2 证明、审批挪用到另一信封、手表批 L3、审批后改参数）；另加 MobSF/Android Lint 安全规则、依赖扫描，以及一次对照第 9 节的 STRIDE 自查。
- 真机矩阵：至少 Pixel 加一台激进省电的厂商机型，覆盖 Doze、开机后恢复、前后台切换。
- 内测指标：误执行率、每日确认弹窗数、任务成功率、p95 延迟、崩溃率；线上审计日志脱敏后可回放到新版本做回归。

发布门禁：向量 100%；注入集 L2 以上自动执行等于 0；重复投递幂等 100%；篡改/重放/过期/吊销/越权审批全部被拒；评测回归不超阈值；24 小时误唤醒不超目标。

## 11. Agent 执行守则（接手时必读）

- 先读 protocol/ 和本文件第 2、5 节，再写任何执行代码；改协议必须同时改向量，改向量失败即停，不要绕过 CI。
- 未编译、未测试、未在真机验证的代码，一律报“未验证”，不要说成已完成。
- 涉及花钱、对外发送、改仓库、删数据、登录验证时，先停下来等用户确认；不要擅自升级、付费、公开密钥。
- 手表系统、电脑系统、云托管、大模型服务商未定前，不要在代码里写死这些选择；用占位加 TODO 标记，落地时由用户定。
- 每完成一个阶段，先跑该阶段的验收清单，验收不过不进入下一阶段；验收结果写进阶段报告，不要只报“已完成”。
- 本文件与 Claude 修订版如有出入，以 Claude 修订版为准并更新本文；与 ChatGPT 版本如有出入，以本文（Claude 侧）为准。
