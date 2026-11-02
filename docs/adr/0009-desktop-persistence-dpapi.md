# ADR 0009：桌面端 SQLite 持久化与 DPAPI 密钥保护

状态：已采纳（2026-11-02）。补上 ADR 0007 里标记为“Phase 2 余项”的两处。

## 背景

- 运行时去重状态只在内存：进程重启后，重复投递的信封可能再次执行（P0 安全/幂等缺口）。
- 身份私钥是明文 PKCS#8 文件：文件被拷走就等于设备身份泄露。

## 决定

1. **SQLite 持久化**（`org.xerial:sqlite-jdbc`，表 `processed`）：
   - 只落**终态**回执（`done/failed/rejected/cancelled`）；`awaiting_confirm` 不落库，
     重启后会重新处理，避免“待确认任务静默丢失”。
   - 处理前先查 `envelope_id`，命中则直接返回上次回执并移入 `processed/`，不再执行。
   - 数据库文件：`~/.dpa-desktop/desktop.db`。
2. **DPAPI 保护私钥**（JNA `Crypt32Util`，当前用户作用域）：
   - 私钥写 `identity-private.dpapi`；首次加载旧的明文 `identity-private.der` 时自动加密并删除明文。
   - 非 Windows 系统没有 DPAPI，暂时明文落盘，`status` 会明确显示“明文（非 Windows）”。
   - 公钥仍明文（本来就是公开材料）。

## 后果

- 跨重启幂等成立：重复投递只返回 DUPLICATE，不再产生副作用（已有单测与本机 E2E 证据）。
- DPAPI 绑定当前 Windows 用户/机器：换用户、重装系统或迁移到别的电脑后私钥无法解开，需要重新配对；
  这是有意的安全取舍，备份方案等配对流程落地后一起做。
- TPM（CNG）与跨平台密钥库仍未做；SQLite 目前只存去重与回执，审计历史/收件箱持久化后续再扩展。
