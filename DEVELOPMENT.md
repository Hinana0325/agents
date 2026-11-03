# 多设备多 Agent 个人助理：开发文档（交给 Agent 执行用）

版本：v2，2026-11-03（Claude 修订版已落地）
依据：以 Claude 给出的修订方案为准；ChatGPT 的版本不作为依据，两者不一致时以本文为准。修订来源：Claude 对话 https://claude.ai/chat/ce1750c6-9a95-4b32-9965-0d192cf0b06f ，2026-11-03 04:09 额度恢复后继续生成。
用户修订（2026-11-02，已融入本文；2026-11-03 Claude 修订版进一步确认并细化）：
优先级为手机第一、电脑第二、云端第三；耳机暂缓（唯一例外是苹果 AirPods，未来单独评估，不进主线）；手表只做轻量接收与确认，不做手机一样的 GUI 应用；电脑、云端、手机在硬件允许时都可跑本地小模型，做相册整理、文件夹分类等本地任务。
未定事项（Agent 不得默认已定）：手表系统与型号、电脑系统、云服务器托管方式、大模型服务商与密钥来源；另见第 8 节末的待用户拍板项（是否新增 local_task、hub 语言、PC 系统、手表选型闸门）。

## 0. v2 相对 v1 的改动摘要（按 Claude 修订版概括）

- 阶段顺序：由“手机 + 云并行，再电脑、手表、语音”改为手机 → 电脑 → 云端；手表、耳机移出主线；每阶段结束都是可日用的版本。粗估 1 人全职约 23–30 周（v1 的 19–22 周不再适用），兼职乘 2–3，留 30% 缓冲。
- 信任模型：由“设备只认 hub 签名”改为手机是信任根；电脑、hub 由手机扫码背书并持手机签发的证书；信封带签发者与证书链，手机、电脑、hub 都能签发，接收方验链并用本机策略复核。
- 云端：由阶段 1 部署（Python）改为阶段 5 才做，作为被手机背书的一个节点加入；语言默认 Kotlin（有闸门 G-HUB，见第 8 节），以便三端共用校验器、策略与状态机。
- LLM 密钥：由“只放云端”改为云上线前放手机/电脑本机加密存储，上线后（阶段 5b）迁到云并轮换作废旧 key。
- 手表：由“轻量端应用、有 L2 密钥”改为不是节点、不持密钥、不写手表 App；唯一内容是手机通知镜像的薄表面，确认由手机签名，审计记 via: watch_mirror，只能批 L2；系统未定，靠 G-WATCH 实测闸门决定。
- 耳机：由“阶段 5 含唤醒词与耳机路由”改为移出主线；语音只留手机按键说话 + 本地识别；常驻唤醒词与电脑端语音删除；AirPods 放后置评估（见第 6 节末）。
- 本地小模型：由“只有语音识别 + 第 7 节分档”升级为独立一层 ai/（任务清单、本地/云路由、设备分档、模型清单、作业引擎、评测），模型只出提案、代码做裁决（见第 7 节）。
- 意图白名单：9 种不变，拟新增第 10 种 local_task（协议 v1.1，待用户拍板，取代 v1 的 local_search 选项），用于受控的本地任务入口。
- FCM、同步、长期记忆、云端调度：全部推迟到阶段 5；阶段 1–4 不做。
- 电脑系统：不再默认 Windows；控制面用 Kotlin/JVM + platform-<os> 适配层，由闸门 G-PC-OS 决定，只做选定的那一个系统。
- Claude 在修订版中自我纠正的旧假设：手表 ≠ Wear OS、电脑 ≠ Windows、手表不是带密钥的节点、LLM Key 不是只在云端、FCM 不是阶段 1 的内容；新增约束：Android 17 起 targetSdk ≥ 37 的应用访问局域网（含 mDNS）受强制保护，影响阶段 3 的手机 ↔ 电脑直连。

## 1. 产品定义

系统由手机、电脑、云服务器组成主线，另有手表、耳机两个薄表面。手机是信任根；云服务器是阶段 5 才加入的增强节点，不是前期的编排中枢；手表、耳机不是 Agent 节点，不持密钥、不跑模型。

