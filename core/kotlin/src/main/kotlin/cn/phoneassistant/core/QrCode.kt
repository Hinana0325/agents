package cn.phoneassistant.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/** 黑白二维码位图（ARGB 像素），不把 ZXing 类型暴露给各端。 */
data class QrImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixels size mismatch" }
    }
}

/**
 * 配对二维码：纯 JVM/Android 通用的编码与解码，渲染成图片由各端自己做。
 * 线上内容仍是配对请求 JSON。
 */
object QrCode {

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun encode(text: String, size: Int = 480): QrImage {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return QrImage(matrix.width, matrix.height, pixels)
    }

    /** @param pixels ARGB int 数组，长度必须为 width*height。 */
    fun decodeLuminance(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return try {
            val source = RGBLuminanceSource(width, height, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: Exception) {
            null
        }
    }
}
