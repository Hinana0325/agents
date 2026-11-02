package cn.phoneassistant.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptValidatorTest {

    private fun verify(text: String): String? = ReceiptValidator.verify(Json.parseToJsonElement(text))

    private fun receipt(
        status: String = "done",
        envelopeId: String = "0193f0a8-9c1b-7000-8000-000000000001",
        extra: String = "",
    ): String =
        """{"v":1,"envelope_id":"$envelopeId","device_id":"phone-1","status":"$status","ts":"2026-11-02T10:00:00Z"$extra}"""

    @Test
    fun validDone() {
        assertNull(verify(receipt()))
    }

    @Test
    fun validWithError() {
        assertNull(verify(receipt(status = "awaiting_confirm", extra = ""","error":{"code":"APPROVAL_LEVEL"}""")))
        assertNull(
            verify(
                receipt(
                    status = "failed",
                    extra = ""","error":{"code":"EXECUTOR_FAILED","detail":"boom"}""",
                ),
            ),
        )
    }

    @Test
    fun unknownFieldIsRejected() {
        assertEquals(ReceiptValidator.INVALID, verify(receipt(extra = ""","extra":1""")))
    }

    @Test
    fun unknownStatusIsRejected() {
        assertEquals(ReceiptValidator.INVALID, verify(receipt(status = "accepted_by_hub")))
    }

    @Test
    fun emptyEnvelopeIdIsRejected() {
        assertEquals(ReceiptValidator.INVALID, verify(receipt(envelopeId = "")))
    }

    @Test
    fun invalidTimestampIsRejected() {
        val text = """{"v":1,"envelope_id":"e","device_id":"phone-1","status":"done","ts":"2026-13-99T99:99:99Z"}"""
        assertEquals(ReceiptValidator.INVALID, verify(text))
    }

    @Test
    fun errorWithoutCodeIsRejected() {
        assertEquals(ReceiptValidator.INVALID, verify(receipt(extra = ""","error":{"detail":"x"}""")))
    }

    @Test
    fun errorUnknownFieldIsRejected() {
        assertEquals(ReceiptValidator.INVALID, verify(receipt(extra = ""","error":{"code":"X","extra":1}""")))
    }

    @Test
    fun longErrorDetailIsRejected() {
        val detail = "x".repeat(501)
        assertEquals(
            ReceiptValidator.INVALID,
            verify(receipt(extra = ""","error":{"code":"X","detail":"$detail"}""")),
        )
    }

    @Test
    fun versionStringIsRejected() {
        val text = """{"v":"1","envelope_id":"e","device_id":"phone-1","status":"done","ts":"2026-11-02T10:00:00Z"}"""
        assertEquals(ReceiptValidator.INVALID, verify(text))
    }
}
