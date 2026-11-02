package cn.phoneassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验器的单元测试：合法意图放行，非法意图一律拒绝。 */
class ModelIntentParserTest {

    @Test
    fun validSetAlarmIsAccepted() {
        val result = ModelIntentParser.parse(
            """{"type":"SET_ALARM","params":{"hour":7,"minute":30,"label":"起床"},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Valid)
        val intent = (result as ValidationResult.Valid).intent
        assertEquals(IntentType.SET_ALARM, intent.type)
        assertEquals("7", intent.params["hour"])
        assertEquals("30", intent.params["minute"])
        assertEquals("起床", intent.params["label"])
    }

    @Test
    fun validCalendarEventComputesBeginAndEndMillis() {
        val result = ModelIntentParser.parse(
            """{"type":"CREATE_CALENDAR_EVENT","params":{"title":"开会","date":"2026-11-03","startTime":"09:00","durationMinutes":60},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Valid)
        val params = (result as ValidationResult.Valid).intent.params
        assertTrue(params["beginMillis"]!!.toLong() < params["endMillis"]!!.toLong())
        assertEquals(3_600_000L, params["endMillis"]!!.toLong() - params["beginMillis"]!!.toLong())
    }

    @Test
    fun validChatOnlyIsAccepted() {
        val result = ModelIntentParser.parse(
            """{"type":"CHAT_ONLY","params":{},"reply":"你好"}""",
        )
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun unknownTypeIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"DELETE_FILE","params":{"path":"/tmp/x"},"reply":"删文件"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun missingParamIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"SET_ALARM","params":{"hour":7},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun outOfRangeHourIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"SET_ALARM","params":{"hour":25,"minute":0},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun unknownParamKeyIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"WEB_SEARCH","params":{"query":"天气","extra":"x"},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun emptyQueryIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"WEB_SEARCH","params":{"query":"   "},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun nonJsonIsRejected() {
        val result = ModelIntentParser.parse("帮我设个闹钟")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun codeFenceWrappedJsonIsAccepted() {
        val result = ModelIntentParser.parse(
            "```json\n{\"type\":\"SET_TIMER\",\"params\":{\"seconds\":300},\"reply\":\"好的\"}\n```",
        )
        assertTrue(result is ValidationResult.Valid)
        assertEquals("300", (result as ValidationResult.Valid).intent.params["seconds"])
    }

    @Test
    fun badDateFormatIsRejected() {
        val result = ModelIntentParser.parse(
            """{"type":"CREATE_CALENDAR_EVENT","params":{"title":"开会","date":"11月3日","startTime":"09:00"},"reply":"好的"}""",
        )
        assertTrue(result is ValidationResult.Invalid)
    }
}
