package cn.phoneassistant.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备忘的本机存储：只写应用私有目录 files/notes，不写外部存储，
 * 不上传、不分享，卸载应用后随应用数据一并清除。
 */
object NoteStore {

    /** 保存一条备忘，返回实际文件名；失败时抛异常，由调用方转为中文错误。 */
    fun save(context: Context, title: String, content: String): String {
        val dir = File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }
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
}
