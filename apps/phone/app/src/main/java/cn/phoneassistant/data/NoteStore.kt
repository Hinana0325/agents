package cn.phoneassistant.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备忘列表里的一项。 */
data class NoteEntry(
    val fileName: String,
    val title: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
)

/**
 * 备忘的本机存储：只写应用私有目录 files/notes，不写外部存储，
 * 不上传、不分享，卸载应用后随应用数据一并清除。
 *
 * 读取与删除都只接受 notes 目录下的 .txt 文件名，拒绝任何路径分隔符，
 * 防止通过文件名跳出目录。
 */
object NoteStore {

    private const val MAX_READ_BYTES = 100_000

    /** 保存一条备忘，返回实际文件名；失败时抛异常，由调用方转为中文错误。 */
    fun save(context: Context, title: String, content: String): String {
        val dir = notesDir(context).apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").take(40)
        val baseName = if (safeTitle.isEmpty()) stamp else "${stamp}_$safeTitle"
        var file = File(dir, "$baseName.txt")
        var index = 1
        while (file.exists()) {
            index++
            file = File(dir, "${baseName}_$index.txt")
        }
        file.writeText(content, Charsets.UTF_8)
        return file.name
    }

    fun list(context: Context): List<NoteEntry> {
        val dir = notesDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            ?.map { file ->
                NoteEntry(
                    fileName = file.name,
                    title = displayTitle(file.name),
                    modifiedAt = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }
            ?.sortedByDescending { it.modifiedAt }
            .orEmpty()
    }

    fun read(context: Context, fileName: String): String? = runCatching {
        val file = resolve(context, fileName)?.takeIf { it.isFile } ?: return null
        val length = minOf(file.length(), MAX_READ_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)
        var read = 0
        java.io.FileInputStream(file).use { stream ->
            while (read < length) {
                val n = stream.read(bytes, read, length - read)
                if (n <= 0) break
                read += n
            }
        }
        String(bytes, 0, read, Charsets.UTF_8)
    }.getOrNull()

    fun delete(context: Context, fileName: String): Boolean =
        resolve(context, fileName)?.delete() == true

    private fun notesDir(context: Context): File = File(context.filesDir, "notes")

    private fun resolve(context: Context, fileName: String): File? {
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\')) return null
        if (!fileName.endsWith(".txt")) return null
        val dir = notesDir(context)
        val file = File(dir, fileName)
        return if (file.canonicalFile.parentFile == dir.canonicalFile) file else null
    }

    /** 文件名形如 20261102-101500_购物，界面只显示标题部分。 */
    private fun displayTitle(fileName: String): String {
        val withoutExt = fileName.removeSuffix(".txt")
        val title = withoutExt.replaceFirst(Regex("^\\d{8}-\\d{6}_?"), "")
        return title.ifBlank { withoutExt }
    }
}
