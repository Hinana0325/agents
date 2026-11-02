package cn.phoneassistant.chat

import android.app.Application
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 未配置大模型时，真 ViewModel 应走本地规则并生成确认卡片，不碰网络。 */
@RunWith(AndroidJUnit4::class)
class OfflineFallbackTest {

    @Test
    fun timerBecomesPendingIntentWithoutModel() {
        withUnconfiguredModel { vm ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.send("10分钟后提醒我") }
            val pending = awaitPending(vm)
            assertNotNull("没有生成本地意图", pending)
            assertEquals(IntentType.SET_TIMER, pending!!.intent.type)
            assertEquals("600", pending.intent.params["seconds"])
        }
    }

    @Test
    fun noteBecomesPendingIntentWithoutModel() {
        withUnconfiguredModel { vm ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.send("记一下：离线备忘") }
            val pending = awaitPending(vm)
            assertNotNull("没有生成本地意图", pending)
            assertEquals(IntentType.SAVE_NOTE, pending!!.intent.type)
            assertEquals("离线备忘", pending.intent.params["content"])
        }
    }

    private fun withUnconfiguredModel(block: (ChatViewModel) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as Application
        val settings = SettingsStore(app)
        val oldBase = settings.baseUrl
        val oldModel = settings.model
        val oldKey = settings.apiKey
        settings.baseUrl = ""
        settings.model = ""
        settings.apiKey = ""
        try {
            block(ChatViewModel(app))
        } finally {
            settings.baseUrl = oldBase
            settings.model = oldModel
            settings.apiKey = oldKey
        }
    }

    private fun awaitPending(vm: ChatViewModel): cn.phoneassistant.model.PendingIntent? {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.pending.value == null && System.currentTimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        return vm.pending.value
    }
}
