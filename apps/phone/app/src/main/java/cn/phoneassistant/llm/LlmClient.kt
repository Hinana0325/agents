package cn.phoneassistant.llm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OpenAI 兼容接口的最小客户端：只用 HttpURLConnection，不引入第三方网络库。
 *
 * 安全要点：
 * - 密钥只出现在本次请求的 Authorization 请求头中，绝不写入日志；
 * - 只请求用户在设置中填写的地址，不请求任何其他域名；
 * - 超时 30 秒；非 200 响应、空回复都转为中文错误，不会把原始报错原样当作意图执行。
 */
class LlmClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 调用一次聊天补全，返回模型回复的文本内容。失败时抛 [LlmException]（中文原因）。 */
    fun chat(userText: String): String {
        val endpoint = "$baseUrl/chat/completions"
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessageDto(role = "system", content = SYSTEM_PROMPT),
                ChatMessageDto(role = "user", content = userText),
            ),
            temperature = 0.2,
        )
        val body = json.encodeToString(request)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                .use { it.readText() }
            if (status !in 200..299) {
                throw LlmException("服务返回了错误（HTTP $status），请检查设置中的地址和模型名是否正确。")
            }
            val response = try {
                json.decodeFromString<ChatCompletionResponse>(responseText)
            } catch (e: Exception) {
                throw LlmException("服务返回的内容无法解析，请稍后再试。")
            }
            val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (content.isEmpty()) {
                throw LlmException("服务返回了空回复，请稍后再试。")
            }
            return content
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("网络请求失败：${e.message ?: "请检查网络后重试。"}")
        } finally {
            connection.disconnect()
        }
    }

    /** 连接测试：只请求 /models，不消耗对话额度；成功返回中文说明，失败抛 [LlmException]。 */
    fun checkConnection(): String {
        val endpoint = "$baseUrl/models"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val status = connection.responseCode
            if (status in 200..299) return "连接成功（HTTP $status）。"
            throw LlmException(
                when (status) {
                    401, 403 -> "密钥被拒绝（HTTP $status），请检查密钥。"
                    404 -> "服务没有 /models 接口（HTTP 404），请确认地址以 /v1 结尾。"
                    else -> "服务返回了 HTTP $status，请检查地址与模型名。"
                },
            )
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("网络请求失败：${e.message ?: "请检查网络后重试。"}")
        } finally {
            connection.disconnect()
        }
    }

    class LlmException(message: String) : Exception(message)

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessageDto>,
        val temperature: Double,
    )

    @Serializable
    private data class ChatMessageDto(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatCompletionResponse(
        val choices: List<ChoiceDto> = emptyList(),
    )

    @Serializable
    private data class ChoiceDto(
        val message: ChatMessageDto? = null,
    )
}
