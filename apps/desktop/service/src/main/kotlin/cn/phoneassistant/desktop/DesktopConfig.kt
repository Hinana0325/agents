package cn.phoneassistant.desktop

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 电脑端本地目录布局（默认 ~/.dpa-desktop，可用 DPA_DESKTOP_HOME 覆盖）。
 * inbox/outbox 是 Phase 2 的“本地信封通道”：云端 hub 就绪前用它做端到端联调，
 * 就绪后替换为 WS 拉取 + FCM 唤醒，运行时与执行器不动。
 */
class DesktopPaths(val home: File) {

    val inbox = File(home, "inbox")
    val outbox = File(home, "outbox")
    val processed = File(home, "processed")
    val pending = File(home, "pending")
    val notes = File(home, "notes")
    val configFile = File(home, "config.json")

    fun ensure() {
        listOf(home, inbox, outbox, processed, pending, notes).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) error("cannot create $dir")
        }
    }

    companion object {
        fun resolve(): DesktopPaths {
            val override = System.getenv("DPA_DESKTOP_HOME")
            val home = if (!override.isNullOrBlank()) {
                File(override)
            } else {
                File(System.getProperty("user.home"), ".dpa-desktop")
            }
            return DesktopPaths(home).also { it.ensure() }
        }
    }
}

@Serializable
data class DesktopConfigFile(
    val device_id: String,
    val role: String = "pc",
    val hub_pubkey: JsonObject? = null,
    val hub_kid: String = "hub-test-1",
    val app_catalog: JsonObject = JsonObject(emptyMap()),
    val url_allowlist: List<String> = emptyList(),
    val web_search_template: String = "https://www.bing.com/search?q={q}",
    val dry_run: Boolean = false,
)

class DesktopConfigLoadError(message: String) : Exception(message)

/** 读取 config.json；不存在时生成一份最小配置（hub 公钥留空，配好之前拒绝接收信封）。 */
object DesktopConfig {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadOrCreate(paths: DesktopPaths): DesktopConfigFile {
        if (!paths.configFile.exists()) {
            val fresh = DesktopConfigFile(
                device_id = "pc-" + UUID.randomUUID().toString().take(8),
            )
            paths.configFile.writeText(json.encodeToString(DesktopConfigFile.serializer(), fresh))
            return fresh
        }
        return try {
            json.decodeFromString(DesktopConfigFile.serializer(), paths.configFile.readText())
        } catch (e: Exception) {
            throw DesktopConfigLoadError("config.json 解析失败：${e.message}")
        }
    }

    fun save(paths: DesktopPaths, config: DesktopConfigFile) {
        paths.configFile.writeText(json.encodeToString(DesktopConfigFile.serializer(), config))
    }
}
