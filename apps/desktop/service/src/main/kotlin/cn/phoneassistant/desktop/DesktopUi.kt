package cn.phoneassistant.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Compose Desktop 入口：主窗口 + 系统托盘。服务线程在 Main 里独立运行。 */
fun runDesktopUi(
    state: UiState,
    config: DesktopConfigFile,
    paths: DesktopPaths,
    store: DesktopStore,
    identity: DesktopIdentity,
) = application {
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
                DesktopApp(state, config, paths, store, identity)
            }
        }
    }
}

@Composable
private fun DesktopApp(
    state: UiState,
    config: DesktopConfigFile,
    paths: DesktopPaths,
    store: DesktopStore,
    identity: DesktopIdentity,
) {
    val logs by state.logs.collectAsState()
    val prompt by state.prompt.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var processed by remember { mutableStateOf<List<DesktopStore.ProcessedRow>>(emptyList()) }

    // 每次有新日志就刷新一次已处理记录（量很小，直接读库）
    LaunchedEffect(logs) {
        processed = runCatching { store.recent(80) }.getOrDefault(emptyList())
    }

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
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("运行日志") })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("已处理（${processed.size}）") },
            )
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("设置") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("配对") })
        }
        if (tab == 3) {
            PairingPane(config, identity, modifier = Modifier.weight(1f).fillMaxWidth())
        } else if (tab == 2) {
            SettingsPane(config, paths, modifier = Modifier.weight(1f).fillMaxWidth())
        } else if (tab == 0) {
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
        } else {
            if (processed.isEmpty()) {
                Text(
                    "还没有处理记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(processed) { row ->
                        Text(
                            "${row.status}  ${row.envelopeId}  ${row.processedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                        )
                    }
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

@Composable
private fun PairingPane(config: DesktopConfigFile, identity: DesktopIdentity, modifier: Modifier = Modifier) {
    val pretty = remember { Json { prettyPrint = true } }
    val request = remember { Pairing.request(config, identity) }
    val requestText = remember { pretty.encodeToString(request) }
    val qr = remember { PairingQr.toComposeBitmap(Json.encodeToString(request), 420) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("配对", style = MaterialTheme.typography.titleMedium)
        Text(
            "信任根（手机公钥）：" + if (config.phone_pubkey == null) "未配置" else "已配置",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("设备证书：${Pairing.describeCertificate(config.device_cert)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "让手机打开「配对」页扫描下面的二维码，或在配对页粘贴请求 JSON：",
            style = MaterialTheme.typography.bodySmall,
        )
        Image(
            bitmap = qr,
            contentDescription = "配对二维码",
            modifier = Modifier.size(240.dp),
        )
        Text("请求 JSON", style = MaterialTheme.typography.bodySmall)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                requestText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(10.dp),
            )
        }
        Text(
            "手机签发后，把证书交给电脑：service.bat verify-cert <证书.json>",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "也可以导出 PNG：service.bat pair-qr <输出.png>",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsPane(config: DesktopConfigFile, paths: DesktopPaths, modifier: Modifier = Modifier) {
    val pretty = remember { Json { prettyPrint = true } }
    var catalog by remember { mutableStateOf(pretty.encodeToString(JsonObject.serializer(), config.app_catalog)) }
    var allowlist by remember { mutableStateOf(config.url_allowlist.joinToString("\n")) }
    var template by remember { mutableStateOf(config.web_search_template) }
    var dryRun by remember { mutableStateOf(config.dry_run) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设备：${config.device_id}（role=${config.role}）", style = MaterialTheme.typography.bodyMedium)
        Text(
            "hub 公钥：" + if (config.hub_pubkey == null) {
                "未配置（不会接收信封）"
            } else {
                "已配置（kid=${config.hub_kid}）"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = dryRun, onCheckedChange = { dryRun = it })
            Text(
                "dry_run：只返回将要执行的命令，不真的开浏览器或起进程",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "应用目录（JSON：app_id → {\"url\": ...} 或 {\"command\": [...]}）",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = catalog,
            onValueChange = { catalog = it },
            modifier = Modifier.fillMaxWidth().height(170.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Text("URL 允许清单（每行一个 host）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = allowlist,
            onValueChange = { allowlist = it },
            modifier = Modifier.fillMaxWidth().height(90.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Text("搜索 URL 模板（必须含 {q}）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = template,
            onValueChange = { template = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            onClick = {
                ConfigEditor.validate(catalog, allowlist, template, dryRun, config).fold(
                    onSuccess = { updated ->
                        runCatching { DesktopConfig.save(paths, updated) }.fold(
                            onSuccess = {
                                isError = false
                                message = "已保存，重启服务后生效。"
                            },
                            onFailure = {
                                isError = true
                                message = "写入失败：${it.message}"
                            },
                        )
                    },
                    onFailure = {
                        isError = true
                        message = it.message ?: "校验失败"
                    },
                )
            },
        ) {
            Text("保存设置")
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "应用目录用于 open_app 的 app_id 映射；保存后需重启服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
