package cn.phoneassistant.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IssuedCertRecord(
    val deviceId: String,
    val certHash: String,
    val issuedAtMillis: Long,
    val certJson: String,
    val revokedAtMillis: Long? = null,
    val revocationJson: String? = null,
)

/** 手机本机保存自己签发过的设备证书，支持之后生成吊销记录。 */
class IssuedCertStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<IssuedCertRecord> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString<List<IssuedCertRecord>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(record: IssuedCertRecord) {
        val updated = list().filterNot { it.certHash == record.certHash } + record
        save(updated)
    }

    @Synchronized
    fun markRevoked(certHash: String, revocationJson: String, nowMillis: Long = System.currentTimeMillis()) {
        val updated = list().map {
            if (it.certHash == certHash) it.copy(revokedAtMillis = nowMillis, revocationJson = revocationJson) else it
        }
        save(updated)
    }

    private fun save(items: List<IssuedCertRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }
}
