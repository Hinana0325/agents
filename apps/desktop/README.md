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
  inbox/pending/outbox 计数与运行日志；系统托盘提供“显示主界面 / 退出”，关闭窗口最小化到托盘。
- **身份**：P-256 密钥对，`pair` 命令输出 `device_id + 公钥 JWK`，供将来的手机扫码背书使用。

已验证：8 个单元测试全绿；本机端到端跑通（device-sim 签发 → 电脑执行 → 回执 done；
L2 在 Compose 界面点确认后执行，含审批密钥签名；取消则 rejected；篡改/错设备被拒；
重复投递只执行一次）。

**未实现**（不要当成已完成）：

- WS/FCM 传输、手机扫码配对与设备证书、设备吊销。
- 密钥保护：目前私钥是 `~/.dpa-desktop/identity-private.der`（PKCS#8 明文文件），
  计划要求 DPAPI/TPM 包一层，属 Phase 2 余项，**不要在生产环境使用当前存储方式**。
- 本地模型、相册整理、文件夹分类、桌面自动化、安装包/自启/自动更新、SQLite 持久化。
- 主界面之外的页面（审计历史、应用目录编辑、设置）后续再补。
- 运行时状态只在内存里；重启后 `pending/` 会重新处理，但“已执行 id”的去重记录不持久化，
  依赖本地通道“处理完即移走文件”来避免重放。接 WS 前必须换成持久化收件箱/发件箱。

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

## 安全边界

- 只执行协议白名单里的意图；未知意图、未知参数、低于策略的 level、过期、重放、
  验签失败一律拒绝，和手机端跑同一份 `protocol/vectors`。
- L2 的确认是**本机界面点击 + 本机审批密钥签名**，云端伪造不了；L3 电脑无权批准。
- `capabilities/file_search.py` 仍是只读诊断工具，**没有接入信封**（ADR 0006 默认选项 A）。