优先级与职责：
- P0 手机：主控制中心、信任根。负责交互、确认、安全、个人数据、本地业务。所有敏感操作必须经过手机确认。手机离线时基本功能仍然成立；阶段 1 手机单机就是可用助手。
- P1 电脑：高算力执行节点。负责本地模型、文件操作、复杂任务、桌面自动化。电脑离线时，手机不能因此失去控制能力；阶段 3 起手机可经局域网直连把任务接力给电脑。
- P2 云端：阶段 5 才加入。5a 做中继与信任（跨网络中继，局域网直连保留为快路径），5b 才成为大脑（计划/记忆/定时/同步），5c 做云端小模型。云端不可用时，已具备本地执行条件的任务仍可运行。
- 薄表面 手表：只做通知镜像的接收、提醒与轻量确认，不写手表 App，不持密钥。确认由手机签名，只能批 L2；L3 的通知只提供“到手机处理”。系统与型号未定，靠 G-WATCH 实测闸门（见第 6 节末）。
- 暂缓 耳机：不进主线，不写耳机端代码，协议里只保留 audio_endpoint 抽象。语音只留手机按键说话 + 本地识别。未来只单独评估苹果 AirPods，见第 6 节末的三个评估闸门。

## 2. 不可违背的约束（硬规则）

- 应用级，不 root。清单里唯一权限原则上是联网；无 shell、无无障碍、无短信、无电话、无后台随意拉起。
- Agent 之间只交换结构化的意图信封，不传自由执行指令。设备之间可经手机背书的证书链互认：信封带签发者（issuer）与证书链，手机、电脑、hub 都能签发，接收方验链并用本机策略复核；不再是“只接受云端签名的信封”。
- 意图白名单，共 9 种：open_app（打开应用）、set_alarm（设闹钟）、create_event（建日历）、set_timer（计时）、web_search（网页搜索）、save_note（存备忘）、chat（纯聊天）、task_handoff（任务接力）、voice_command（语音指令）。拟新增第 10 种 local_task（协议 v1.1，待用户拍板，见第 7 节末）；在用户拍板前不得默认已新增。
- voice_command 是“输入信封”，携带转写文本，不可直接执行，必须先解析为其他具体意图，产出的意图继承 voice 来源标记。
- task_handoff 是“向云端提交的跨设备转交请求”，内含一个意图提案，深度不超过 1，不得是 task_handoff 或 voice_command 本身；云端重新校验后签发新信封给目标设备，parent 指向原请求，级别取 max(2, 提案级别)，污点与语音标记随之继承。
- 大模型只提“意图提案”，不碰元数据，不定确认级。确认级由策略引擎算，设备永远独立验签并复核。
- 确认分级：L1 自动执行；L2 需本人确认（任一受信界面）；L3 仅手机界面加生物识别。用户设置可调高级别；调低仅限可放宽项，且永不低于硬下限；L2 以上的口头确认无效，必须在界面上点。
- 污点传播：本轮上下文含不可信内容（网页、备忘、日历标题、文件名里的指令等），产出的信封 tainted=true 且级别加 1，上限 L3；存下来的备忘读回来仍是不可信输入。
- 本地模型和云端模型都只能提出执行建议，不能绕过统一安全策略。每个设备本地也必须有独立的安全策略。
- 密钥只存本机加密存储（Keystore / DPAPI / TPM），不回显原文，不写死在代码里。大模型 API Key 在云上线前放手机/电脑本机加密存储（每设备独立一把、供应商侧设预算上限），阶段 5b 迁到云后轮换作废旧 key，设备只留设备令牌；设备密钥不上云、不备份，丢了就重新配对。
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

