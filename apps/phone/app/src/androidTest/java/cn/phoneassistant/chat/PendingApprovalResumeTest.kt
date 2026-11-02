package cn.phoneassistant.chat

import android.app.Application
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.phoneassistant.core.Es256
import cn.phoneassistant.core.Outbox
import cn.phoneassistant.core.PendingApproval
import cn.phoneassistant.core.PendingApprovalStore
import cn.phoneassistant.data.DeviceIdentity
import cn.phoneassistant.data.SettingsStore
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 进程在确认框上被杀掉后，重启应恢复待确认信封并完成审批/回执。 */
@RunWith(AndroidJUnit4::class)
class PendingApprovalResumeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val field = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    @Test
    fun pendingApprovalIsResumedAfterRestart() {
        val app = context.applicationContext as Application
        val settings = SettingsStore(app)
        val oldUrl = settings.hubUrl
        val oldToken = settings.hubToken
        val oldPubkey = settings.hubPubkey
        val oldKid = settings.hubKid
        val oldCatalog = settings.appCatalogJson
        val pendingFile = File(context.filesDir, "pending_approvals.json")
        val outbox = Outbox(File(context.filesDir, "outbox.json"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            outbox.list().forEach { outbox.markSent(it.key) }
            pendingFile.delete()

            val hub = keyPair()
            val hubJwk = jwk(hub)
            val identity = DeviceIdentity(context)
            val envelope = signedEnvelope(hub, identity.deviceId, intent = "save_note")
            val id = (envelope["id"] as JsonPrimitive).content

            settings.hubUrl = "http://127.0.0.1:${server.port}"
            settings.hubToken = "test-token"
            settings.hubPubkey = hubJwk.toString()
            settings.hubKid = "hub-test-1"
            settings.appCatalogJson = ""

            PendingApprovalStore(pendingFile).put(
                PendingApproval(id, 2, envelope, System.currentTimeMillis()),
            )

            val vm = ChatViewModel(app)
            val approval = await(10_000) { vm.remoteApproval.value }
            assertNotNull("重启后没有恢复确认框", approval)
            assertEquals(2, approval!!.level)
            assertEquals("save_note", approval.intent)

            vm.answerRemote(true)
            assertTrue(
                "待确认文件没有被清空",
                awaitTrue(10_000) { PendingApprovalStore(pendingFile).size() == 0 },
            )

            val request = server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("没有回传回执", request)
            assertEquals("/v1/device/receipts", request!!.path)
            assertTrue(request.body.readUtf8().contains("\"status\":\"done\""))
        } finally {
            settings.hubUrl = oldUrl
            settings.hubToken = oldToken
            settings.hubPubkey = oldPubkey
            settings.hubKid = oldKid
            settings.appCatalogJson = oldCatalog
            pendingFile.delete()
            server.shutdown()
        }
    }

    private fun signedEnvelope(hub: KeyPair, deviceId: String, intent: String): JsonObject {
        val now = Instant.now()
        val claims = buildJsonObject {
            put("v", 1)
            put("id", uuid7())
            put("ts", field.format(now))
            put("ttl_s", 300)
            putJsonObject("from") {
                put("role", "hub")
                put("kid", "hub-test-1")
            }
            putJsonObject("to") { put("device", deviceId) }
            put("intent", intent)
            putJsonObject("params") {
                put("text", "恢复测试")
                put("title", "恢复测试")
            }
            putJsonObject("origin") {
                put("kind", "user_text")
                put("tainted", false)
            }
            put("level", 2)
        }
        return JsonObject(
            claims.toMutableMap().apply {
                put("sig", JsonPrimitive(Es256.signRaw(claims, hub.private)))
            },
        )
    }

    private fun uuid7(): String =
        String.format(
            "0193f0a8-9c1b-7%03x-8%03x-%012x",
            (System.nanoTime() and 0xFFF), (System.nanoTime() and 0xFFF), System.nanoTime() and 0xFFFFFFFFFFFF,
        )

    private fun <T> await(timeoutMillis: Long, block: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun awaitTrue(timeoutMillis: Long, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (block()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

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
}
