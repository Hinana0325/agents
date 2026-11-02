package cn.phoneassistant.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 端上应用目录：协议信封只送逻辑 app_id，包名只存在本机。
 * JSON 形如 {"wechat": "com.tencent.mm", "settings": "com.android.settings"}。
 */
object AppCatalog {

    private val APP_ID = Regex("^[a-z][a-z0-9_.-]{1,63}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    const val MAX_ENTRIES = 200

    /** 返回 null 表示合法，否则是中文原因。 */
    fun validate(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return "应用目录必须是 JSON 对象，例如 {\"wechat\":\"com.tencent.mm\"}"
        if (obj.size > MAX_ENTRIES) return "应用目录最多 $MAX_ENTRIES 项"
        for ((appId, value) in obj) {
            if (!APP_ID.matches(appId)) return "app_id 不合法：$appId"
            val packageName = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return "应用目录里的 $appId 必须是包名字符串"
            if (!PACKAGE.matches(packageName)) return "$appId 的包名不合法：$packageName"
        }
        return null
    }

    fun parse(element: JsonElement): Result<Map<String, String>> {
        validate(element)?.let { return Result.failure(IllegalArgumentException(it)) }
        val obj = element as JsonObject
        return Result.success(
            obj.entries.associate { (key, value) ->
                key to (value as JsonPrimitive).content
            },
        )
    }

    /**
     * 把新包并入目录，自动生成未被占用的 app1/app2… 逻辑 id。
     * 已存在的包不会重复添加；调用方负责最终校验。
     */
    fun addPackages(existing: Map<String, String>, packages: List<String>): Map<String, String> {
        val result = existing.toMutableMap()
        val usedIds = result.keys.toMutableSet()
        var nextId = 1
        for (packageName in packages.distinct()) {
            if (result.containsValue(packageName)) continue
            while (usedIds.contains("app$nextId")) nextId++
            val id = "app$nextId"
            result[id] = packageName
            usedIds += id
            nextId++
        }
        return result
    }
}