v2 结构增补（相对 v1 的目标结构）：
- protocol/schema/ 增加 cert.v1.json（手机为根签发的设备证书）；envelope 增加 issuer + chain，origin 增加 derived_local；capability 增加 surfaces[]（声明手表镜像等薄表面）；sync/ 只放占位，阶段 5b 才实现；policy 增加 model-routing.v1.yaml（本地/云路由策略）；spec 增加 trust-chain.md、transport-lan.md；vectors 增加 routing/（路由决策）与 fileops/（路径逃逸、符号链接、重名、超限、删除被拒）。
- core/kotlin/ 增加 llm-provider/（接口 + 直连实现，密钥取自本机加密存储）、orchestrator-lite/（understand + route，planner/reducer 留到阶段 5）、model-router/（读路由策略，输出本地/云/延后/问用户）。
- 新增 ai/ 本地小模型层：contracts/（每种任务的输入输出 schema，含 fileops-plan、album-plan）、models/（manifest.v1.yaml、tiers.v1.yaml）、runtimes/（android/、desktop-worker/、cloud/）、jobs/（断点续跑、节流、撤销日志）、probe/（设备算力探测与基准）。
- apps/watch/ 只留 README + 探测说明，不含代码；services/hub/ 冻结至阶段 5；tools/ 增加 pc-probe（跨系统探测，输出 tier.json）；docs/ 增加 decisions/{G-PC-OS, G-WATCH, G-HUB}.md 与 backlog/（含 airpods-eval.md 实验步骤，不排期）；eval/ 增加 local-tasks/，audio/ 缩减。
- apps/phone/ 增加 surfaces/（PhoneSurface、NotificationSurface，手表镜像走后者）、llm/（DirectApiProvider、LocalProvider）、ai/（模型管理器、LiteRT-LM/ONNX 适配、WorkManager 作业）、assistant-lab（通知实验室/算力探测/向量自检）、albums（虚拟相册）；transport/ 分 lan/（阶段 3）与 hub/（阶段 5，占位）。
- apps/desktop/ 的 platform-win/ 改为 platform-<os>/（闸门 G-PC-OS 之后只做选定的那一个）；增加 ai-worker/（数据面，默认 Python，只通过回环接口接收任务提案）。

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

信封示例字段：v、id（UUIDv7，同时是幂等键）、trace、ts、ttl_s、from（role、kid）、to（device）、intent、params、origin（kind、via、tainted）、level（策略引擎算出，大模型不参与）、parent（接力重新签发时指向原请求）、sig（ES256 over JCS，除 sig 外全部字段）。v2 增补：issuer（签发者）与 chain（证书链）；origin 增加 derived_local（本地模型派生）。

v2 阶段说明：阶段 1–4 没有 hub，understand + route 由手机（orchestrator-lite）或电脑本地完成，LLM 直连本机加密存储的 key；阶段 5 起 hub 加入后才由 hub 做大脑与中继，FCM 唤醒后拉取、同步、长期记忆、持久化调度也都在阶段 5 才出现。本节其余生命周期描述按“有 hub 时”理解，阶段 1–4 把其中的“云端”读作“本机 orchestrator-lite”。

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

v2 说明：下表里 targets 含 watch 的，指的是经手机通知镜像的薄表面确认/提醒，不是手表作为执行节点；手表本身不执行、不持密钥。task_handoff 在阶段 1–4 由手机直接签发给电脑（阶段 3 起经局域网），阶段 5 起也可经 hub 中继。

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

