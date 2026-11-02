package cn.phoneassistant.model

/**
 * v0.1 唯一允许的意图类型。任何不在此枚举中的类型都会被
 * [cn.phoneassistant.intent.IntentValidator] 拒绝，绝不会进入执行器。
 */
enum class IntentType {
    OPEN_APP,
    SET_ALARM,
    CREATE_CALENDAR_EVENT,
    SET_TIMER,
    WEB_SEARCH,
    SAVE_NOTE,
    CHAT_ONLY,
}

/**
 * 一条已经过校验的结构化意图。
 *
 * @property type 意图类型。
 * @property params 已经校验过的参数；键名与取值含义见 IntentValidator 的文档。
 * @property reply 模型给出的简短中文说明，可为空；仅用于展示，不参与执行。
 */
data class AssistantIntent(
    val type: IntentType,
    val params: Map<String, String>,
    val reply: String?,
)

/**
 * 校验结果：合法时携带意图，不合法时携带中文原因。
 * 不合法的原因会原样展示给用户，执行器永远收不到它。
 */
sealed class ValidationResult {
    data class Valid(val intent: AssistantIntent) : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}
