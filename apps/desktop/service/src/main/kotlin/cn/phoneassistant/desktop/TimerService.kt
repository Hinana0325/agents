package cn.phoneassistant.desktop

import java.util.Timer
import java.util.TimerTask

/** 进程内计时器；到点用通知出口提醒。进程退出即失效（与协议里“即时计时”定位一致）。 */
class TimerService(private val notifier: Notifier) {

    private val timer = Timer("dpa-timers", true)

    fun schedule(seconds: Int, label: String) {
        timer.schedule(
            object : TimerTask() {
                override fun run() = notifier.notify("计时结束", label)
            },
            seconds * 1000L,
        )
    }
}
