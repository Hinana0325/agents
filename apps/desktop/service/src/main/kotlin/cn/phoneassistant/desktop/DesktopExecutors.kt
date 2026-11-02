package cn.phoneassistant.desktop

import cn.phoneassistant.core.runtime.ExecutionOutcome
import cn.phoneassistant.core.runtime.IntentExecutor
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 电脑端执行器：只实现协议白名单里电脑可达的意图。
 * 日历/闹钟明确不支持（策略 targets 只有手机），返回失败而不是假装成功。
 *
 * 打开浏览器 / 启动程序属于可见副作用；config.dry_run=true 时只返回将要执行的命令，
 * 用于测试和联调。
 */
class DesktopExecutors(
    private val notesDir: File,
    private val config: DesktopConfigFile,
    private val timers: TimerService,
) : IntentExecutor {

    override fun execute(intent: String, params: JsonObject): ExecutionOutcome = try {
        when (intent) {
            "chat" -> ExecutionOutcome(true, "chat")
            "save_note" -> saveNote(params)
            "web_search" -> webSearch(params)
            "open_app" -> openApp(params)
            "set_timer" -> setTimer(params)
            else -> ExecutionOutcome(false, "电脑端不执行 $intent")
        }
    } catch (e: Exception) {
        ExecutionOutcome(false, e.message ?: e.javaClass.simpleName)
    }

    private fun saveNote(params: JsonObject): ExecutionOutcome {
        val text = params["text"]?.jsonPrimitive?.contentOrNull
            ?: return ExecutionOutcome(false, "缺少 text")
        val title = params["title"]?.jsonPrimitive?.contentOrNull ?: "备忘"
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(40).ifBlank { "note" }
        val file = File(notesDir, "${stamp}_$safe.txt")
        file.writeText(text, Charsets.UTF_8)
        return ExecutionOutcome(true, "notes/${file.name}")
    }

    private fun webSearch(params: JsonObject): ExecutionOutcome {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return ExecutionOutcome(false, "缺少 query")
        val url = config.web_search_template.replace("{q}", URLEncoder.encode(query, "UTF-8"))
        if (!config.dry_run) browse(url)
        return ExecutionOutcome(true, url)
    }

    private fun openApp(params: JsonObject): ExecutionOutcome {
        val appId = params["app_id"]?.jsonPrimitive?.contentOrNull
            ?: return ExecutionOutcome(false, "缺少 app_id")
        val entry = config.app_catalog[appId] as? JsonObject
            ?: return ExecutionOutcome(false, "应用目录里没有 $appId")
        val url = (entry["url"] as? JsonPrimitive)?.contentOrNull
        val command = (entry["command"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return when {
            url != null -> {
                if (!config.dry_run) browse(url)
                ExecutionOutcome(true, url)
            }
            !command.isNullOrEmpty() -> {
                if (!config.dry_run) ProcessBuilder(command).start()
                ExecutionOutcome(true, command.joinToString(" "))
            }
            else -> ExecutionOutcome(false, "目录项 $appId 既没有 url 也没有 command")
        }
    }

    private fun setTimer(params: JsonObject): ExecutionOutcome {
        val seconds = params["seconds"]?.jsonPrimitive?.intOrNull
            ?: return ExecutionOutcome(false, "缺少 seconds")
        val label = params["label"]?.jsonPrimitive?.contentOrNull ?: "计时结束"
        timers.schedule(seconds, label)
        return ExecutionOutcome(true, "timer:${seconds}s")
    }

    private fun browse(url: String) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw IllegalStateException("当前桌面环境不支持打开浏览器")
        }
        Desktop.getDesktop().browse(URI(url))
    }
}
