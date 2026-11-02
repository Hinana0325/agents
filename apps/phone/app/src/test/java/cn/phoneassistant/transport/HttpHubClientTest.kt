package cn.phoneassistant.transport

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHubClientTest {

    @Test
    fun pullParsesEnvelopesAndCursor() {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"envelopes":[{"id":"a"},{"id":"b"}],"cursor":"c1"}"""),
            )
            start()
        }
        try {
            val client = HttpHubClient(server.url("/").toString(), "phone-1", "token-1")

            val result = runBlocking { client.pull() }

            assertEquals(2, result.envelopes.size)
            assertEquals("c1", result.cursor)
            val recorded = server.takeRequest()
            assertEquals("/v1/device/inbox?device_id=phone-1", recorded.path)
            assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pullRejectsHttpError() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(503))
            start()
        }
        try {
            val client = HttpHubClient(server.url("/").toString(), "phone-1", "token-1")
            val error = assertThrows(HubException::class.java) {
                runBlocking { client.pull() }
            }
            assertTrue(error.message!!.contains("503"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun postReceiptSendsJsonWithAuth() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(204))
            start()
        }
        try {
            val client = HttpHubClient(server.url("/").toString(), "phone-1", "token-1")

            runBlocking { client.postReceipt(buildJsonObject { put("status", "done") }) }

            val recorded = server.takeRequest()
            assertEquals("/v1/device/receipts", recorded.path)
            assertEquals("POST", recorded.method)
            assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
            assertTrue(recorded.body.readUtf8().contains("\"status\":\"done\""))
        } finally {
            server.shutdown()
        }
    }
}
