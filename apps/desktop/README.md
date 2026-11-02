# PC Agent（电脑执行节点）

## 现状（2026-11-02）

已实现一个**能独立运行的无头服务**（Kotlin/JVM，复用 `core/kotlin`），不依赖手机与云端：

- **本地信封通道**：`inbox/` 投递 → 校验/验签/去重 → 执行 → `outbox/` 回执，`pending/` 存待确认。
  这是云端 hub 就绪前的传输替代品；换成 WS 拉取后，处理逻辑原样保留。
- **执行器**：`chat`、`save_note`（写本地备忘）、`web_search`（固定搜索引擎模板）、
  `open_app`（端上应用目录映射到 url/command）、`set_timer`（进程内计时 + 托盘通知）。
  日历/闹钟按策略只有手机可达，电脑明确返回失败，不假装成功。
- **确认**：L1 直接执行；L2 在 Compose 主界面弹确认框（默认按钮是“取消”），确认后用
  **本机审批密钥**签名 `approval.v1` 再执行；L3 电脑没有审批权，留在 `pending/` 并提示去手机批准。
  非交互运行（`once`）时 L2 留在 `pending/`。
- **界面**：Compose Desktop（Kotlin/JVM + Material3）主窗口显示设备信息、hub 公钥状态、
  inbox/pending/outbox 计数，并有“运行日志 / 已处理 / 设置”三个页签；设置页可编辑
  应用目录（JSON）、URL 允许清单、搜索模板与 dry_run，保存前校验并写入 config.json；
  系统托盘提供“显示主界面 / 退出”，关闭窗口最小化到托盘。
- **身份**：P-256 密钥对，`pair` 命令输出 `device_id + 公钥 JWK`，供将来的手机扫码背书使用。

已验证：25 个单元测试全绿（含跨重启去重、DPAPI 往返/迁移、历史查询、设置校验、设备证书）；本机端到端跑通
（device-sim 签发 → 电脑执行 → 回执 done → 重投同一信封返回 DUPLICATE 且不再执行；
L2 在 Compose 界面点确认后执行，含审批密钥签名；取消则 rejected；篡改/错设备被拒）；
打包出的 `DpaDesktop.exe`（自带 JRE）实机启动并完成一次 save_note 执行。

**未实现**（不要当成已完成）：

- WS/FCM 传输、手机扫码配对与设备证书、设备吊销。
- 密钥保护：Windows 已用 DPAPI；**TPM 未做**，非 Windows 仍是明文文件。
- 安装器：没有 MSI（本机缺 WiX）、没有开始菜单/卸载登记/自动更新；已有登录自启命令（Windows）。
- 本地模型、相册整理、文件夹分类、桌面自动化。
- 设置页已支持应用目录/允许清单/搜索模板/dry_run 的编辑与校验（应用目录目前是 JSON 文本编辑，
  不是表单）；保存后需重启服务生效。审计/撤销等页面后续再补。
- SQLite 目前只存“已处理 id + 回执”；收件箱/发件箱的持久化与重试队列还没做。

## 运行

```bash
# 在仓库根目录
subst V: "<仓库目录>"          # 目录名含中文时必须
cd V:
./gradlew :apps:desktop:service:installDist

# 首次运行生成配置与身份密钥（默认 ~/.dpa-desktop，可用 DPA_DESKTOP_HOME 覆盖）
DPA_DESKTOP_HOME=/path/to/home apps/desktop/service/build/install/service/bin/service.bat status

# 开发联调：用 device-sim 以 hub 身份签发信封
uv run --with cryptography python tools/device-sim/sim.py install --desktop-home /path/to/home --dry-run
uv run --with cryptography python tools/device-sim/sim.py send --desktop-home /path/to/home \
    --intent save_note --params '{"text":"买牛奶","title":"购物"}' --level 1

# 单次处理 / 常驻（Compose 主窗口 + 托盘 + L2 弹窗）
apps/desktop/service/build/install/service/bin/service.bat once
apps/desktop/service/build/install/service/bin/service.bat run
```

