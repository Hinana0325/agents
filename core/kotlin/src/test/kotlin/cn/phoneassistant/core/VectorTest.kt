package cn.phoneassistant.core

import cn.phoneassistant.core.runtime.ApprovalValidator
import cn.phoneassistant.core.runtime.TrustedApprover
import java.io.File
import java.time.Instant
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
                    val expect = case["expect"]!!.jsonObject
                    val expectedOk = expect["ok"]!!.jsonPrimitive.booleanOrNull ?: false
                    val matches = when (categoryDir.name) {
                        "cert", "revocation" -> {
                            val context = case["context"]?.jsonObject
                            val root = context?.get("trust_root_jwk")?.jsonObject
                            val now = context?.get("now")?.jsonPrimitive?.content
                            val revoked = (context?.get("revoked_hashes") as? JsonArray)
                                ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                            if (root == null || now == null) {
                                false
                            } else {
                                val code = if (categoryDir.name == "cert") {
                                    DeviceCert.verify(case["cert"]!!, root, Instant.parse(now), revoked)
                                } else {
                                    Revocation.verify(case["revocation"]!!, root, Instant.parse(now))
                                }
                                if (expectedOk) code == null
                                else code == expect["code"]?.jsonPrimitive?.content
                            }
                        }
                        "receipt" -> {
                            val error = ReceiptValidator.verify(case["receipt"]!!)
                            if (expectedOk) error == null
                            else error == expect["code"]?.jsonPrimitive?.content
                        }
                        "approval" -> {
                            val context = case["context"]!!.jsonObject
                            val approvers = context["approvers"]!!.jsonObject.mapValues { (id, element) ->
                                val obj = element.jsonObject
                                TrustedApprover(
                                    id,
                                    obj["max_level"]!!.jsonPrimitive.int,
                                    obj["pubkey_jwk"]!!.jsonObject,
                                )
                            }
                            val used = (context["used_nonces"] as? JsonArray)
                                ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                            val error = ApprovalValidator.verify(
                                approval = case["approval"]!!,
                                envelope = case["envelope"]!!.jsonObject,
                                requiredLevel = context["required_level"]!!.jsonPrimitive.int,
                                approvers = approvers,
                                usedNonces = used,
                                now = Instant.parse(context["now"]!!.jsonPrimitive.content),
                                windowSeconds = context["window_s"]!!.jsonPrimitive.content.toLong(),
                            )
                            if (expectedOk) error == null
                            else error == expect["code"]?.jsonPrimitive?.content
                        }
                        else -> {
                            val got = EnvelopeValidator.validate(case["envelope"]!!, parseContext(case["context"]))
                            if (expectedOk) {
                                got.ok &&
                                    got.level == expect["level"]?.jsonPrimitive?.int &&
                                    got.executable == (expect["executable"]?.jsonPrimitive?.booleanOrNull ?: true)
                            } else {
                                !got.ok && got.code == expect["code"]?.jsonPrimitive?.content
                            }
                        }
                    }
                    if (!matches) {
                        failures += "FAIL $name\n  expect $expect"
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