- 云 hub（阶段 5 才做，v2 默认语言改为 Kotlin，见第 8 节闸门 G-HUB）：三端共用一份校验器、策略与状态机；若改用 Python 生态或保留已有 Python 骨架，代价是回到“双实现 + 向量对齐”。部署形态沿用 v1 的单机思路（Caddy、Postgres 等），落地时按 G-HUB 决定，阶段 5 之前不部署、不写 hub 业务代码。
- 大模型网关：自写薄层，与供应商无关，小/中/大三档模型，结构化输出（把意图 schema 当工具 schema 传入），超时/重试/降级、日预算；模型名和提示词版本放配置并带 hash；低温度；输出必须过校验，失败最多重试一次，再转为向用户澄清。
- 手机：Kotlin + Compose（现有）、kotlinx.serialization、Room（SQLCipher）、OkHttp WebSocket、WorkManager、FCM、Android Keystore（加 Tink）、BiometricPrompt。无 GMS 机型：前台服务长连，或 UnifiedPush/ntfy。
- 手表：不是节点，不写 App，不持密钥。手机通知默认桥接到配对手表，通知带 2–3 个动作按钮（批准 / 拒绝 / 到手机处理），动作用显式、不可变的 PendingIntent，携带 approval_id + nonce，单次有效、到 TTL 过期；L3 的通知只提供“到手机处理”；手表回复走 RemoteInput，记 origin=user_text via=watch_reply。系统未定，选型只看实测：至少 2 个可点动作按钮、点击到手机延迟 p95 < 3 秒、听写回复可用、锁屏/口袋里可靠。文档化支持最好的是 Wear OS 系，其他品牌因厂商而异，必须实测；若手机继续是 Android，Apple Watch 因必须配 iPhone 直接排除。
- 电脑：Kotlin/JVM + Compose Desktop（托盘加确认弹窗），复用 core；系统未定，platform-<os>/ 只做 G-PC-OS 选定的那一个；SQLite；密钥放本机安全存储；打包与自启按选定系统落地。需要深度集成时再议语言，校验器靠向量对齐。
- 语音（v2 缩减）：只留手机按键说话 + 本地识别；常驻唤醒词与电脑端语音删除。ASR 三选一用自录语音集评测：sherpa-onnx、ML Kit 语音识别（Basic 模式）、小模型音频输入；TTS 先用系统 TTS。AICore/ML Kit GenAI 只作前台交互式通道（仅最前台推理、有配额），绝不用于批处理作业。
- 耳机：无代码，作为 audio_endpoint 登记；按键经 MediaSession 映射为“按键说话”。AirPods 后置评估，满足任一闸门再启动：新增 iOS 设备（那是新增 iOS 端，当前范围外）；Apple 开放第三方可编程能力（目前未见）；阶段 2 之后用同一批自录语音对比 AirPods（接 Android）与手机麦克风的识别错误率与延迟。注意：AirPods 接 Android 时基本等同高端普通蓝牙耳机，常驻语音助手与空间音频等功能在 Android 上用不了；目前未见允许第三方在耳机芯片上运行自己代码或模型的接口，其价值主要在收音/降噪与 iOS 侧头动数据。
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

本地小模型层（v2，ai/，控制面与数据面分离）：模型只出提案（受 schema 约束的 JSON），Kotlin 侧只通过 InferenceEngine 接口访问，提案必须先过校验器和策略引擎才可能变成动作；“要不要执行高风险操作”任何模型都不决定，由确定性策略引擎决定。

路由规则（model-routing.v1.yaml，纯函数，可用向量测试，按优先级判断）：P2 数据（照片、文件内容、私密备忘）只能本地，本地跑不了就“延后”，或用户明确选“发送脱敏摘要到云”，绝不自动上传；P0/P1 按任务表默认位置；本地先行，置信度低于阈值且数据允许才升级到云（带脱敏）；批处理只在充电 + 空闲 + 温度正常时跑，电量低或过热暂停；云调用走日预算；每次路由决策写审计（任务、位置、模型版本、理由）。用户可选隐私优先（云仅限显式批准）、均衡（默认）、质量优先（非 P2 任务云优先）三种模式。

适合本地 / 必须走云（v2 细化）：本地做意图快路径、按键说话转写、嵌入/语义搜索/去重/聚类、相册整理、文件夹分类与改名建议、短中文档摘要、发云前脱敏、注入旁路检测（只作 taint 辅助信号，不替代确定性规则）；云做多步规划与任务分解、长上下文推理、联网检索、高质量写作/翻译/代码生成；长期记忆默认云（脱敏后），电脑档位 ≥ M3 且开隐私优先时可本地。另可采用“云出规则、端上执行”：云只看脱敏后的少量文件名样本设计分类体系，本地再逐个归类。