`run` 会打开主窗口并常驻托盘；关闭窗口只是最小化，退出用托盘菜单。`once` 不启动界面，供脚本/CI 使用。

## 打包（自带 JRE，不依赖目标机 Java）

```bash
./gradlew :apps:desktop:service:packageAppImage
# 产物：apps/desktop/service/build/jpackage/DpaDesktop/DpaDesktop.exe
```

整个 `DpaDesktop/` 目录可直接拷到别的 Windows 机器运行（jpackage app-image + 内置 runtime）。
GUI 版没有控制台，`status/pair/once` 继续用 `installDist` 的 `service.bat`。
没有 MSI：本机缺 WiX，`jpackage --type msi` 无法执行（见 ADR 0010）。

## 登录自启（仅 Windows）

```bash
service.bat install-autostart    # 写 HKCU\Software\Microsoft\Windows\CurrentVersion\Run\DpaDesktop
service.bat remove-autostart     # 删除该项
service.bat status               # 显示“登录自启：已启用/未启用”
```

自启指向数据目录里的 `autostart.cmd`，命令由当前 JVM 的 `java.home` + `java.class.path` 拼成，
`installDist` 与 jpackage 两种运行方式都能用。**正式使用时请从打包后的 `DpaDesktop.exe` 执行
`install-autostart`**：从 `installDist` 安装会指向 build 目录，重新构建后路径可能失效。

## 配置（`~/.dpa-desktop/config.json`）

```json
{
  "device_id": "pc-xxxxxxxx",
  "role": "pc",
  "hub_pubkey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." },
  "hub_kid": "hub-test-1",
  "app_catalog": {
    "com.example.site": { "url": "https://example.com" },
    "com.example.notepad": { "command": ["notepad.exe"] }
  },
  "url_allowlist": ["example.com"],
  "web_search_template": "https://www.bing.com/search?q={q}",
  "dry_run": false
}
```

`hub_pubkey` 为空时服务**拒绝接收任何信封**（不允许跳过验签）。`dry_run=true` 时
打开浏览器/启动程序只返回将要执行的命令，便于测试。

## 数据与密钥

- 数据目录默认 `~/.dpa-desktop`（`DPA_DESKTOP_HOME` 可覆盖）：`inbox/ outbox/ pending/ processed/ notes/`。
- `desktop.db`（SQLite）：已处理信封 id 与终态回执，用于跨重启去重。`awaiting_confirm` 不落库，重启后重新处理。
- 私钥：Windows 上由 DPAPI 保护为 `identity-private.dpapi`（旧明文 `.der` 会自动加密并删除）；
  非 Windows 暂时明文，`status` 会明确标注。

## 配对（二维码 / JSON）

```bash
service.bat pair > request.json           # 输出配对请求（device_id/role/max_level/公钥）
service.bat pair-qr request.png           # 把配对请求导出成二维码 PNG
service.bat trust-phone phone_pubkey.json # 记录手机公钥（信任根）
service.bat verify-cert cert.json         # 验证手机签发的设备证书并保存
service.bat revoke-device revoke.json     # 验证手机签发的吊销记录并作废证书
service.bat status                        # 查看“信任根 / 设备证书 / 已吊销证书”
```

主界面“配对”页同时显示二维码与请求 JSON。手机端在“配对”页可以“从相册扫描”二维码
（把桌面导出的 PNG 传过去），或直接粘贴 JSON；手机用 Keystore 身份密钥签名后，
把证书交回电脑 `verify-cert`。证书规则见 `protocol/spec/pairing.md`。
相机实时扫码与证书吊销/轮换仍未做。

## 安全边界

- 只执行协议白名单里的意图；未知意图、未知参数、低于策略的 level、过期、重放、
  验签失败一律拒绝，和手机端跑同一份 `protocol/vectors`。
- L2 的确认是**本机界面点击 + 本机审批密钥签名**，云端伪造不了；L3 电脑无权批准。
- `capabilities/file_search.py` 仍是只读诊断工具，**没有接入信封**（ADR 0006 默认选项 A）。
