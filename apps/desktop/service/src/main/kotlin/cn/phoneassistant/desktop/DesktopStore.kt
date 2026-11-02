package cn.phoneassistant.desktop

import java.io.File
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 本地持久化：已处理信封 id 与回执。
 *
 * 作用：
 * - 跨重启去重（内存里的 DeviceRuntime 记录在进程退出后就没了）；
 * - 保存回执历史，重复投递可以直接返回上次结果；
 * - 只有终态（done/failed/rejected/cancelled）才落库，待确认的不落库，重启后会重新处理。
 */
class DesktopStore(file: File) : AutoCloseable {

    private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS processed (" +
                    "envelope_id TEXT PRIMARY KEY, " +
                    "status TEXT NOT NULL, " +
                    "receipt TEXT NOT NULL, " +
                    "processed_at TEXT NOT NULL)",
            )
        }
    }

    @Synchronized
    fun receipt(envelopeId: String): JsonObject? {
        connection.prepareStatement("SELECT receipt FROM processed WHERE envelope_id = ?").use { ps ->
            ps.setString(1, envelopeId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return json.parseToJsonElement(rs.getString(1)) as JsonObject
            }
        }
    }

    @Synchronized
    fun save(envelopeId: String, receipt: JsonObject) {
        connection.prepareStatement("INSERT OR REPLACE INTO processed VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, envelopeId)
            ps.setString(2, (receipt["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown")
            ps.setString(3, receipt.toString())
            ps.setString(4, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun count(): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM processed").use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    @Synchronized
    override fun close() {
        connection.close()
    }
}
