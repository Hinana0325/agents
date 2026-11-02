package cn.phoneassistant.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cn.phoneassistant.core.QrCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** 把配对请求渲染成二维码（桌面展示 / 导出 PNG）。 */
object PairingQr {

    fun toImage(text: String, size: Int = 480): BufferedImage {
        val qr = QrCode.encode(text, size)
        val image = BufferedImage(qr.width, qr.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, qr.width, qr.height, qr.pixels, 0, qr.width)
        return image
    }

    fun writePng(text: String, file: File, size: Int = 480) {
        file.parentFile?.mkdirs()
        ImageIO.write(toImage(text, size), "png", file)
    }

    fun toComposeBitmap(text: String, size: Int = 480): ImageBitmap =
        org.jetbrains.skia.Image.makeFromEncoded(pngBytes(text, size)).toComposeImageBitmap()

    private fun pngBytes(text: String, size: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(toImage(text, size), "png", it) }.toByteArray()
}
