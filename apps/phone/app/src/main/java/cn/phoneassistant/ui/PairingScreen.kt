package cn.phoneassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.phoneassistant.core.PairingRequests
import cn.phoneassistant.data.DeviceIdentity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 配对页：展示本机身份、粘贴电脑的配对请求、用 Keystore 密钥签发设备证书。
 * 手机是信任根，签发前请核对电脑显示的 device_id 与角色。
 */
@Composable
fun PairingScreen(identity: DeviceIdentity, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pretty = remember { Json { prettyPrint = true } }
    val publicKeyText = remember { pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), identity.publicJwk()) }

    var requestText by remember { mutableStateOf("") }
    var certText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    // 把本机公钥留一份在应用私有目录，便于排障/分享；不含私钥。
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "pairing").apply { mkdirs() }
                File(dir, "device-public.json").writeText(publicKeyText, Charsets.UTF_8)
            }
        }
    }

    fun signRequest() {
        PairingRequests.parse(requestText).fold(
            onSuccess = { request ->
                val cert = identity.createCertificate(request)
                certText = pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), cert)
                isError = false
                message = "已为 ${request.deviceId}（${request.role}，最高 L${request.maxLevel}）签发证书。"
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.filesDir, "pairing").apply { mkdirs() }
                        File(dir, "last-cert.json").writeText(certText, Charsets.UTF_8)
                    }
                }
            },
            onFailure = {
                certText = ""
                isError = true
                message = it.message ?: "配对请求无效"
            },
        )
    }

    fun runSelfTest() {
        val error = identity.selfTest()
        isError = error != null
        message = if (error == null) "签名自检通过：Keystore 签名与验签一致。" else "自检失败：$error"
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设备身份", style = MaterialTheme.typography.titleMedium)
        Text("设备 id：${identity.deviceId}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "手机是信任根。先把下面的公钥交给电脑（trust-phone），再让电脑输出配对请求。",
            style = MaterialTheme.typography.bodySmall,
            color = MutedGrey,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = ShapeCard) {
            Text(
                publicKeyText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp),
            )
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(publicKeyText)) }) {
            Text("复制公钥")
        }

        HorizontalDivider()
        Text("配对请求", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = requestText,
            onValueChange = { requestText = it },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            placeholder = { Text("把电脑执行 pair 得到的 JSON 粘贴到这里") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { signRequest() }) { Text("签发设备证书") }
            OutlinedButton(onClick = { runSelfTest() }) { Text("签名自检") }
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) DangerRed else SuccessGreen,
            )
        }

        if (certText.isNotEmpty()) {
            Text("设备证书", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = certText,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().height(190.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(certText)) }) {
                Text("复制证书")
            }
            Text(
                "把证书交给电脑执行 verify-cert。证书有效期一年，到期需重新配对。",
                style = MaterialTheme.typography.bodySmall,
                color = MutedGrey,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