设备分档（由 probe 实测吞吐决定，不只看规格；经验公式：4bit 权重约参数量 × 0.6–0.7 GB，再加 KV 缓存与运行时 1–2 GB，总量不超过可用内存/显存的 70–75%）：M0（<6GB 手机、老电脑，无本地 LLM，规则 + 微型模型）；M1（中端手机 6–8GB、无独显 16GB 电脑，≤2B 级）；M2（旗舰手机 12–16GB、16–32GB 内存或 6–8GB 显存电脑，4B 级多模态）；M3（12–16GB 显存、24–32GB 统一内存，9–12B 级）；M4（24GB+ 显存、48GB+ 统一内存，27–31B 级）；云 C0（CPU VPS，ONNX 嵌入、小分类器、脱敏/guard）、C1（可选 GPU 机，仅当 API 成本高于 GPU 成本时考虑）、云 API（供应商小/中/大三档）。云端小模型不是“本地”，P2 数据不进云端小模型。

模型选型方法（先方法，模型每月都在变）：按任务在 manifest 里筛许可允许的候选，内存 × 1.3 ≤ 0.7 × 可用内存且实测首字延迟/吞吐达标者可行，在自有评测集（eval/local-tasks/）上得分最高者胜出，钉死版本 + sha256 进 tiers.v1.yaml；换设备、新模型发布、每季度重跑；公开榜单只用来缩小候选范围。当前候选（截至 2026 年 10 月检索，最终以自有评测为准，概括）：文本 LLM 按档位从小到大覆盖 Gemma 4 E2B/E4B/12B/26B-A4B/31B 与 Qwen3.5-2B/4B/9B、Qwen3.8-27B；多模态同档位模型或 CLIP 类零样本标签；图像嵌入 SigLIP 2（B/L/So400m）；文本嵌入 EmbeddingGemma（308M，量化后不到 200MB）。运行时：手机主选 LiteRT-LM（GPU、受约束解码正好保证意图 JSON 格式），llama.cpp（JNI）作备选；电脑小档可复用 Kotlin 封装，中大档用 llama.cpp server（GGUF，OpenAI 兼容）；Ollama 与 LM Studio 的 OpenAI 端点并不完全兼容，不作为默认。

作业与安全：所有本地模型输出都是提案，用受约束解码生成 JSON；fileops-plan 只允许 mkdir/move/copy/rename。文件操作限定在用户配置的“安全根”内：路径规范化，防 .. 与符号链接逃逸；v1 禁止删除和覆盖，重名加后缀，不做跨卷移动；单计划操作数有上限，超限拆分；写 journal，可一键撤销；审批绑定 plan_hash。Android 相册默认只建虚拟相册/标签索引，不动文件；真要移动时走系统授权请求（createWriteRequest），批量删除走 createTrashRequest 类路径，v1 不做。文件内容当作不可信文本：本地 LLM 读文件时无工具权限，只返回受限标签或摘要，且标记 tainted。小模型置信度不可靠，门禁用多次采样自洽 + 与规则/最近邻一致，不用裸 logprob。作业用 WorkManager（手机）或空闲调度（电脑），每 N 项落一次检查点，过热或低电量暂停。

拟新增意图 local_task（协议 v1.1，待用户拍板，取代 v1 的 local_search 选项）：
- 任务：file_search、file_classify、album_organize、summarize_local；目标设备 phone、pc。
- 两段式：mode=propose 只读 + 算力、无副作用、结果 tainted，基础级 L1；mode=apply 升到 L2，审批绑定 plan_hash；操作数超限或超出安全根则拒绝。
- 备选（若用户不拍板新增）：本地任务只在设备 UI 触发，不接入信封，手机就不能远程指挥电脑整理文件。
- 电脑现有的只读文件搜索在拍板前不接入信封；文件内容是提示注入和外带的经典入口，必须先有污点机制。

## 8. 分阶段开发方案（v2，按 Claude 修订版重排）

粗估按 1 人全职，总计约 23–30 周（v2 修订估算；兼职乘 2–3，并留 30% 缓冲）。每阶段结束都是一个能日常使用的版本；每阶段硬时间盒，超时砍功能不砍测试；新增意图必须走 ADR 加 schema 加策略加向量加评测。

