package cn.phoneassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTest {

    @Test
    fun encodeThenDecodeRoundTrips() {
        val text = """{"v":1,"device_id":"pc-1","role":"pc","max_level":2}"""
        val qr = QrCode.encode(text, 360)

        assertEquals(text, QrCode.decodeLuminance(qr.width, qr.height, qr.pixels))
    }

    @Test
    fun decodeGarbageReturnsNull() {
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertNull(QrCode.decodeLuminance(64, 64, pixels))
    }
}
