package cn.phoneassistant.core

import java.math.BigInteger
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 协议 v1 的设备侧校验器：严格结构校验 + 策略重算 + 验签。
 *
 * 与 tools/vector-runner/py/validator.py 逐条对应；两侧对 protocol/vectors
 * 必须给出完全一致的结果。错误码顺序见 protocol/spec/error-codes.md。
 */
object EnvelopeValidator {

    val INTENTS = listOf(
        "open_app", "set_alarm", "create_event", "set_timer",
        "web_search", "save_note", "chat", "task_handoff", "voice_command",
    )
    private val HANDOFF_PROPOSAL_INTENTS = setOf(
        "open_app", "set_alarm", "create_event", "set_timer", "web_search", "save_note", "chat",
    )
    private val ROLES = setOf("hub", "phone", "watch", "pc")
    private val ORIGIN_KINDS = setOf("user_text", "user_voice", "scheduled", "derived")
    private val LEVEL_BASE = mapOf(
        "chat" to 1, "web_search" to 1, "set_timer" to 1, "save_note" to 1, "open_app" to 1,
        "set_alarm" to 1, "create_event" to 2, "task_handoff" to 2, "voice_command" to 2,
    )
    private val TARGETS = mapOf(
        "chat" to setOf("phone", "watch", "pc"),
        "web_search" to setOf("phone", "pc"),
        "set_timer" to setOf("phone", "watch", "pc"),
        "save_note" to setOf("phone", "pc"),
        "open_app" to setOf("phone", "pc"),
        "set_alarm" to setOf("phone"),
        "create_event" to setOf("phone"),
        "task_handoff" to setOf("phone", "watch", "pc"),
        "voice_command" to setOf("phone", "watch", "pc"),
    )
    private val ENVELOPE_FIELDS = setOf(
        "v", "id", "trace", "ts", "ttl_s", "from", "to", "intent", "params", "origin", "level", "parent", "sig",
    )
    private val REQUIRED_FIELDS = listOf("v", "id", "ts", "ttl_s", "from", "to", "intent", "params", "origin", "level", "sig")

    private val UUID7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val TS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val APP_ID = Regex("^[a-z][a-z0-9_.-]{1,63}$")
    private val URL_OK = Regex("^https://[^\\s]{1,512}$")
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")
    private val LANG = Regex("^[a-z]{2}(-[A-Z]{2})?$")