阶段 0 · 契约、决策与探测（1–2 周）
- 目标：冻结新增契约，把两个“未定”变成数据驱动的闸门。
- 交付物：monorepo + 导入手机、电脑存根、云骨架（云骨架放 services/hub/，标注“冻结至阶段 5”）；protocol/ 修订（envelope 含 issuer/chain、cert、capability 含 surfaces、local_task 草案、intent-policy 含 local_task、model-routing.v1.yaml、spec/trust-chain.md、spec/transport-lan.md）；vectors/ 不少于 200 条（沿用五类，新增 routing/ 与 fileops/）；core/kotlin/validator 迁入并通过向量；tools/pc-probe（Python 单文件，跨系统，测内存/显存/加速器，跑 llama.cpp 基准与嵌入吞吐，输出 tier.json）；决策文档模板 G-PC-OS / G-WATCH / G-HUB；ADR 0001–0008（单仓、信任根 = 手机、控制/数据面分离、LLM 密钥托管、local_task、禁止删除、手表 = 表面、hub 语言）。
- 验收：Kotlin 侧向量 100%；pc-probe 在每台候选电脑上跑出 tier.json；G-PC-OS 有足够数据可决定。

阶段 1 · 手机独立版（4–5 周，最重要）
- 目标：手机单机就是可用助手：输入 → LLM → 信封 → 校验 → 确认 → 执行 → 审计。
- 交付物：core/kotlin（envelope、validator、policy 含 taint、crypto ES256 + JCS、approval、llm-provider 接口 + DirectApiProvider 含结构化输出/重试/用量计数、orchestrator-lite understand + route）；手机：身份密钥 + 审批密钥（Keystore，后者每次需生物识别）、加密存储、LLM Key 录入与用量上限（供应商侧给每设备独立 key 设预算上限）；6 个执行器（open_app / set_alarm / create_event / set_timer / web_search / save_note）；确认 UI（L2 通知按钮 + 底部弹层，L3 全屏 + 生物识别）；动作历史页（可撤销的能撤销）；离线规则快路径（计时/闹钟/打开应用/备忘）；Assistant Lab（通知实验室、算力探测、向量自检）；eval/injection 不少于 50 条。
- 验收：20 条脚本化场景在真机通过；飞行模式下快路径可用；L3 无法用 L2 证明绕过；注入集里 L2+ 自动执行 = 0；API Key 不出现在日志和备份里；用量上限生效；L1 文本到执行 p95 < 3 秒（初始目标）。
- 砍掉/后移：FCM、云、同步、手表 App、唤醒词、耳机。

阶段 2 · 手机本地小模型 lite（3–4 周）
- 目标：把本地能力层落到最受限的设备上，先验证契约、预算和节流。
- 交付物：ai/contracts（intent_fast、asr、embed_text、embed_image、image_tag、album-plan）、ai/models 清单与档位、ai/probe（App 内）、model-router；手机运行时：模型管理器（Wi-Fi 下载、sha256 校验、断点续传、存储配额）、LiteRT-LM 适配（llama.cpp 备选，用基准决定）、ONNX Runtime Mobile（嵌入）、作业引擎（WorkManager：充电 + 空闲 + 电量/温度闸 + 断点续跑）；意图快路径（小模型 + 受约束解码，低置信度升云）；按键说话（录音 → 本地识别 → voice_command，origin=user_voice via=phone_mic）；相册 v1（虚拟相册：扫 MediaStore → 去重/连拍/截图识别（非 ML）→ 嵌入 + 零样本标签 → 事件聚类 → 语义搜索 → 只出提案）。
- 验收：probe 给出档位和实测吞吐；快路径在 200 条话术上达到云基线的目标比例，p50 < 1 秒（M2，初始目标）；5k 张照片作业可被杀后续跑，开飞行模式也跑完（证明零外传）；所有模型文件校验 hash；ASR 三选一有结论。
- 后移规则：若超时，砍相册，保按键说话 + 快路径。

