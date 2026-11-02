package cn.phoneassistant.model

import cn.phoneassistant.core.AssistantIntent
import kotlinx.serialization.Serializable

/** 聊天列表中的一条消息。 */
@Serializable
data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    /** 为 true 时以失败样式（ muted 红色文字）展示。 */
    val isError: Boolean = false,
)

/** 正在等待用户确认的意图卡片。 */
data class PendingIntent(
    val intent: AssistantIntent,
    /** OPEN_APP 的候选应用（包名 -> 显示名称），非 OPEN_APP 时为空。 */
    val appCandidates: List<AppCandidate> = emptyList(),
    /** 用户在候选列表中选中的包名；无候选或未选择时为空。 */
    val selectedPackage: String? = null,
)

/** 一个可启动应用的候选：显示名称供用户确认，包名供启动使用。 */
data class AppCandidate(
    val packageName: String,
    val label: String,
)
