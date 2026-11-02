package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncValidator 的金标准向量测试：只跑 protocol/vectors/sync/cases.json，
 * 与 tools/vector-runner/py/runner.py 的 sync 类别共用同一份向量。
 */
class SyncValidatorTest {

    @Test
    fun syncVectorsPass() {
        val dir = File(System.getProperty("vectors.dir") ?: error("vectors.dir system property is required"))
        val file = File(dir, "sync/cases.json")
        assertTrue("sync vectors not found: $file", file.isFile)

        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue("sync vectors fewer than 18 (found ${cases.size})", cases.size >= 18)

        val failures = mutableListOf<String>()
        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val expect = case["expect"]!!.jsonObject
            val expectedOk = expect["ok"]!!.jsonPrimitive.booleanOrNull ?: false
            val error = SyncValidator.validate(case["batch"]!!)
            val matches = if (expectedOk) error == null else error == expect["code"]?.jsonPrimitive?.content
            if (!matches) failures += "FAIL sync/$name\n  expect $expect\n  got    $error"
        }
        assertEquals(failures.joinToString("\n"), "", failures.joinToString("\n"))
    }
}
