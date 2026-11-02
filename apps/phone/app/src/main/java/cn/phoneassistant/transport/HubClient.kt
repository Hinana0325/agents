package cn.phoneassistant.transport

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class PullResult(val envelopes: List<JsonObject>, val cursor: String? = null)

/** 设备到云端的传输抽象；WS 版本以后替换实现，调用方不变。 */
interface HubClient {
    suspend fun pull(): PullResult
    suspend fun postReceipt(receipt: JsonObject)
}

class HubException(message: String) : Exception(message)

/**
 * HTTPS 拉取式客户端（ADR 0003：唤醒后拉取，不做常驻推送）。
 * `GET  {base}/v1/device/inbox?device_id=…` → {"envelopes":[…],"cursor":"…"}
 * `POST {base}/v1/device/receipts`         → 回执
 * 设备令牌只放在 Authorization 头里，不写日志。
 */
class HttpHubClient(
    private val baseUrl: String,
    private val deviceId: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
) : HubClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun pull(): PullResult = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') +
            "/v1/device/inbox?device_id=" + URLEncoder.encode(deviceId, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HubException("收取失败：HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw HubException("收取失败：响应不是 JSON")
            val envelopes = (root["envelopes"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val cursor = (root["cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            PullResult(envelopes, cursor)
        }
    }

    override suspend fun postReceipt(receipt: JsonObject): Unit = withContext(Dispatchers.IO) {
        val body = receipt.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/device/receipts")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HubException("回执发送失败：HTTP ${response.code}")
        }
    }
}
