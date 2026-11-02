package cn.phoneassistant.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SecureSettings：Keystore AES-GCM 字段级加密的实际行为。 */
@RunWith(AndroidJUnit4::class)
class SecureSettingsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = "secure_settings_test"

    @After
    fun cleanup() {
        context.deleteSharedPreferences(file)
    }

    @Test
    fun roundTripRemoveAndPersist() {
        val store = SecureSettings(context, file)
        assertTrue(store.putString("token", "s3cret"))
        assertEquals("s3cret", store.getString("token", "?"))

        assertEquals("s3cret", SecureSettings(context, file).getString("token", "?"))

        assertTrue(store.remove("token"))
        assertEquals("fallback", SecureSettings(context, file).getString("token", "fallback"))
    }

    @Test
    fun storedValueIsNotPlaintext() {
        val store = SecureSettings(context, file)
        store.putString("token", "s3cret")
        val raw = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            .getString("token", "").orEmpty()
        assertFalse(raw.contains("s3cret"))
        assertTrue(raw.isNotEmpty())
    }

    @Test
    fun tamperedValueFallsBackToDefault() {
        val store = SecureSettings(context, file)
        store.putString("token", "s3cret")
        val raw = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        val encoded = raw.getString("token", "").orEmpty()
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
        raw.edit().putString("token", tampered).commit()

        assertEquals("fallback", store.getString("token", "fallback"))
    }

    @Test
    fun ciphertextCannotMoveBetweenFields() {
        val store = SecureSettings(context, file)
        store.putString("a", "value-a")
        val raw = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        raw.edit().putString("b", raw.getString("a", "").orEmpty()).commit()

        assertEquals("fallback", store.getString("b", "fallback"))
        assertEquals("value-a", store.getString("a", "?"))
    }
}
