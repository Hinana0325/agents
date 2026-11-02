package cn.phoneassistant.data

import android.content.Context
import cn.phoneassistant.model.ChatMessage
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 对话历史的本地存档：只写应用私有目录，不上传、不分享、卸载即清除。
 * 只保留最近 [MAX_MESSAGES] 条，避免文件无限增长。
 */
object ChatHistoryStore {

    private const val FILE_NAME = "chat_history.json"
    private const val MAX_MESSAGES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class HistoryFile(val version: Int = 1, val messages: List<ChatMessage>)

    fun load(context: Context): List<ChatMessage> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) emptyList() else json.decodeFromString<HistoryFile>(file.readText()).messages
    }.getOrDefault(emptyList())

    fun save(context: Context, messages: List<ChatMessage>) {
        runCatching {
            val tail = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
            File(context.filesDir, FILE_NAME)
                .writeText(json.encodeToString(HistoryFile(messages = tail)), Charsets.UTF_8)
        }
    }
}
