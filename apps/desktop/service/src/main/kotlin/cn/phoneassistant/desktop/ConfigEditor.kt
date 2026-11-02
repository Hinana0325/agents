package cn.phoneassistant.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置页的校验：把文本框里的内容变成可保存的 [DesktopConfigFile]。
 * 校验失败返回中文原因，绝不把半成品写进 config.json。
 */
object ConfigEditor {

    private val APP_ID = Regex("^[a-z][a-z0-9_.-]{1,63}$")
    private val HOST = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
    private val ENTRY_KEYS = setOf("url", "command")

    fun validate(
        catalogText: String,
        allowlistText: String,
        webSearchTemplate: String,
        dryRun: Boolean,
        base: DesktopConfigFile,
    ): Result<DesktopConfigFile> {
        val catalogElement = try {
            Json.parseToJsonElement(catalogText.ifBlank { "{}" })
        } catch (e: Exception) {
            return failure("应用目录不是合法 JSON：${e.message}")
        }
        val catalog = catalogElement as? JsonObject
            ?: return failure("应用目录必须是 JSON 对象，例如 {\"com.example.site\": {\"url\": \"https://example.com\"}}")

        for ((appId, value) in catalog) {
            if (!APP_ID.matches(appId)) {
                return failure("应用 id 不合法：$appId（小写字母开头，只允许 a-z0-9_.-）")
            }
            val entry = value as? JsonObject ?: return failure("应用 $appId 的值必须是对象")
            for (key in entry.keys) {
                if (key !in ENTRY_KEYS) return failure("应用 $appId 含未知字段：$key")
            }
            val url = (entry["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val command = entry["command"] as? JsonArray
            if (url == null && command == null) {
                return failure("应用 $appId 至少要有一个 url 或 command")
            }
            if (url != null && !url.startsWith("https://")) {
                return failure("应用 $appId 的 url 必须以 https:// 开头")
            }
            if (command != null) {
                if (command.isEmpty() || command.size > 8) {
                    return failure("应用 $appId 的 command 需要 1 到 8 个参数")
                }
                if (command.any { (it as? JsonPrimitive)?.isString != true || it.content.isBlank() }) {
                    return failure("应用 $appId 的 command 每一项都必须是非空字符串")
                }
            }
        }

        val hosts = allowlistText.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toList()
        for (host in hosts) {
            if (!HOST.matches(host)) return failure("允许清单里的 host 不合法：$host")
        }

        if (!webSearchTemplate.startsWith("https://") || !webSearchTemplate.contains("{q}")) {
            return failure("搜索模板必须以 https:// 开头并包含 {q}")
        }

        return Result.success(
            base.copy(
                app_catalog = catalog,
                url_allowlist = hosts.distinct(),
                web_search_template = webSearchTemplate.trim(),
                dry_run = dryRun,
            ),
        )
    }

    private fun failure(message: String): Result<DesktopConfigFile> =
        Result.failure(IllegalArgumentException(message))
}
