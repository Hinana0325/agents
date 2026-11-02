package cn.phoneassistant.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun validCatalogParses() {
        val catalog = AppCatalog.parse(json("""{"wechat":"com.tencent.mm","settings":"com.android.settings"}"""))
            .getOrThrow()
        assertEquals("com.tencent.mm", catalog["wechat"])
        assertEquals(2, catalog.size)
    }

    @Test
    fun emptyObjectIsValid() {
        assertNull(AppCatalog.validate(json("{}")))
        assertTrue(AppCatalog.parse(json("{}")).getOrThrow().isEmpty())
    }

    @Test
    fun nonObjectIsRejected() {
        assertNotNull(AppCatalog.validate(json("[]")))
        assertNotNull(AppCatalog.validate(json("\"wechat\"")))
    }

    @Test
    fun badAppIdIsRejected() {
        assertNotNull(AppCatalog.validate(json("""{"WeChat":"com.tencent.mm"}""")))
        assertNotNull(AppCatalog.validate(json("""{"w":"com.tencent.mm"}""")))
    }

    @Test
    fun badPackageIsRejected() {
        assertNotNull(AppCatalog.validate(json("""{"wechat":"wechat"}""")))
        assertNotNull(AppCatalog.validate(json("""{"wechat":"com..tencent"}""")))
    }

    @Test
    fun nonStringValueIsRejected() {
        assertNotNull(AppCatalog.validate(buildJsonObject { put("wechat", 1) }))
    }

    @Test
    fun tooManyEntriesIsRejected() {
        val obj = buildJsonObject {
            repeat(AppCatalog.MAX_ENTRIES + 1) { index ->
                put("app$index", "com.example.app$index")
            }
        }
        assertNotNull(AppCatalog.validate(obj))
    }
}
