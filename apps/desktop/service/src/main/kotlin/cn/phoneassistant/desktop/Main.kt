package cn.phoneassistant.desktop

import java.awt.GraphicsEnvironment
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "run"
    val paths = DesktopPaths.resolve()
    val config = try {
        DesktopConfig.loadOrCreate(paths)
    } catch (e: Exception) {
        println("配置读取失败：${e.message}")
        return
    }
    val identity = try {
        DesktopIdentity(paths.home)
    } catch (e: Exception) {
        println("身份密钥初始化失败：${e.message}")
        return
    }

    when (command) {
        "status" -> printStatus(paths, config, identity)
        "pair" -> printPair(config, identity)
        "once" -> runOnce(paths, config, identity)
        "run" -> runForever(paths, config, identity)
        else -> printUsage()
    }
}

private fun printUsage() {
    println(
        """
        用法：service <命令>
          status   查看设备配置、公钥与收件箱状态
          pair     输配对用 JSON（device_id + 公钥 JWK）
          once     处理 inbox/ 与 pending/ 里的全部信封，然后退出
          run      常驻：每秒处理信封，托盘提示，L2 弹确认
        环境变量：
          DPA_DESKTOP_HOME  覆盖数据目录（默认 ~/.dpa-desktop）
        """.trimIndent(),
    )
}

private fun printStatus(paths: DesktopPaths, config: DesktopConfigFile, identity: DesktopIdentity) {
    val pretty = Json { prettyPrint = true }
    println("数据目录：${paths.home}")
    println("设备：${config.device_id}（role=${config.role}）")
    println("hub 公钥：${if (config.hub_pubkey == null) "未配置（配置前拒绝接收信封）" else "已配置（kid=${config.hub_kid}）"}")
    println("dry_run：${config.dry_run}")
    println("应用目录：${config.app_catalog.size} 项；URL 允许清单：${config.url_allowlist.size} 项")
    println("inbox：${count(paths.inbox)}；pending：${count(paths.pending)}；outbox：${count(paths.outbox)}")
    println("本机公钥：")
    println(pretty.encodeToString(identity.publicJwk()))
}

private fun printPair(config: DesktopConfigFile, identity: DesktopIdentity) {
    val pretty = Json { prettyPrint = true }
    val payload = buildJsonObject {
        put("device_id", config.device_id)
        put("role", config.role)
        put("public_key_jwk", identity.publicJwk())
    }
    println(pretty.encodeToString(payload))
}

private fun runOnce(paths: DesktopPaths, config: DesktopConfigFile, identity: DesktopIdentity) {
    if (config.hub_pubkey == null) {
        println("还没配置 hub 公钥，拒绝接收任何信封。先运行 device-sim install（或手工填 config.json）。")
        return
    }
    val agent = InboxAgent(paths, config, identity, TrayUI(), interactive = false)
    val results = agent.processAll()
    if (results.isEmpty()) {
        println("收件箱是空的。")
    } else {
        results.forEach { println(it) }
    }
}

private fun runForever(paths: DesktopPaths, config: DesktopConfigFile, identity: DesktopIdentity) {
    if (config.hub_pubkey == null) {
        println("还没配置 hub 公钥，拒绝接收任何信封。先运行 device-sim install（或手工填 config.json）。")
        return
    }
    val tray = TrayUI()
    val trayReady = tray.install()
    val interactive = !GraphicsEnvironment.isHeadless()
    println("电脑助理已启动：${config.device_id}，监听 ${paths.inbox}")
    println(
        if (trayReady) "托盘已就绪；L2 会在桌面弹出确认框。"
        else "没有系统托盘；L2 会留在 pending/，等下次交互式运行再确认。",
    )
    val agent = InboxAgent(paths, config, identity, tray, interactive = interactive)
    while (true) {
        val results = try {
            agent.processAll()
        } catch (e: Exception) {
            listOf("ERROR ${e.message}")
        }
        results.forEach { println("[${Instant.now()}] $it") }
        Thread.sleep(1000L)
    }
}

private fun count(dir: java.io.File): Int =
    dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.size ?: 0
