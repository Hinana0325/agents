package cn.phoneassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIntentParserTest {

    private fun parse(text: String): AssistantIntent? = LocalIntentParser.parse(text)

    @Test
    fun digitDurationBecomesTimer() {
        val intent = parse("10分钟后提醒我")!!
        assertEquals(IntentType.SET_TIMER, intent.type)
        assertEquals("600", intent.params["seconds"])
    }

    @Test
    fun chineseDurationBecomesTimer() {
        val intent = parse("定个五分钟计时器")!!
        assertEquals(IntentType.SET_TIMER, intent.type)
        assertEquals("300", intent.params["seconds"])
    }

    @Test
    fun compoundDurationAccumulates() {
        val intent = parse("1小时30分钟倒计时")!!
        assertEquals("5400", intent.params["seconds"])
    }

    @Test
    fun halfHourDurationWorks() {
        val intent = parse("半小时后提醒我")!!
        assertEquals("1800", intent.params["seconds"])
    }

    @Test
    fun durationOutOfRangeIsRejected() {
        assertNull(parse("25小时计时"))
    }

    @Test
    fun durationWithoutCueIsNotATimer() {
        assertNull(parse("我跑了30分钟"))
    }

    @Test
    fun colonAlarmParses() {
        val intent = parse("设个7:30的闹钟")!!
        assertEquals(IntentType.SET_ALARM, intent.type)
        assertEquals("7", intent.params["hour"])
        assertEquals("30", intent.params["minute"])
    }

    @Test
    fun chineseAlarmParses() {
        val intent = parse("明天早上七点叫我起床")!!
        assertEquals(IntentType.SET_ALARM, intent.type)
        assertEquals("7", intent.params["hour"])
        assertEquals("0", intent.params["minute"])
        assertEquals("起床", intent.params["label"])
    }

    @Test
    fun halfAlarmParses() {
        val intent = parse("七点半的闹钟")!!
        assertEquals("7", intent.params["hour"])
        assertEquals("30", intent.params["minute"])
    }

    @Test
    fun afternoonAlarmUses24HourClock() {
        val intent = parse("下午3点提醒我")!!
        assertEquals("15", intent.params["hour"])
        assertEquals("0", intent.params["minute"])
    }

    @Test
    fun noteWithColon() {
        val intent = parse("记一下：买牛奶")!!
        assertEquals(IntentType.SAVE_NOTE, intent.type)
        assertEquals("买牛奶", intent.params["content"])
    }

    @Test
    fun noteWithSpaceAndNoColon() {
        val intent = parse("帮我记住 明天交报告")!!
        assertEquals(IntentType.SAVE_NOTE, intent.type)
        assertEquals("明天交报告", intent.params["content"])
    }

    @Test
    fun noteWinsOverSearchKeyword() {
        val intent = parse("记一下：搜索怎么用")!!
        assertEquals(IntentType.SAVE_NOTE, intent.type)
        assertEquals("搜索怎么用", intent.params["content"])
    }

    @Test
    fun openAppParses() {
        val intent = parse("打开微信")!!
        assertEquals(IntentType.OPEN_APP, intent.type)
        assertEquals("微信", intent.params["appName"])
    }

    @Test
    fun openAppStripsPoliteSuffix() {
        val intent = parse("帮我打开一下相机吧")!!
        assertEquals("相机", intent.params["appName"])
    }

    @Test
    fun searchParses() {
        val intent = parse("搜索今天天气")!!
        assertEquals(IntentType.WEB_SEARCH, intent.type)
        assertEquals("今天天气", intent.params["query"])
    }

    @Test
    fun chatFallsThrough() {
        assertNull(parse("你好，今天怎么样"))
    }

    @Test
    fun emptyIsNull() {
        assertNull(parse("   "))
    }
}
