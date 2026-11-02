package cn.phoneassistant.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.Timer
import java.util.TimerTask
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** 托盘与确认弹窗。无 GUI 环境下 install() 返回 false，通知变成标准输出。 */
class TrayUI {

    private var trayIcon: TrayIcon? = null

    fun install(): Boolean {
        if (trayIcon != null) return true
        if (!SystemTray.isSupported()) return false
        return try {
            val icon = TrayIcon(iconImage(), "电脑助理")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        } catch (e: Exception) {
            false
        }
    }

    fun notify(title: String, body: String) {
        val icon = trayIcon
        if (icon != null) {
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
        } else {
            println("[通知] $title：$body")
        }
    }

    /** 阻塞等待用户在界面上点确认；默认按钮是“取消”，避免误触。 */
    fun confirm(title: String, body: String): Boolean {
        var approved = false
        val task = Runnable {
            val options = arrayOf("确认执行", "取消")
            val choice = JOptionPane.showOptionDialog(
                null,
                body,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1],
            )
            approved = choice == 0
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        return approved
    }

    private fun iconImage(): BufferedImage {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x3F, 0x47, 0x56)
        g.fillOval(2, 2, size - 4, size - 4)
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.5f)
        g.drawLine(10, 16, 22, 16)
        g.drawLine(16, 10, 16, 22)
        g.dispose()
        return image
    }
}

/** 进程内计时器；到点用托盘通知。进程退出即失效（与协议里“即时计时”定位一致）。 */
class TimerService(private val tray: TrayUI) {

    private val timer = Timer("dpa-timers", true)

    fun schedule(seconds: Int, label: String) {
        timer.schedule(
            object : TimerTask() {
                override fun run() = tray.notify("计时结束", label)
            },
            seconds * 1000L,
        )
    }
}
