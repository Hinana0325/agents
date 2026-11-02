# ADR 0010：电脑端打包用 jpackage app-image，MSI 暂缓

状态：已采纳（2026-11-02）

## 背景

计划要求“打包出 MSI”。本机没有 WiX（`candle.exe`/`light.exe` 都不存在），
而 `jpackage --type msi` 依赖 WiX。安装包不是本轮唯一目标，先用 JDK 自带的
`jpackage --type app-image` 产出**自带 JRE、不依赖本机 Java** 的目录式应用。

## 决定

- Gradle 任务 `packageAppImage`（`:apps:desktop:service`）：
  `installDist` → `jpackage --type app-image --name DpaDesktop`，
  输出 `build/jpackage/DpaDesktop/DpaDesktop.exe`，内置 runtime。
- 版本与 Android 端保持一致为 `0.2.0`。
- **GUI 版不带控制台**：`status/once/pair` 这些 CLI 命令继续用 `installDist` 的 `service.bat`。
- MSI 等装好 WiX 后再加一个 `packageMsi` 任务，参数与 app-image 相同、只换 `--type msi`。

## 后果

- 现在可以拷走整个 `DpaDesktop` 目录直接运行，不要求目标机装 Java。
- 没有安装器：没有开始菜单项、没有卸载登记、没有自启；这些随 MSI 或后续安装脚本补。
- 打包暴露了一个真实缺陷并已修复：GUI 模式下缺 hub 公钥时原本直接退出（窗口闪一下就没），
  现在会正常开窗并显示“未配置 hub 公钥”的指引。
