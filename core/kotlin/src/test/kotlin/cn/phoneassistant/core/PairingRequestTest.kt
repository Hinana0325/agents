package cn.phoneassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequestTest {

    private val validJson = """
        {"v":1,"device_id":"pc-1","role":"pc","max_level":2,
         "pubkey":{"kty":"EC","crv":"P-256","x":"${"A".repeat(43)}","y":"${"B".repeat(43)}"}}
    """.trimIndent()

    @Test
    fun validRequestIsParsed() {
        val request = PairingRequests.parse(validJson).getOrThrow()
        assertEquals("pc-1", request.deviceId)
        assertEquals("pc", request.role)
        assertEquals(2, request.maxLevel)
    }

    @Test
    fun phoneRoleIsRejected() {
        val bad = validJson.replace("\"pc\"", "\"phone\"")
        assertTrue(PairingRequests.parse(bad).isFailure)
    }

    @Test
    fun levelThreeIsRejected() {
        val bad = validJson.replace("\"max_level\":2", "\"max_level\":3")
        assertTrue(PairingRequests.parse(bad).isFailure)
    }

    @Test
    fun wrongVersionIsRejected() {
        val bad = validJson.replace("\"v\":1", "\"v\":2")
        assertTrue(PairingRequests.parse(bad).isFailure)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertTrue(PairingRequests.parse("{not json").isFailure)
    }

    @Test
    fun badPubkeyIsRejected() {
        val bad = validJson.replace("\"kty\":\"EC\"", "\"kty\":\"RSA\"")
        assertTrue(PairingRequests.parse(bad).isFailure)
    }
}