    fun validate(envelope: JsonElement, ctx: ValidationContext = ValidationContext()): EnvelopeValidationResult {
        if (envelope !is JsonObject) return EnvelopeValidationResult.fail("BAD_TYPE")

        val v = envelope["v"] ?: return EnvelopeValidationResult.fail("MISSING_FIELD")
        if (!isInt(v)) return EnvelopeValidationResult.fail("BAD_TYPE")
        if (intOf(v) != 1L) return EnvelopeValidationResult.fail("UNSUPPORTED_VERSION")

        checkKeys(envelope, ENVELOPE_FIELDS, REQUIRED_FIELDS)?.let { return EnvelopeValidationResult.fail(it) }

        checkString(envelope["id"], 1, Int.MAX_VALUE)?.let { return EnvelopeValidationResult.fail(it) }
        if (!UUID7.matches(str(envelope["id"])!!)) return EnvelopeValidationResult.fail("BAD_FORMAT")
        envelope["trace"]?.let { checkString(it, 1, 64)?.let { e -> return EnvelopeValidationResult.fail(e) } }
        checkString(envelope["ts"], 1, Int.MAX_VALUE)?.let { return EnvelopeValidationResult.fail(it) }
        if (!TS.matches(str(envelope["ts"])!!)) return EnvelopeValidationResult.fail("BAD_FORMAT")
        checkInt(envelope["ttl_s"], 1, 86400)?.let { return EnvelopeValidationResult.fail(it) }

        val from = envelope["from"]
        if (from !is JsonObject) return EnvelopeValidationResult.fail("BAD_TYPE")
        checkKeys(from, setOf("role", "kid"), listOf("role", "kid"))?.let { return EnvelopeValidationResult.fail(it) }
        val role = from["role"]
        if (role !is JsonPrimitive || !role.isString) return EnvelopeValidationResult.fail("BAD_TYPE")
        if (role.content !in ROLES) return EnvelopeValidationResult.fail("BAD_ENUM")
        checkString(from["kid"], 1, 64)?.let { return EnvelopeValidationResult.fail(it) }

        val to = envelope["to"]
        if (to !is JsonObject) return EnvelopeValidationResult.fail("BAD_TYPE")
        checkKeys(to, setOf("device"), listOf("device"))?.let { return EnvelopeValidationResult.fail(it) }
        checkString(to["device"], 1, 64)?.let { return EnvelopeValidationResult.fail(it) }

        val intentElement = envelope["intent"]
        if (intentElement !is JsonPrimitive || !intentElement.isString) return EnvelopeValidationResult.fail("BAD_TYPE")
        val intent = intentElement.content
        if (intent !in INTENTS) return EnvelopeValidationResult.fail("UNKNOWN_INTENT")

        val params = envelope["params"]
        if (params !is JsonObject) return EnvelopeValidationResult.fail("BAD_TYPE")

        val origin = envelope["origin"]
        if (origin !is JsonObject) return EnvelopeValidationResult.fail("BAD_TYPE")
        checkKeys(origin, setOf("kind", "via", "tainted"), listOf("kind", "tainted"))?.let { return EnvelopeValidationResult.fail(it) }
        val kind = origin["kind"]
        if (kind !is JsonPrimitive || !kind.isString) return EnvelopeValidationResult.fail("BAD_TYPE")
        if (kind.content !in ORIGIN_KINDS) return EnvelopeValidationResult.fail("BAD_ENUM")
        if (!isBool(origin["tainted"])) return EnvelopeValidationResult.fail("BAD_TYPE")
        origin["via"]?.let { checkString(it, 1, 64)?.let { e -> return EnvelopeValidationResult.fail(e) } }

        checkInt(envelope["level"], 1, 3)?.let { return EnvelopeValidationResult.fail(it) }
        envelope["parent"]?.let {
            if (!isString(it)) return EnvelopeValidationResult.fail("BAD_TYPE")
            if (!UUID7.matches(str(it)!!)) return EnvelopeValidationResult.fail("BAD_FORMAT")
        }

        val sigElement = envelope["sig"]
        if (sigElement !is JsonPrimitive || !sigElement.isString) return EnvelopeValidationResult.fail("BAD_TYPE")
        val sig = sigElement.content
        if (!B64URL.matches(sig) || sig.length < 86 || sig.length > 88) return EnvelopeValidationResult.fail("BAD_FORMAT")

        validateParams(intent, params)?.let { return EnvelopeValidationResult.fail(it) }

        val computed = computeLevel(intent, params, origin, ctx)
        val envelopeLevel = intOf(envelope["level"]!!).toInt()
        if (envelopeLevel < computed) return EnvelopeValidationResult.fail("LEVEL_BELOW_POLICY")
        val effective = maxOf(envelopeLevel, computed)

        ctx.deviceRole?.let { roleName ->
            if (roleName !in TARGETS.getValue(intent)) return EnvelopeValidationResult.fail("TARGET_NOT_ALLOWED")
        }

        ctx.now?.let { nowText ->
            if (kotlin.math.abs(ctx.clockOffsetSeconds) > 60) return EnvelopeValidationResult.fail("CLOCK_SKEW")
            val now = Instant.parse(nowText).plusSeconds(ctx.clockOffsetSeconds.toLong())
            val ts = Instant.parse(str(envelope["ts"])!!)
            val expires = ts.plusSeconds(intOf(envelope["ttl_s"]!!))
            if (now.isAfter(expires)) return EnvelopeValidationResult.fail("EXPIRED")
        }

        if (str(envelope["id"]) in ctx.seenIds) return EnvelopeValidationResult.fail("ID_REPLAYED")

        ctx.pubkeyJwk?.let { jwk ->
            if (!verifyEs256(envelope, jwk)) return EnvelopeValidationResult.fail("BAD_SIG")
        }

        return EnvelopeValidationResult.pass(effective, executable = intent != "voice_command")
    }

