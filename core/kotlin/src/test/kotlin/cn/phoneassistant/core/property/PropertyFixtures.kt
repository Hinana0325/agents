package cn.phoneassistant.core.property

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.ValidationContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 属性测试的公共基建：加载 protocol/vectors 里结构合法的信封语料，
 * 供各属性测试复用（对应 DEVELOPMENT.md 第 10 节"属性测试/模糊"）。
 *
 * 语料只保留 EnvelopeValidator 以默认上下文判定为合法的信封，保证
 * computeLevel 等策略重算入口可以安全调用（params 结构完整）。
 */
object PropertyFixtures {

    /** 每个意图的最小合法 params，构造 handoff 提案等场景时复用。 */
    val MINIMAL_PARAMS: Map<String, JsonObject> = mapOf(
        "chat" to JsonObject(mapOf("text" to JsonPrimitive("你好"))),
        "web_search" to JsonObject(mapOf("query" to JsonPrimitive("天气"))),
        "save_note" to JsonObject(mapOf("text" to JsonPrimitive("笔记"))),
        "set_timer" to JsonObject(mapOf("seconds" to JsonPrimitive(60))),
        "open_app" to JsonObject(mapOf("app_id" to JsonPrimitive("com.example.app"))),
        "set_alarm" to JsonObject(mapOf("hour" to JsonPrimitive(7), "minute" to JsonPrimitive(30))),
        "create_event" to JsonObject(
            mapOf(
                "title" to JsonPrimitive("评审"),
                "begin_ms" to JsonPrimitive(1),
                "end_ms" to JsonPrimitive(2),
            ),
        ),
        "task_handoff" to JsonObject(
            mapOf(
                "target_device_id" to JsonPrimitive("phone-2"),
                "proposal" to JsonObject(
                    mapOf(
                        "intent" to JsonPrimitive("chat"),
                        "params" to JsonObject(mapOf("text" to JsonPrimitive("你好"))),
                        "level" to JsonPrimitive(1),
                    ),
                ),
            ),
        ),
        "voice_command" to JsonObject(mapOf("transcript" to JsonPrimitive("明天早上七点叫我"))),
    )

    /** 从 vectors.dir 读取所有信封向量（排除 cert/revocation/receipt/approval 等专用类别）。 */
    fun loadVectorEnvelopes(): List<JsonObject> {
        val dir = File(System.getProperty("vectors.dir") ?: error("vectors.dir system property is required"))
        check(dir.isDirectory) { "vectors dir not found: $dir" }
        val envelopes = mutableListOf<JsonObject>()
        dir.listFiles { file -> file.isDirectory }!!.sortedBy { it.name }.forEach { category ->
            val file = File(category, "cases.json")
            if (!file.exists()) return@forEach
            Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray.forEach { element ->
                (element.jsonObject["envelope"] as? JsonObject)?.let { envelopes += it }
            }
        }
        return envelopes
    }

    /** 语料中经默认上下文校验为合法的信封（无验签/无过期判定）。 */
    fun loadValidEnvelopes(): List<JsonObject> =
        loadVectorEnvelopes().filter { EnvelopeValidator.validate(it, ValidationContext()).ok }

    /** 复制信封并把 origin.tainted 置为给定值。 */
    fun withTainted(envelope: JsonObject, tainted: Boolean): JsonObject {
        val origin = envelope["origin"] as JsonObject
        val newOrigin = JsonObject(origin.toMutableMap().apply { put("tainted", JsonPrimitive(tainted)) })
        return JsonObject(envelope.toMutableMap().apply { put("origin", newOrigin) })
    }

    /** 复制信封并把 origin.kind 置为给定值（其余字段不动）。 */
    fun withOriginKind(envelope: JsonObject, kind: String): JsonObject {
        val origin = envelope["origin"] as JsonObject
        val newOrigin = JsonObject(origin.toMutableMap().apply { put("kind", JsonPrimitive(kind)) })
        return JsonObject(envelope.toMutableMap().apply { put("origin", newOrigin) })
    }

    fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(element: JsonElement?): Int? =
        (element as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.content?.toIntOrNull()

    /** 与 DeviceRuntimeTest 一致的 UUIDv7 形状 id 生成器（计数器保证唯一）。 */
    class IdGenerator {
        private var counter = 0
        fun next(): String {
            counter++
            return "0193f0a8-9c1b-7%03x-8%03x-%012x".format(counter, counter, counter)
        }
    }
}
