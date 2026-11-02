package cn.phoneassistant.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.launch

/** Compose Desktop 入口：主窗口 + 系统托盘。服务线程在 Main 里独立运行。 */
fun runDesktopUi(state: UiState, config: DesktopConfigFile, paths: DesktopPaths) = application {
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()
    val windowState = rememberWindowState(width = 780.dp, height = 580.dp)
    var windowVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        state.trayNotifier = Notifier { title, body ->
            scope.launch { trayState.sendNotification(Notification(title, body)) }
        }
    }

    Tray(
        icon = remember { trayPainter() },
        state = trayState,
        tooltip = "电脑助理",
        onAction = { windowVisible = true },
        menu = {
            Item("显示主界面") { windowVisible = true }
            Separator()
            Item("退出") { exitApplication() }
        },
    )

    Window(
        onCloseRequest = { windowVisible = false },
        state = windowState,
        visible = windowVisible,
        title = "电脑助理",
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DesktopApp(state, config, paths)
            }
        }
    }
}

@Composable
private fun DesktopApp(state: UiState, config: DesktopConfigFile, paths: DesktopPaths) {
    val logs by state.logs.collectAsState()
    val prompt by state.prompt.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("电脑助理", style = MaterialTheme.typography.headlineSmall)
        Text("设备：${config.device_id}（role=${config.role}）", style = MaterialTheme.typography.bodyMedium)
        Text(
            "hub 公钥：" + if (config.hub_pubkey == null) {
                "未配置（不会接收信封）"
            } else {
                "已配置（kid=${config.hub_kid}）"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "inbox ${count(paths.inbox)} · pending ${count(paths.pending)} · outbox ${count(paths.outbox)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openDataDir(paths.home) }) { Text("打开数据目录") }
        }
        HorizontalDivider()
        Text("运行日志", style = MaterialTheme.typography.titleMedium)
        if (logs.isEmpty()) {
            Text(
                "还没有收到信封。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(logs.asReversed()) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "L1 自动执行；L2 在这里确认后由本机审批密钥签名；L3 需要手机批准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    prompt?.let { request ->
        AlertDialog(
            onDismissRequest = { state.answer(false) },
            title = { Text(request.title) },
            text = { Text(request.body) },
            confirmButton = {
                Button(onClick = { state.answer(true) }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { state.answer(false) }) { Text("取消") }
            },
        )
    }
}

private fun count(dir: File): Int =
    dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.size ?: 0

private fun openDataDir(dir: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(dir)
        }
    }
}

/** 托盘图标在运行时画出来，省掉一份图片资源。 */
private fun trayPainter(): Painter {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x3F, 0x47, 0x56)
    g.fillOval(2, 2, size - 4, size - 4)
    g.color = Color.WHITE
    g.stroke = BasicStroke(2.5f)
    g.drawLine(10, 16, 22, 16)
    g.drawLine(16, 10, 16, 22)
    g.dispose()
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    return BitmapPainter(bitmap)
}