阶段 3 · 电脑控制面 + 手机 ↔ 电脑直连（3–4 周）[闸门 G-PC-OS]
- 目标：手机把任务接力给电脑，确认跨设备成立；电脑单机也能用。
- 交付物：apps/desktop（Kotlin/JVM，复用 core）：无头服务 + 托盘；只实现选定系统的 platform-<os>，其余留接口。配对：电脑显示二维码（公钥 + 一次性 nonce + 地址候选），手机扫码后签发证书 {pubkey, role: pc, may_approve: [2], not_after}，电脑保存手机根公钥。局域网传输：电脑起 WSS 服务，mDNS 广播，配对时交换并固定 SPKI；应用层签名不变。手机端注意 Android 17 约束：targetSdk 低于 37 的旧应用会隐式获得局域网权限，targetSdk 37 则必须二选一（系统选择器免权限，或运行时权限，缺权限时 TCP 连接表现为超时）；先在真机上测哪条适合日常重连，再定 targetSdk。电脑执行器：open_app（含 https 链接）、web_search、set_timer（系统通知）、save_note（本地加密）、chat（电脑 UI → 直连 LLM，密钥在本机加密存储）、接收接力。接力：手机校验提案后直接签发给电脑（parent 指向原请求）。现有只读文件搜索迁入 capabilities/，暂不经网络暴露。安装包 + 自启。
- 验收：手机 → 电脑接力端到端；电脑离线时信封排队，到 TTL 后手机收到“未送达”；电脑拒绝篡改、过期、重放、已吊销；电脑不能批准 L3；手机吊销电脑后，后续信封被拒；局域网混沌测试（丢包/重复/乱序）通过。
- 未做：跨网络直连。临时方案是手动搭 mesh VPN，正式方案等阶段 5a。

阶段 4 · 电脑本地小模型 full（4–5 周）
- 目标：电脑成为本地 AI 主力：文件夹分类、全量相册、本地摘要，按档位用能力最高的模型。
- 交付物：ai-worker（默认 Python 3.12 + uv）：管理 llama.cpp server、ONNX 流水线（图像嵌入、OCR）、文档文本抽取、作业运行器（可续跑、可节流）、向量索引；回环接口带令牌，只收 JSON 任务提案。档位落地：pc-probe → tiers.v1.yaml 选出 M1–M4 的实际模型，按第 7 节方法在 eval/local-tasks/ 上跑分并钉死。local_task 意图（v1.1）：file_search / file_classify / album_organize / summarize_local，propose（L1）与 apply（L2，绑 plan_hash）两段式。fileops 执行器：安全根、禁删除/覆盖、journal、撤销、进度 UI。分类质量闭环：用户纠错 → 示例库；分类体系可编辑（YAML 或列表）。手机触发：手机发 local_task(propose)，电脑回计划摘要，手机确认（L2），电脑执行。评测与安全套件：错移率、路径逃逸模糊测试、撤销后目录 hash 一致、文件内容里的注入测试。
- 验收（初始目标，先出基线再定）：在自己的 2 万文件测试树（用副本）上，默认阈值下错移率 < 1%，其余进“待确认”；apply + undo 后目录 hash 与原来一致；1 万张照片作业能断点续跑，各档吞吐有记录；本地任务期间对外网络流量为零（断网也能完成）；换成更差的模型时，评测门禁会拦住。

阶段 5 · 云端 hub（5–7 周）[闸门 G-HUB]
- 5a 中继与信任（1–2 周）：部署一台 VM；hub 由手机扫码背书；WSS 网关 + 收件箱 + presence；手机端 FCM 唤醒后拉取；审计哈希链；kill switch。此后手机 ↔ 电脑可跨网络，局域网直连保留为快路径。
- 5b 大脑（3–4 周）：复用 orchestrator-lite 加上 planner（DAG）/ reducer；LLM 网关；LLM 密钥迁到 hub，旧 key 轮换作废，设备只留设备令牌；长期记忆；持久化定时；跨设备同步（沿用 v1 的单写者设计，去掉手表）。
- 5c 云端小模型（约 1 周）：CPU 上的 ONNX 嵌入、PII 脱敏、guard 分类器；云 API 三档之间的路由。GPU 自托管不做。
- 验收：“hub 密钥被盗”演练：设备仍要求 L2/L3 审批，速率限制生效；重启不丢不重定时任务；局域网直连与 hub 中继可自动切换；密钥迁移演练通过（旧 key 失效）。

