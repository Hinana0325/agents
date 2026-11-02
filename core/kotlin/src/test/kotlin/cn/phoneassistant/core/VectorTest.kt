package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金标准向量测试：与 tools/vector-runner/py 跑同一份 protocol/vectors。
 * 任何一条不一致都失败；向量总数低于 150 也失败（防止有人删向量“修绿”）。
 */
class VectorTest {

    @Test
    fun allVectorsPass() {
        val dir = File(System.getProperty("vectors.dir") ?: error("vectors.dir system property is required"))
        assertTrue("vectors dir not found: $dir", dir.isDirectory)

        val failures = mutableListOf<String>()
        var total = 0

        dir.listFiles { file -> file.isDirectory }!!
            .sortedBy { it.name }
            .forEach { categoryDir ->
                val file = File(categoryDir, "cases.json")
                if (!file.exists()) return@forEach
                val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray
                cases.forEach { element ->
                    total++
                    val case = element.jsonObject
                    val name = "${categoryDir.name}/${case["name"]!!.jsonPrimitive.content}"
                    val envelope = case["envelope"]!!
                    val ctx = parseContext(case["context"])
                    val expect = case["expect"]!!.jsonObject
                    val got = EnvelopeValidator.validate(envelope, ctx)

                    val expectedOk = expect["ok"]!!.jsonPrimitive.booleanOrNull ?: false
                    val matches = if (expectedOk) {
                        got.ok &&
                            got.level == expect["level"]?.jsonPrimitive?.int &&
                            got.executable == (expect["executable"]?.jsonPrimitive?.booleanOrNull ?: true)
                    } else {
                        !got.ok && got.code == expect["code"]?.jsonPrimitive?.content
                    }
                    if (!matches) {
                        failures += "FAIL $name\n  expect $expect\n  got    $got"
                    }
                }
            }

        assertTrue("vectors under $dir are fewer than 150 (found $total)", total >= 150)
        assertEquals(failures.joinToString("\n"), "", failures.joinToString("\n"))
    }

    private fun parseContext(element: JsonElement?): ValidationContext {
        if (element == null || element !is JsonObject) return ValidationContext()
        return ValidationContext(
            now = element["now"]?.jsonPrimitive?.content,
            seenIds = (element["seen_ids"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            pubkeyJwk = element["pubkey_jwk"]?.jsonObject,
            clockOffsetSeconds = element["clock_offset_s"]?.jsonPrimitive?.int ?: 0,
            appCatalog = element["app_catalog"]?.jsonObject,
            urlAllowlist = (element["url_allowlist"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }.orEmpty(),
            recentAlarms24h = element["recent_alarms_24h"]?.jsonPrimitive?.int ?: 0,
            deviceRole = element["device_role"]?.jsonPrimitive?.content,
        )
    }
}
