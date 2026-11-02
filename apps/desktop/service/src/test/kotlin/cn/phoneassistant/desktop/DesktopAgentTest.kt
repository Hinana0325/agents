package cn.phoneassistant.desktop

import cn.phoneassistant.core.Jcs
import cn.phoneassistant.core.runtime.ExecutionOutcome
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAgentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val hub = keyPair()
    private val stores = mutableListOf<DesktopStore>()

    @After
    fun closeStores() {
        stores.forEach { runCatching { it.close() } }
        stores.clear()
    }

    @Test
    fun noteIsExecutedAndReceiptWritten() {
        val paths = paths()
        val agent = agent(paths, dryRun = true)

        writeEnvelope(paths, signEnvelope(envelope("save_note", buildJsonObject {
            put("text", "买牛奶")
            put("title", "购物")
        })))

        val results = agent.processAll()

        assertTrue(results.single().startsWith("done"))
        assertEquals(1, notesCount(paths))
        val receipt = readReceipts(paths).single()
        assertEquals("done", receipt["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun levelTwoWaitsForConfirmationAndMovesToPending() {
        val paths = paths()
        val agent = agent(paths, dryRun = true)

        writeEnvelope(paths, signEnvelope(envelope("save_note", buildJsonObject {
            put("text", "需要确认的备忘")
        }, level = 2)))

        val results = agent.processAll()

        assertTrue(results.single().startsWith("AWAITING_CONFIRM"))
        assertEquals(1, pendingCount(paths))
        assertEquals(0, notesCount(paths))
        assertEquals("awaiting_confirm", readReceipts(paths).single()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun approvedLevelTwoEnvelopeExecutes() {
        val paths = paths()
        val agent = agent(paths, dryRun = true, confirm = { true })

        writeEnvelope(paths, signEnvelope(envelope("save_note", buildJsonObject {
            put("text", "确认后执行")
        }, level = 2)))

        val results = agent.processAll()

        assertTrue(results.single().startsWith("done"))
        assertEquals(1, notesCount(paths))
        assertEquals("done", readReceipts(paths).single()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectedLevelTwoEnvelopeIsNotExecuted() {
        val paths = paths()
        val agent = agent(paths, dryRun = true, confirm = { false })

        writeEnvelope(paths, signEnvelope(envelope("save_note", buildJsonObject {
            put("text", "不该执行")
        }, level = 2)))

        val results = agent.processAll()

        assertTrue(results.single().startsWith("rejected"))
        assertEquals(0, notesCount(paths))
        assertEquals("rejected", readReceipts(paths).single()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicateAcrossRestartIsNotExecutedAgain() {
        val paths = paths()
        val env = signEnvelope(envelope("save_note", buildJsonObject { put("text", "once") }))

        writeEnvelope(paths, env)
        agent(paths, dryRun = true).processAll()

        // 模拟进程重启：关掉数据库连接，重新建 agent
        stores.forEach { runCatching { it.close() } }
        stores.clear()

        writeEnvelope(paths, env)
        val results = agent(paths, dryRun = true).processAll()

        assertTrue(results.single().startsWith("DUPLICATE"))
        assertEquals(1, notesCount(paths))
    }

    @Test
    fun processedHistoryIsQueryable() {
        val paths = paths()
        writeEnvelope(paths, signEnvelope(envelope("save_note", buildJsonObject { put("text", "history") })))
        agent(paths, dryRun = true).processAll()

        val db = DesktopStore(File(paths.home, "desktop.db"))
        stores += db
        val rows = db.recent(10)

        assertEquals(1, rows.size)
        assertEquals("done", rows.first().status)
        assertEquals(1, db.count())
    }

    @Test
    fun envelopeForAnotherDeviceIsRejected() {
        val paths = paths()
        val agent = agent(paths, dryRun = true)
        val env = signEnvelope(envelope("save_note", buildJsonObject { put("text", "x") }))
        val wrongDevice = JsonObject(
            env.toMutableMap().apply { put("to", buildJsonObject { put("device", "someone-else") }) },
        )
        writeEnvelope(paths, wrongDevice)

        val results = agent.processAll()

        assertTrue(results.single().startsWith("TARGET_NOT_ALLOWED"))
        assertEquals("TARGET_NOT_ALLOWED", readReceipts(paths).single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, notesCount(paths))
    }

    @Test
    fun tamperedEnvelopeIsRejected() {
        val paths = paths()
        val agent = agent(paths, dryRun = true)
        val signed = signEnvelope(envelope("save_note", buildJsonObject { put("text", "original") }))
        val tampered = JsonObject(
            signed.toMutableMap().apply { put("params", buildJsonObject { put("text", "changed") }) },
        )
        writeEnvelope(paths, tampered)

        agent.processAll()

        assertEquals("BAD_SIG", readReceipts(paths).single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, notesCount(paths))
    }

    @Test
    fun duplicateDeliveryExecutesOnce() {
        val paths = paths()
        val agent = agent(paths, dryRun = true)
        val env = signEnvelope(envelope("save_note", buildJsonObject { put("text", "once") }))

        writeEnvelope(paths, env)
        agent.processAll()
        writeEnvelope(paths, env)
        agent.processAll()

        assertEquals(1, notesCount(paths))
    }

    @Test
    fun executorsReportUnsupportedIntents() {
        val paths = paths()
        val executors = DesktopExecutors(paths.notes, config(dryRun = true), TimerService(Notifier { _, _ -> }))

        val unsupported = executors.execute("create_event", buildJsonObject { put("title", "x") })
        assertFalse(unsupported.success)

        val unknownApp = executors.execute("open_app", buildJsonObject { put("app_id", "no.such.app") })
        assertFalse(unknownApp.success)

        val chat = executors.execute("chat", buildJsonObject { put("text", "hi") })
        assertTrue(chat.success)
    }

    // -------------------------------------------------------------- helpers

    private fun paths(): DesktopPaths {
        val home = temp.newFolder("desktop")
        return DesktopPaths(home).also { it.ensure() }
    }

    private fun config(dryRun: Boolean, deviceId: String = "pc-test"): DesktopConfigFile = DesktopConfigFile(
        device_id = deviceId,
        hub_pubkey = jwk(hub),
        hub_kid = "hub-test-1",
        dry_run = dryRun,
    )

    private fun agent(
        paths: DesktopPaths,
        dryRun: Boolean,
        confirm: ((ApprovalPrompt) -> Boolean)? = null,
    ): InboxAgent {
        val store = DesktopStore(File(paths.home, "desktop.db"))
        stores += store
        return InboxAgent(
            paths = paths,
            config = config(dryRun),
            identity = DesktopIdentity(paths.home),
            notifier = Notifier { _, _ -> },
            confirm = confirm,
            store = store,
        )
    }

    private fun envelope(intent: String, params: JsonObject, level: Int = 1): JsonObject = buildJsonObject {
        put("v", 1)
        put("id", uuid7())
        put("ts", timestamp())
        put("ttl_s", 300)
        putJsonObject("from") {
            put("role", "hub")
            put("kid", "hub-test-1")
        }
        putJsonObject("to") { put("device", "pc-test") }
        put("intent", intent)
        put("params", params)
        putJsonObject("origin") {
            put("kind", "user_text")
            put("tainted", false)
        }
        put("level", level)
        put("sig", "A".repeat(86))
    }

    private fun signEnvelope(envelope: JsonObject): JsonObject {
        val payload = JsonObject(envelope.filterKeys { it != "sig" })
        return JsonObject(envelope.toMutableMap().apply { put("sig", JsonPrimitive(sign(payload))) })
    }

    private fun writeEnvelope(paths: DesktopPaths, envelope: JsonObject) {
        val id = envelope["id"]!!.jsonPrimitive.content
        File(paths.inbox, "$id.json").writeText(envelope.toString(), Charsets.UTF_8)
    }

    private fun readReceipts(paths: DesktopPaths): List<JsonObject> =
        paths.outbox.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.map { kotlinx.serialization.json.Json.parseToJsonElement(it.readText()) as JsonObject }
            .orEmpty()

    private fun notesCount(paths: DesktopPaths): Int =
        paths.notes.listFiles()?.count { it.isFile } ?: 0

    private fun pendingCount(paths: DesktopPaths): Int =
        paths.pending.listFiles()?.count { it.isFile } ?: 0

    private fun sign(payload: JsonObject): String {
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(hub.private)
        signer.update(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun jwk(pair: KeyPair): JsonObject {
        val publicKey = pair.public as ECPublicKey
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", b64(publicKey.w.affineX))
            put("y", b64(publicKey.w.affineY))
        }
    }

    private fun b64(value: BigInteger): String {
        val bytes = value.toByteArray()
        val fixed = when {
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> bytes
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed)
    }

    private fun uuid7(): String {
        val raw = UUID.randomUUID().toString().toCharArray()
        raw[14] = '7'
        val variant = "89ab"[kotlin.random.Random.nextInt(4)]
        raw[19] = variant
        return String(raw)
    }

    private fun timestamp(): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
}