阶段 6 · 硬化与内测（3 周）
- 威胁模型复盘（增加本地模型和文件操作面）；校验器与 fileops 模糊测试；混沌测试；密钥轮换与设备吊销演练；备份恢复演练；两周自用日志。
- 验收：无 P0/P1；演练全过；自用期误执行 = 0；本地文件操作无一次不可撤销。

后置与砍掉（v2）
- 手表 App（Level B，极简伴侣）：后置；仅当 G-WATCH 实测发现通知镜像不够时启动。
- 常驻唤醒词、电脑端语音：砍，只留按键说话；平台限制放宽，或用户接受“需用户动作拉起”时再议。
- 通用蓝牙耳机端点：后置；阶段 2 之后用自录语音集实测麦克风质量。
- AirPods：后置评估；见第 6 节末的三个闸门。
- 云端 FCM、同步、记忆、调度：推迟到阶段 5。
- 人脸聚类：默认关闭，做了也只在本机；需用户明确要。
- 手机相册交给电脑跑：后置（需要局域网传图）；阶段 4 之后。
- 云端 GPU 自托管模型：后置；仅当 API 成本高于 GPU 成本时考虑。
- iOS 端：范围外；仅当用户新增 iOS 设备时再议。

前 10 个工作日（v2）
- D1：建 monorepo，导入三处旧代码，CI 跑通；在每台候选电脑上启动 pc-probe。
- D2：写 envelope.v1.json（含 issuer/chain）、cert.v1.json、9 个 intent schema。
- D3：写 intent-policy.v1.yaml（含 local_task）和 model-routing.v1.yaml；手写 30 条 valid + 30 条 invalid 向量。
- D4：手机校验器迁到 core/kotlin/validator 跑向量；每条失败判断是校验器 bug 还是向量写错，结论进 ADR。
- D5：起草 trust-chain.md、transport-lan.md、ADR 0001–0008；拿 tier.json 填 G-PC-OS。
- D6–7：手机：身份密钥 + 审批密钥、加密存储、API Key 录入与用量上限。
- D8–9：DirectApiProvider + orchestrator-lite.understand（结构化输出 → 校验 → 策略）+ 前 3 个执行器（计时、闹钟、打开应用）。
- D10：L2/L3 确认 UI + 动作历史 + Assistant Lab 通知实验室，在真机上跑通第一个端到端场景。

闸门与待用户拍板（v2，Agent 不得默认已定）
- G-PC-OS（阶段 3 开始前）：先在每台候选电脑上跑 pc-probe，再按下面选：NVIDIA 独显 ≥ 12–16GB：Windows 或 Linux（llama.cpp 的 CUDA 路线最成熟）；Apple Silicon 统一内存 ≥ 24–32GB：macOS，能装更大模型；只有核显或内存 ≤ 16GB：任意系统，只到 M1–M2，其余靠云。系统差异（安全存储、自启、通知、打开应用）全部收在 platform-<os>/，只做选定的那一个。
- G-WATCH：见第 6 节，不排期。
- G-HUB（阶段 5 前）：默认 Kotlin，理由是 phone/PC/hub 共用一份校验器、策略和状态机。如果用户更想用 Python 生态，或现有骨架已经是 Python 且值得保留，就保留 Python，代价是回到“双实现 + 向量对齐”。
- D-INTENT：是否新增第 10 种 local_task。默认新增；备选是本地任务只在设备 UI 触发，不接入信封（手机就不能远程指挥电脑整理文件）。
- D-KEY：阶段 1–4 LLM Key 放手机/电脑本机。风险是设备被攻破时 key 泄露。缓解：Keystore/OS 安全存储加密、供应商侧预算上限、每设备独立 key、阶段 5b 迁移后轮换作废。


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
- 手机是信任根：电脑和 hub 由手机扫码背书，手机签发设备证书 {pubkey, role, max_level, expiry}，类似 Signal 的关联设备；hub 公钥在配对时随二维码下发并固定。手表不是节点，不签发证书、不持密钥。
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
