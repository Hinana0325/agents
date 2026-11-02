package cn.phoneassistant.desktop

import cn.phoneassistant.core.EnvelopeValidator
import cn.phoneassistant.core.runtime.DeviceRuntime
import cn.phoneassistant.core.runtime.EnvelopeState
import cn.phoneassistant.core.runtime.TrustedApprover
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 本地信封通道的消费端：inbox 目录下的 JSON 投递 → DeviceRuntime 校验/去重/执行 → outbox 回执。
 *
 * 这是云端 hub 就绪前的传输替代品；换成 WS 拉取后，本类的“处理一条信封”逻辑原样保留。
 * L2：interactive 时弹托盘确认并用本机审批密钥签名；非交互时留在 pending/ 等下次确认。
 * L3：电脑没有 L3 审批权，留在 pending/ 并提示去手机批准。
 */
class InboxAgent(
    private val paths: DesktopPaths,
    private val config: DesktopConfigFile,
    private val identity: DesktopIdentity,
    private val tray: TrayUI,
    private val interactive: Boolean,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val runtime = DeviceRuntime(
        deviceId = config.device_id,
        deviceRole = config.role,
        executor = DesktopExecutors(paths.notes, config, TimerService(tray)),
        hubPublicKeys = config.hub_pubkey?.let { mapOf(config.hub_kid to it) } ?: emptyMap(),
        approvers = mapOf(
            config.device_id to TrustedApprover(config.device_id, 2, identity.publicJwk()),
        ),
        appCatalog = config.app_catalog,
        urlAllowlist = config.url_allowlist,
    )

    fun hasHubKey(): Boolean = config.hub_pubkey != null

    /** 处理 inbox 与 pending 里的全部信封，返回人类可读的结果行。 */
    fun processAll(): List<String> {
        val results = mutableListOf<String>()
        for (file in listJsonFiles(paths.pending) + listJsonFiles(paths.inbox)) {
            results += processFile(file)
        }
        results += runtime.sweepExpired().map { "EXPIRED $it" }
        return results
    }

    private fun processFile(file: File): String {
        val parsed = try {
            json.parseToJsonElement(file.readText())
        } catch (e: Exception) {
            writeReceipt(file.nameWithoutExtension, rejected("unknown", "BAD_TYPE", "无法解析 JSON"))
            moveTo(file, paths.processed)
            return "BAD_TYPE ${file.name}"
        }
        val envelope = parsed as? JsonObject
        if (envelope == null) {
            writeReceipt(file.nameWithoutExtension, rejected("unknown", "BAD_TYPE", "信封不是 JSON 对象"))
            moveTo(file, paths.processed)
            return "BAD_TYPE ${file.name}"
        }
        val id = (envelope["id"] as? JsonPrimitive)?.contentOrNull
        if (id == null) {
            writeReceipt(file.nameWithoutExtension, rejected("unknown", "MISSING_FIELD", "缺少 id"))
            moveTo(file, paths.processed)
            return "MISSING_FIELD ${file.name}"
        }
        val toDevice = ((envelope["to"] as? JsonObject)?.get("device") as? JsonPrimitive)?.contentOrNull
        if (toDevice != null && toDevice != config.device_id) {
            writeReceipt(id, rejected(id, "TARGET_NOT_ALLOWED", "目标是 $toDevice，不是 ${config.device_id}"))
            moveTo(file, paths.processed)
            return "TARGET_NOT_ALLOWED ${file.name}"
        }

        val receipt = runtime.submit(parsed)
        val state = runtime.stateOf(id)
        val outcome: JsonObject = if (state == EnvelopeState.AWAITING_CONFIRM) {
            val level = runtime.requiredLevelOf(id) ?: 2
            when {
                level >= 3 -> {
                    tray.notify("需要手机批准", "这条任务需要 L3，电脑只能批 L2")
                    writeReceipt(id, receipt)
                    moveTo(file, paths.pending)
                    return "AWAITING_PHONE ${file.name}"
                }
                interactive -> {
                    val approved = tray.confirm("确认执行", describe(envelope))
                    val final = if (approved) runtime.approve(id, approval(envelope, level)) else runtime.reject(id)
                    writeReceipt(id, final)
                    moveTo(file, paths.processed)
                    return "${status(final)} ${file.name}"
                }
                else -> {
                    writeReceipt(id, receipt)
                    moveTo(file, paths.pending)
                    return "AWAITING_CONFIRM ${file.name}"
                }
            }
        } else {
            receipt
        }
        writeReceipt(id, outcome)
        moveTo(file, paths.processed)
        return "${status(outcome)} ${file.name}"
    }

    private fun approval(envelope: JsonObject, level: Int): JsonObject {
        val payload = buildJsonObject {
            put("v", 1)
            put("envelope_hash", EnvelopeValidator.envelopeHash(envelope))
            put("decision", "approve")
            put("level", level)
            put("approver_device", config.device_id)
            put("ts", timestamp())
            put("nonce", nonce())
        }
        return JsonObject(payload.toMutableMap().apply { put("sig", JsonPrimitive(identity.sign(payload))) })
    }

    private fun describe(envelope: JsonObject): String {
        val intent = (envelope["intent"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val params = envelope["params"]?.toString().orEmpty()
        val level = (envelope["level"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return "动作：$intent（L$level）\n参数：${params.take(400)}"
    }

    private fun rejected(id: String, code: String, detail: String): JsonObject = buildJsonObject {
        put("v", 1)
        put("envelope_id", id)
        put("device_id", config.device_id)
        put("status", "rejected")
        put("ts", timestamp())
        putJsonObject("error") {
            put("code", code)
            put("detail", detail.take(500))
        }
    }

    private fun writeReceipt(id: String, receipt: JsonObject) {
        runCatching { File(paths.outbox, "$id.json").writeText(receipt.toString(), Charsets.UTF_8) }
    }

    private fun moveTo(file: File, dir: File) {
        runCatching {
            val target = File(dir, file.name)
            if (target.exists()) target.delete()
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }
    }

    private fun listJsonFiles(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun status(receipt: JsonObject): String =
        (receipt["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown"

    private fun timestamp(): String = FORMAT.format(Instant.now())

    private fun nonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
