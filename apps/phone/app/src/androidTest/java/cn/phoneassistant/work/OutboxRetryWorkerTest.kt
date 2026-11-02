package cn.phoneassistant.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import cn.phoneassistant.core.Outbox
import cn.phoneassistant.data.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 SettingsStore + 真实 HTTP，直接跑 worker 的那一次补发；
 * 同时确认 OutboxScheduler 确实把唯一任务交给了 WorkManager。
 * 不等待系统 JobScheduler 真正执行（模拟器无外网时会被系统延后）。
 */
@RunWith(AndroidJUnit4::class)
class OutboxRetryWorkerTest {

    @Test
    fun scheduledWorkIsRegisteredAndPassSendsReceipt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val settings = SettingsStore(context)
        val oldUrl = settings.hubUrl
        val oldToken = settings.hubToken
        val oldPubkey = settings.hubPubkey
        try {
            settings.hubUrl = "http://127.0.0.1:${server.port}"
            settings.hubToken = "test-token"
            settings.hubPubkey =
                """{"kty":"EC","crv":"P-256","x":"${"A".repeat(43)}","y":"${"B".repeat(43)}"}"""

            val outbox = Outbox(File(context.filesDir, "outbox.json"))
            outbox.list().forEach { outbox.markSent(it.key) }
            val payload = buildJsonObject {
                put("envelope_id", "instrumentation-1")
                put("status", "done")
            }
            outbox.enqueue("receipt:instrumentation-1", "receipt", payload)

            OutboxScheduler.ensureScheduled(context)
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(OutboxScheduler.WORK_NAME)
                .get(10, TimeUnit.SECONDS)
            assertTrue("没有登记 outbox-retry 任务", infos.isNotEmpty())
            assertTrue(infos.any { !it.state.isFinished })

            assertEquals(OutboxPassResult.SUCCESS, runOutboxPass(context))
            assertEquals(0, outbox.size())

            val request = server.takeRequest(10, TimeUnit.SECONDS)
            assertTrue("hub 没收到回执", request != null)
            assertEquals("/v1/device/receipts", request!!.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("instrumentation-1"))
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork(OutboxScheduler.WORK_NAME)
            settings.hubUrl = oldUrl
            settings.hubToken = oldToken
            settings.hubPubkey = oldPubkey
            server.shutdown()
        }
    }
}
