package cn.phoneassistant.desktop

import java.io.File
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
        "run" -> runWithUi(paths, config, identity)
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
    println("已处理（持久化）：" + runCatching {
        DesktopStore(File(paths.home, "desktop.db")).use { it.count() }
    }.getOrDefault(0))
    println("私钥保护：" + if (SecretStore.isProtected) "DPAPI（当前用户）" else "明文（非 Windows，尚未加密）")
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
    DesktopStore(File(paths.home, "desktop.db")).use { store ->
        val agent = InboxAgent(
            paths = paths,
            config = config,
            identity = identity,
            notifier = Notifier { title, body -> println("[通知] $title：$body") },
            confirm = null,
            store = store,
        )
        val results = agent.processAll()
        if (results.isEmpty()) {
            println("收件箱是空的。")
        } else {
            results.forEach { println(it) }
        }
    }
}

private fun runWithUi(paths: DesktopPaths, config: DesktopConfigFile, identity: DesktopIdentity) {
    if (config.hub_pubkey == null) {
        println("还没配置 hub 公钥，拒绝接收任何信封。先运行 device-sim install（或手工填 config.json）。")
        return
    }
    val state = UiState()
    state.log("已启动：${config.device_id}，监听 ${paths.inbox}")
    val store = DesktopStore(File(paths.home, "desktop.db"))
    try {
        val agent = InboxAgent(
            paths = paths,
            config = config,
            identity = identity,
            notifier = state.notifier,
            confirm = state::ask,
            store = store,
        )
        Thread(
            {
                while (true) {
                    val results = try {
                        agent.processAll()
                    } catch (e: Exception) {
                        listOf("ERROR ${e.message}")
                    }
                    results.forEach(state::log)
                    Thread.sleep(1000L)
                }
            },
            "dpa-inbox",
        ).apply { isDaemon = true }.start()
        runDesktopUi(state, config, paths)
    } finally {
        runCatching { store.close() }
    }
}

private fun count(dir: File): Int =
    dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.size ?: 0