    /** 策略重算：base + 升级规则 + 污点，上限 3。app_class 只来自端上目录。 */
    fun computeLevel(intent: String, params: JsonObject, origin: JsonObject, ctx: ValidationContext): Int {
        var level = LEVEL_BASE.getValue(intent)
        when (intent) {
            "open_app" -> {
                val appId = str(params["app_id"]) ?: ""
                val appClass = ((ctx.appCatalog?.get(appId) as? JsonObject)?.get("class") as? JsonPrimitive)?.content ?: "general"
                if (appClass in setOf("finance", "payment", "system_settings")) level = maxOf(level, 2)
                str(params["url"])?.let { url ->
                    val host = try {
                        URI(url).host ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    if (host !in ctx.urlAllowlist) level = maxOf(level, 2)
                }
            }
            "set_alarm" -> {
                val repeat = str(params["repeat"]) ?: "none"
                if (repeat != "none" || ctx.recentAlarms24h > 5) level = maxOf(level, 2)
            }
            "create_event" -> {
                val attendees = params["attendees"]
                if (attendees != null) level = 3
            }
            "task_handoff" -> {
                val proposal = params["proposal"] as JsonObject
                level = maxOf(2, intOf(proposal["level"]!!).toInt())
            }
        }
        val tainted = (origin["tainted"] as JsonPrimitive).booleanOrNull == true
        if (tainted) level = minOf(3, level + 1)
        return level
    }

    private fun validateParams(intent: String, params: JsonObject): String? {
        fun keys(allowed: Set<String>, required: List<String>) = checkKeys(params, allowed, required)

        when (intent) {
            "chat" -> {
                keys(setOf("text"), listOf("text"))?.let { return it }
                return checkString(params["text"], 1, 4000)
            }
            "web_search" -> {
                keys(setOf("query"), listOf("query"))?.let { return it }
                return checkString(params["query"], 1, 200)
            }
            "save_note" -> {
                keys(setOf("text", "title"), listOf("text"))?.let { return it }
                checkString(params["text"], 1, 2000)?.let { return it }
                params["title"]?.let { return checkString(it, 1, 80) }
                return null
            }
            "set_timer" -> {
                keys(setOf("seconds", "label"), listOf("seconds"))?.let { return it }
                checkInt(params["seconds"], 1, 86400)?.let { return it }
                params["label"]?.let { return checkString(it, 1, 40) }
                return null
            }
            "set_alarm" -> {
                keys(setOf("hour", "minute", "label", "repeat", "days"), listOf("hour", "minute"))?.let { return it }
                checkInt(params["hour"], 0, 23)?.let { return it }
                checkInt(params["minute"], 0, 59)?.let { return it }
                params["label"]?.let { checkString(it, 1, 40)?.let { e -> return e } }
                params["repeat"]?.let { element ->
                    if (!isString(element)) return "BAD_TYPE"
                    if (str(element) !in setOf("none", "daily", "weekdays")) return "BAD_ENUM"
                }
                params["days"]?.let { element ->
                    if (element !is kotlinx.serialization.json.JsonArray) return "BAD_TYPE"
                    if (element.size > 7) return "BAD_RANGE"
                    val seen = mutableSetOf<Long>()
                    for (day in element) {
                        checkInt(day, 1, 7)?.let { return it }
                        val value = intOf(day)
                        if (!seen.add(value)) return "BAD_PARAM"
                    }
                }
                return null
            }
            "open_app" -> {
                keys(setOf("app_id", "url"), listOf("app_id"))?.let { return it }
                if (!isString(params["app_id"])) return "BAD_TYPE"
                if (!APP_ID.matches(str(params["app_id"])!!)) return "BAD_FORMAT"
                params["url"]?.let { element ->
                    if (!isString(element)) return "BAD_TYPE"
                    if (!URL_OK.matches(str(element)!!)) return "BAD_FORMAT"
                }
                return null
            }
            "create_event" -> {
                keys(
                    setOf("title", "begin_ms", "end_ms", "description", "location", "attendees"),
                    listOf("title", "begin_ms", "end_ms"),
                )?.let { return it }
                checkString(params["title"], 1, 200)?.let { return it }
                checkInt(params["begin_ms"], 1, (1L shl 62))?.let { return it }
                checkInt(params["end_ms"], 1, (1L shl 62))?.let { return it }
                if (intOf(params["end_ms"]!!) <= intOf(params["begin_ms"]!!)) return "BAD_PARAM"
                params["description"]?.let { checkString(it, 0, 2000)?.let { e -> return e } }
                params["location"]?.let { checkString(it, 0, 200)?.let { e -> return e } }
                params["attendees"]?.let { element ->
                    if (element !is kotlinx.serialization.json.JsonArray) return "BAD_TYPE"
                    if (element.size > 20) return "BAD_RANGE"
                    for (attendee in element) checkString(attendee, 3, 200)?.let { return it }
                }
                return null
            }
            "task_handoff" -> {
                keys(setOf("target_device_id", "proposal"), listOf("target_device_id", "proposal"))?.let { return it }
                checkString(params["target_device_id"], 1, 64)?.let { return it }
                val proposal = params["proposal"]
                if (proposal !is JsonObject) return "BAD_TYPE"
                checkKeys(proposal, setOf("intent", "params", "level"), listOf("intent", "params", "level"))?.let { return it }
                if (!isString(proposal["intent"])) return "BAD_TYPE"
                val pIntent = str(proposal["intent"])!!
                if (pIntent == "task_handoff" || pIntent == "voice_command") return "HANDOFF_DEPTH"
                if (pIntent !in HANDOFF_PROPOSAL_INTENTS) return "UNKNOWN_INTENT"
                checkInt(proposal["level"], 1, 3)?.let { return it }
                val pParams = proposal["params"]
                if (pParams !is JsonObject) return "BAD_TYPE"
                return validateParams(pIntent, pParams)
            }
            "voice_command" -> {
                keys(setOf("transcript", "lang"), listOf("transcript"))?.let { return it }
                checkString(params["transcript"], 1, 2000)?.let { return it }
                params["lang"]?.let { element ->
                    if (!isString(element)) return "BAD_TYPE"
                    if (!LANG.matches(str(element)!!)) return "BAD_FORMAT"
                }
                return null
            }
            else -> return "UNKNOWN_INTENT"
        }
    }

    fun envelopeHash(envelope: JsonObject): String {
        val payload = JsonObject(envelope.filterKeys { it != "sig" })
        val digest = MessageDigest.getInstance("SHA-256").digest(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyEs256(envelope: JsonObject, jwk: JsonObject): Boolean {
        return try {
            val sigText = str(envelope["sig"]) ?: return false
            val raw = Base64.getUrlDecoder().decode(sigText)
            if (raw.size != 64) return false
            val x = Base64.getUrlDecoder().decode(str(jwk["x"]) ?: return false)
            val y = Base64.getUrlDecoder().decode(str(jwk["y"]) ?: return false)
            val params = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params),
            )
            val payload = JsonObject(envelope.filterKeys { it != "sig" })
            val data = Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8)
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(raw)
        } catch (e: Exception) {
            false
        }
    }

