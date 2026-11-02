package cn.phoneassistant.desktop

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "run"
    val argument = args.getOrNull(1)
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
        "pair", "pair-request" -> printPairRequest(config, identity)
        "pair-qr" -> writePairingQr(config, identity, argument)
        "trust-phone" -> trustPhone(paths, config, argument)
        "verify-cert" -> verifyCertificate(paths, config, argument)
        "revoke-device" -> revokeDevice(paths, config, argument)
        "once" -> runOnce(paths, config, identity)
        "run" -> runWithUi(paths, config, identity)
        "install-autostart" -> installAutostart(paths)
        "remove-autostart" -> removeAutostart()
        else -> printUsage()
    }
}

private fun printUsage() {
    println(
        """
        用法：service <命令>
          status   查看设备配置、公钥、配对状态与收件箱
          pair     输出配对请求 JSON（交给手机签发设备证书）
          pair-qr <输出.png>            把配对请求导出成二维码 PNG
          trust-phone <手机公钥.json>   记录信任根（手机公钥）
          verify-cert <证书.json>       验证手机签发的设备证书并保存
          revoke-device <吊销.json>     验证手机签发的吊销记录并作废证书
          once     处理 inbox/ 与 pending/ 里的全部信封，然后退出
          run      常驻：每秒处理信封，托盘提示，L2 弹确认
          install-autostart   写入登录自启（HKCU Run，仅 Windows）
          remove-autostart    删除登录自启
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
    println("信任根（手机公钥）：${if (config.phone_pubkey == null) "未配置" else "已配置"}")
    println("设备证书：${Pairing.describeCertificate(config.device_cert)}")
    println("证书状态：${Pairing.certificateState(config.device_cert)}")
    println("已吊销证书：${config.revoked_certs.size} 个")
    runCatching {
        val audit = cn.phoneassistant.core.AuditLog(File(paths.home, "audit.json"))
        println("审计链：${audit.verify() ?: "完整"}（${audit.list().size} 条）")
    }
    println("dry_run：${config.dry_run}")
    println("应用目录：${config.app_catalog.size} 项；URL 允许清单：${config.url_allowlist.size} 项")
    println("inbox：${count(paths.inbox)}；pending：${count(paths.pending)}；outbox：${count(paths.outbox)}")
    println("已处理（持久化）：" + runCatching {
        DesktopStore(File(paths.home, "desktop.db")).use { it.count() }
    }.getOrDefault(0))
    println("私钥保护：" + if (SecretStore.isProtected) "DPAPI（当前用户）" else "明文（非 Windows，尚未加密）")
    println(
        "登录自启：" + when {
            !Autostart.isSupported -> "不支持（仅 Windows）"
            Autostart.isInstalled() -> "已启用"
            else -> "未启用"
        },
    )
    println("本机公钥：")
    println(pretty.encodeToString(identity.publicJwk()))
}

private fun printPairRequest(config: DesktopConfigFile, identity: DesktopIdentity) {
    val pretty = Json { prettyPrint = true }
    println(pretty.encodeToString(Pairing.request(config, identity)))
}

private fun writePairingQr(config: DesktopConfigFile, identity: DesktopIdentity, filePath: String?) {
    if (filePath == null) {
        println("用法：service pair-qr <输出.png>")
        return
    }
    try {
        val request = Pairing.request(config, identity)
        PairingQr.writePng(Json.encodeToString(request), File(filePath))
        println("已写入配对二维码：$filePath")
    } catch (e: Exception) {
        println("生成失败：${e.message}")
    }
}

private fun trustPhone(paths: DesktopPaths, config: DesktopConfigFile, filePath: String?) {
    if (filePath == null) {
        println("用法：service trust-phone <手机公钥.json>")
        return
    }
    val parsed = try {
        Json.parseToJsonElement(File(filePath).readText())
    } catch (e: Exception) {
        println("读取失败：${e.message}")
        return
    }
    val obj = parsed as? JsonObject
    if (obj == null) {
        println("文件必须是 JSON 对象")
        return
    }
    val phoneKey = (obj["public_key_jwk"] as? JsonObject) ?: obj
    Pairing.trust(paths, config, phoneKey).fold(
        onSuccess = { println("已记录手机公钥（信任根）。") },
        onFailure = { println("失败：${it.message}") },
    )
}

private fun verifyCertificate(paths: DesktopPaths, config: DesktopConfigFile, filePath: String?) {    if (filePath == null) {
        println("用法：service verify-cert <证书.json>")
        return
    }
    val parsed = try {
        Json.parseToJsonElement(File(filePath).readText())
    } catch (e: Exception) {
        println("读取失败：${e.message}")
        return
    }
    val cert = parsed as? JsonObject
    if (cert == null) {
        println("文件必须是 JSON 对象")
        return
    }
    Pairing.verifyCertificate(paths, config, cert).fold(
        onSuccess = { println("证书有效：${Pairing.describeCertificate(cert)}") },
        onFailure = { println("验证失败：${it.message}") },
    )
}

private fun revokeDevice(paths: DesktopPaths, config: DesktopConfigFile, filePath: String?) {
    if (filePath == null) {
        println("用法：service revoke-device <吊销.json>")
        return
    }
    val parsed = try {
        Json.parseToJsonElement(File(filePath).readText())
    } catch (e: Exception) {
        println("读取失败：${e.message}")
        return
    }
    val revocation = parsed as? JsonObject
    if (revocation == null) {
        println("文件必须是 JSON 对象")
        return
    }
    Pairing.revoke(paths, config, revocation).fold(
        onSuccess = { updated ->
            println(
                "已吊销证书：${updated.revoked_certs.size} 个；本机证书" +
                    if (updated.device_cert == null) "已作废。" else "仍有效。",
            )
        },
        onFailure = { println("吊销失败：${it.message}") },
    )
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
    val state = UiState()
    val store = DesktopStore(File(paths.home, "desktop.db"))
    try {
        if (config.hub_pubkey == null) {
            // GUI 应用没有控制台；缺公钥时也要把窗口开出来，否则用户只看到“闪一下就没了”。
            state.log("未配置 hub 公钥：不会接收信封。")
            state.log("把 config.json 的 hub_pubkey 填好（或用 device-sim install）后重启。")
        } else {
            state.log("已启动：${config.device_id}，监听 ${paths.inbox}")
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
        }
        runDesktopUi(state, config, paths, store, identity)
    } finally {
        runCatching { store.close() }
    }
}

private fun count(dir: File): Int =
    dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.size ?: 0

private fun installAutostart(paths: DesktopPaths) {
    if (!Autostart.isSupported) {
        println("登录自启只支持 Windows。")
        return
    }
    try {
        val cmd = Autostart.install(paths)
        println("已启用登录自启：$cmd")
        println("取消：service.bat remove-autostart")
    } catch (e: Exception) {
        println("启用失败：${e.message}")
    }
}

private fun removeAutostart() {
    if (!Autostart.isSupported) {
        println("登录自启只支持 Windows。")
        return
    }
    println(if (Autostart.remove()) "已删除登录自启。" else "没有找到自启项，或删除失败。")
}
