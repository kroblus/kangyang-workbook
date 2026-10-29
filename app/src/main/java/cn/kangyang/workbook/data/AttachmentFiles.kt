package cn.kangyang.workbook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

object AttachmentFiles {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private val extensionMime = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
    )
    fun supports(mime: String, name: String): Boolean = mime in extensionMime.values &&
        (name.substringAfterLast('.', "").lowercase() in extensionMime || mime.startsWith("image/"))

    fun import(context: Context, root: File, ownerId: String, uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = "附件"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni)
                if (si >= 0 && !cursor.isNull(si)) declaredSize = cursor.getLong(si)
            }
        }
        name = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(255).ifBlank { "附件" }
        if (declaredSize != null && declaredSize!! > MAX_FILE_BYTES) throw DataProblem("文件过大，请选择 20 MB 以内的附件")
        val detectedMime = resolver.getType(uri).orEmpty().lowercase()
        val mime = if (detectedMime in extensionMime.values) detectedMime else extensionMime[name.substringAfterLast('.', "").lowercase()].orEmpty()
        if (!supports(mime, name)) throw DataProblem("不支持此格式，请选择照片、PDF、文本或常见 Office 文件")
        if (root.usableSpace < (declaredSize?.coerceAtLeast(0) ?: MAX_FILE_BYTES) + 4L * 1024 * 1024) throw DataProblem("存储空间不足，请释放空间后重试")
        val stream = resolver.openInputStream(uri) ?: throw DataProblem("无法读取所选文件，请重新选择")
        return stream.use { write(root, ownerId, name, mime, it) }
    }

    fun write(root: File, ownerId: String, name: String, mime: String, input: InputStream): Attachment {
        val dir = File(root, "attachments").apply { if (!exists() && !mkdirs()) throw DataProblem("无法创建附件存储目录") }
        val temp = File(dir, ".import-${UUID.randomUUID()}")
        try {
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n
                    if (size > MAX_FILE_BYTES) throw DataProblem("文件过大，请选择 20 MB 以内的附件")
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
            if (size == 0L) throw DataProblem("所选文件为空，请重新选择")
            validateSignature(temp, mime)
            val hash = digest.digest().hex()
            val finalFile = File(dir, "$hash.bin")
            if (finalFile.exists()) {
                if (finalFile.length() != size || sha256(finalFile) != hash) throw DataProblem("已有附件完整性异常，请先备份并检查存储")
            } else if (!temp.renameTo(finalFile)) throw DataProblem("附件保存失败，请检查剩余空间后重试")
            return Attachment(ownerId = ownerId, name = name, mime = mime, relativePath = "attachments/$hash.bin", size = size, sha256 = hash)
        } catch (e: DataProblem) { throw e
        } catch (e: Exception) { throw DataProblem("附件读取或保存失败，原有内容已保留，请重试", e)
        } finally { temp.delete() }
    }

    fun resolve(root: File, attachment: Attachment): File {
        if (!attachment.sha256.matches(Regex("[a-f0-9]{64}")) || attachment.relativePath != "attachments/${attachment.sha256}.bin") throw DataProblem("附件路径无效")
        val file = File(root, attachment.relativePath)
        val directory = File(root, "attachments").canonicalFile
        if (file.canonicalFile.parentFile != directory) throw DataProblem("附件路径超出允许范围")
        return file
    }

    fun checked(root: File, attachment: Attachment): File {
        val file = resolve(root, attachment)
        if (!file.isFile || file.length() != attachment.size || sha256(file) != attachment.sha256) throw DataProblem("附件丢失或已损坏，其他记录仍保留；可从完整备份恢复")
        return file
    }

    fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().hex()
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun validateSignature(file: File, mime: String) {
        val bytes = ByteArray(12)
        val count = file.inputStream().use { it.read(bytes) }
        fun ascii(n: Int) = if (count >= n) String(bytes, 0, n, Charsets.ISO_8859_1) else ""
        val valid = when (mime) {
            "image/png" -> count >= 8 && bytes.take(8) == listOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map { it.toByte() }
            "image/jpeg" -> count >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            "image/gif" -> ascii(6) in setOf("GIF87a", "GIF89a")
            "image/webp" -> ascii(4) == "RIFF" && count >= 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
            "application/pdf" -> ascii(5) == "%PDF-"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.oasis.opendocument.text" -> ascii(2) == "PK"
            else -> true
        }
        if (!valid) throw DataProblem("文件内容与格式不匹配，请重新选择有效文件")
    }
}
