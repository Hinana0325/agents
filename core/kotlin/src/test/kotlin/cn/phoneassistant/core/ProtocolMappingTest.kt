package cn.phoneassistant.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMappingTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun chatKeepsTextAsReply() {
        val intent = ProtocolMapping.toAssistantIntent(
            "chat",
            buildJsonObject { put("text", "你好") },
            zone,
        ).getOrThrow()

        assertEquals(IntentType.CHAT_ONLY, intent.type)
        assertEquals("你好", intent.reply)
    }

    @Test
    fun saveNoteRenamesTextToContent() {
        val intent = ProtocolMapping.toAssistantIntent(
            "save_note",
            buildJsonObject {
                put("text", "买牛奶")
                put("title", "购物")
            },
            zone,
        ).getOrThrow()

        assertEquals(IntentType.SAVE_NOTE, intent.type)
        assertEquals("买牛奶", intent.params["content"])
        assertEquals("购物", intent.params["title"])
    }

    @Test
    fun createEventComputesDisplayFields() {
        val begin = 1762081200000L
        val end = begin + 45 * 60_000L
        val intent = ProtocolMapping.toAssistantIntent(
            "create_event",
            buildJsonObject {
                put("title", "评审")
                put("begin_ms", begin)
                put("end_ms", end)
            },
            zone,
        ).getOrThrow()

        assertEquals(IntentType.CREATE_CALENDAR_EVENT, intent.type)
        val beginZoned = Instant.ofEpochMilli(begin).atZone(zone)
        assertEquals(beginZoned.toLocalDate().toString(), intent.params["date"])
        assertEquals(beginZoned.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), intent.params["startTime"])
        assertEquals("45", intent.params["durationMinutes"])
        assertEquals(begin.toString(), intent.params["beginMillis"])
        assertEquals(end.toString(), intent.params["endMillis"])
    }

    @Test
    fun openAppUsesAppIdAsName() {
        val intent = ProtocolMapping.toAssistantIntent(
            "open_app",
            buildJsonObject { put("app_id", "com.example.music") },
            zone,
        ).getOrThrow()

        assertEquals(IntentType.OPEN_APP, intent.type)
        assertEquals("com.example.music", intent.params["appName"])
    }

    @Test
    fun voiceCommandIsNotExecutable() {
        val result = ProtocolMapping.toAssistantIntent(
            "voice_command",
            buildJsonObject { put("transcript", "设个闹钟") },
            zone,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun endBeforeBeginIsRejected() {
        val result = ProtocolMapping.toAssistantIntent(
            "create_event",
            buildJsonObject {
                put("title", "x")
                put("begin_ms", 100)
                put("end_ms", 100)
            },
            zone,
        )
        assertTrue(result.isFailure)
    }
}
