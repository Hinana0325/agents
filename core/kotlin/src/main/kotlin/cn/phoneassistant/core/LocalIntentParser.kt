package cn.phoneassistant.core

/**
 * 本地规则快路径：不联网也能把常见中文口令解析成白名单意图。
 * 只在未配置大模型或网络请求失败时使用；认不出来返回 null，交给正常流程。
 *
 * 产出仍走现有的确认卡片/执行器，规则本身不产生任何副作用。
 * 参数键名与取值范围与 [ModelIntentParser] 完全一致。
 */
object LocalIntentParser {

    private val NOTE = Regex(
        """^\s*(?:帮我)?(?:记一下|记下|记住|记个备忘|备忘|保存备忘)\s*[:：,，]?\s*(.+?)\s*$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DURATION = Regex("""(\d{1,5}|[零〇一二两三四五六七八九十百]+|半)\s*(?:个)?\s*(小时|钟头|分钟|分|秒钟|秒)""")
    private val CLOCK = Regex(
        """(?:^|[^\d])(\d{1,2}|[零〇一二两三四五六七八九十]+)\s*(?:点|[:：])\s*(\d{1,2}|[零〇一二两三四五六七八九十]+)?\s*(半)?""",
    )
    private val OPEN_APP = Regex(
        """^\s*(?:帮我|请|麻烦)?\s*(?:打开|启动|运行|开一下)\s*(?:一下)?\s*([^\s，。！？!?]{1,30}?)\s*(?:吧|呗|谢谢)?\s*[。！!]?\s*$""",
    )
    private val SEARCH = Regex("""^\s*(?:帮我|请)?\s*(?:搜索|搜一下|搜下|搜|查一下|查查|查询)\s*[:：]?\s*(.{1,500}?)\s*$""")
    private val TIMER_CUE = Regex("计时|倒计时|定时|提醒我|提醒一下|叫我")
    private val ALARM_CUE = Regex("闹钟|叫醒|叫我|起床|提醒")
    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    fun parse(text: String): AssistantIntent? {
        val input = text.trim()
        if (input.isEmpty()) return null

        parseNote(input)?.let { return it }

        val seconds = parseDurationSeconds(input)
        if (seconds != null && TIMER_CUE.containsMatchIn(input)) {
            if (seconds !in 1..86400) return null
            return AssistantIntent(IntentType.SET_TIMER, mapOf("seconds" to seconds.toString()), reply = null)
        }

        parseAlarm(input)?.let { return it }
        parseOpenApp(input)?.let { return it }
        parseSearch(input)?.let { return it }
        return null
    }

    private fun parseNote(input: String): AssistantIntent? {
        if (input.length > 5010) return null
        val match = NOTE.find(input) ?: return null
        val content = match.groupValues[1].trim()
        if (content.isEmpty() || content.length > 5000) return null
        return AssistantIntent(IntentType.SAVE_NOTE, mapOf("content" to content), reply = null)
    }

    private fun parseDurationSeconds(input: String): Int? {
        var total = 0
        var matched = false
        for (match in DURATION.findAll(input)) {
            matched = true
            val numberText = match.groupValues[1]
            val amount = if (numberText == "半") 0.5 else (parseNumber(numberText)?.toDouble() ?: return null)
            val unit = match.groupValues[2]
            val factor = when (unit) {
                "小时", "钟头" -> 3600.0
                "分钟", "分" -> 60.0
                else -> 1.0
            }
            total += (amount * factor).toInt()
        }
        return if (matched) total else null
    }

    private fun parseAlarm(input: String): AssistantIntent? {
        if (!ALARM_CUE.containsMatchIn(input)) return null
        val match = CLOCK.find(input) ?: return null
        var hour = parseNumber(match.groupValues[1]) ?: return null
        val minuteText = match.groupValues[2]
        var minute = if (minuteText.isBlank()) 0 else (parseNumber(minuteText) ?: return null)
        if (match.groupValues[3] == "半") minute = 30
        if (input.contains("下午") || input.contains("晚上") || input.contains("傍晚")) {
            if (hour in 1..11) hour += 12
        }
        if (input.contains("中午") && hour in 1..10) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null
        val label = when {
            input.contains("起床") -> "起床"
            input.contains("开会") -> "开会"
            else -> null
        }
        val params = buildMap {
            put("hour", hour.toString())
            put("minute", minute.toString())
            if (label != null) put("label", label)
        }
        return AssistantIntent(IntentType.SET_ALARM, params, reply = null)
    }

    private fun parseOpenApp(input: String): AssistantIntent? {
        val match = OPEN_APP.find(input) ?: return null
        val name = match.groupValues[1].trim()
        if (name.isEmpty() || name.length > 100) return null
        return AssistantIntent(IntentType.OPEN_APP, mapOf("appName" to name), reply = null)
    }

    private fun parseSearch(input: String): AssistantIntent? {
        val match = SEARCH.find(input) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty() || query.length > 500) return null
        return AssistantIntent(IntentType.WEB_SEARCH, mapOf("query" to query), reply = null)
    }

    /** 支持阿拉伯数字与常见中文数字（零到一百多），认不出返回 null。 */
    private fun parseNumber(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        var total = 0
        var current = 0
        var seen = false
        for (ch in text) {
            when {
                ch in DIGITS -> {
                    current = DIGITS.getValue(ch)
                    seen = true
                }
                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                    seen = true
                }
                ch == '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                    seen = true
                }
                else -> return null
            }
        }
        return if (seen) total + current else null
    }
}