    // ---- 基础类型判定（与 Python 侧语义保持一致） ----

    private fun isString(element: JsonElement?): Boolean =
        element is JsonPrimitive && element.isString

    /** kotlinx 的 booleanOrNull 会解析字符串内容，所以必须同时排除 isString。 */
    private fun isBool(element: JsonElement?): Boolean =
        element is JsonPrimitive && !element.isString && element.booleanOrNull != null

    private fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isInt(element: JsonElement?): Boolean {
        if (element !is JsonPrimitive || element.isString || element.booleanOrNull != null) return false
        val content = element.content
        if (content.isEmpty() || content.contains('.') || content.contains('e') || content.contains('E')) return false
        return content.toLongOrNull() != null
    }

    private fun intOf(element: JsonElement): Long = (element as JsonPrimitive).content.toLong()

    private fun cpLength(text: String): Int = text.codePointCount(0, text.length)

    private fun checkString(element: JsonElement?, min: Int, max: Int): String? {
        if (!isString(element)) return "BAD_TYPE"
        val length = cpLength(str(element)!!)
        if (length < min || length > max) return "BAD_RANGE"
        return null
    }

    private fun checkInt(element: JsonElement?, min: Long, max: Long): String? {
        if (!isInt(element)) return "BAD_TYPE"
        val value = intOf(element!!)
        if (value < min || value > max) return "BAD_RANGE"
        return null
    }

    private fun checkKeys(obj: JsonObject, allowed: Set<String>, required: List<String>): String? {
        for (key in obj.keys) if (key !in allowed) return "UNKNOWN_FIELD"
        for (key in required) if (key !in obj) return "MISSING_FIELD"
        return null
    }
}
