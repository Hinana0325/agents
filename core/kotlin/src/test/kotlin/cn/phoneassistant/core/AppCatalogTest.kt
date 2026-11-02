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
    fun addsPackagesWithGeneratedIds() {
        val merged = AppCatalog.addPackages(emptyMap(), listOf("com.a", "com.b"))
        assertEquals("com.a", merged["app1"])
        assertEquals("com.b", merged["app2"])
    }

    @Test
    fun skipsPackagesAlreadyPresent() {
        val existing = mapOf("wechat" to "com.tencent.mm")
        val merged = AppCatalog.addPackages(existing, listOf("com.tencent.mm", "com.b"))
        assertEquals("com.b", merged["app1"])
        assertEquals(2, merged.size)
    }

    @Test
    fun fillsUnusedGeneratedIds() {
        val existing = mapOf("app1" to "com.a", "app3" to "com.c")
        val merged = AppCatalog.addPackages(existing, listOf("com.b"))
        assertEquals("com.b", merged["app2"])
        assertEquals(3, merged.size)
    }

    @Test
    fun deduplicatesInputAndKeepsCustomIds() {
        val existing = mapOf("wechat" to "com.tencent.mm")
        val merged = AppCatalog.addPackages(existing, listOf("com.b", "com.b"))
        assertEquals(mapOf("wechat" to "com.tencent.mm", "app1" to "com.b"), merged)
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
