package cn.kangyang.workbook.storage

import android.content.Context
import cn.kangyang.workbook.BuildConfig
import cn.kangyang.workbook.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class BackupManifest(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val createdAt: Long,
    val snapshot: Snapshot,
)

class PreparedRestore internal constructor(
    val snapshot: Snapshot,
    val appVersion: String,
    val createdAt: Long,
    internal val files: Map<String, ByteArray>,
) {
    val summary: String get() {
        fun count(kind: Kind) = snapshot.entries.count { it.kind == kind }
        return "单位 ${count(Kind.ORGANIZATION)}，客户 ${count(Kind.CUSTOMER)}，联系人 ${count(Kind.CONTACT)}，服务对象 ${count(Kind.RECIPIENT)}，沟通 ${count(Kind.INTERACTION)}，活动 ${count(Kind.ACTIVITY)}，待办 ${count(Kind.TASK)}，草稿 ${snapshot.drafts.size}，附件 ${snapshot.attachments.size}。\n确认后会完整替换本机当前资料，并保留一次可回滚快照。"
    }
}

/** Password-portable backup; no device-local key and no network are involved. */
class BackupManager(private val repository: Repository, @Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun export(password: String): ByteArray = repository.withConsistentSnapshot { snapshot ->
        if (password.length < 8) throw DataProblem("备份口令至少 8 个字符，请妥善保存；口令无法找回")
        DataValidation.validateSnapshot(snapshot)
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        snapshot.attachments.distinctBy { it.relativePath }.forEach { attachment ->
            total += attachment.size
            if (total > BackupCodec.MAX_UNCOMPRESSED) throw DataProblem("附件总量超过本版本 64 MB 备份限制，请先分批整理")
            files[attachment.relativePath] = repository.attachmentFile(attachment).readBytes()
        }
        BackupCodec.encode(BackupManifest(appVersion = BuildConfig.VERSION_NAME, createdAt = System.currentTimeMillis(), snapshot = snapshot), files, password)
    }

    /** Inspection only allocates bounded memory. It never writes current files or database rows. */
    suspend fun inspect(bytes: ByteArray, password: String): PreparedRestore = withContext(Dispatchers.IO) {
        BackupCodec.decode(bytes, password)
    }

    /**
     * Immutable files are durably staged before one SQLite transaction changes all references.
     * A crash before the transaction leaves the complete old state; a crash after commit leaves
     * the complete new state. Old files remain available to the rollback snapshot.
     * checkpoint is a test seam, not a production UI control.
     */
    suspend fun restore(prepared: PreparedRestore, checkpoint: ((String) -> Unit)? = null) = withContext(Dispatchers.IO) {
        repository.mutationMutex.withLock {
            BackupCodec.validatePrepared(prepared)
            var totalMissing = 0L
            prepared.snapshot.attachments.distinctBy { it.relativePath }.forEach {
                val file = AttachmentFiles.resolve(repository.filesRoot, it)
                if (!file.exists()) totalMissing += it.size
            }
            if (repository.filesRoot.usableSpace < totalMissing + 8L * 1024 * 1024) throw DataProblem("空间不足，当前资料未修改，请释放空间后重试")
            checkpoint?.invoke("validated")
            prepared.snapshot.attachments.distinctBy { it.relativePath }.forEach { attachment ->
                val target = AttachmentFiles.resolve(repository.filesRoot, attachment)
                val alreadyValid = target.exists() && runCatching { AttachmentFiles.checked(repository.filesRoot, attachment) }.isSuccess
                if (!alreadyValid) {
                    target.parentFile!!.mkdirs()
                    val temp = java.io.File(target.parentFile, ".restore-${UUID.randomUUID()}")
                    try {
                        FileOutputStream(temp).use { stream -> stream.write(prepared.files.getValue(attachment.relativePath)); stream.fd.sync() }
                        if (temp.length() != attachment.size || AttachmentFiles.sha256(temp) != attachment.sha256) throw DataProblem("附件写入校验失败，当前资料未修改")
                        // Same-directory rename is atomic. Replacing a damaged content-addressed file
                        // with its verified original bytes also repairs any old reference to that hash.
                        try { android.system.Os.rename(temp.absolutePath, target.absolutePath) }
                        catch (e: Exception) { throw DataProblem("附件写入失败，当前资料未修改", e) }
                    } finally { temp.delete() }
                }
            }
            checkpoint?.invoke("files_staged")
            repository.replaceSnapshot(prepared.snapshot)
            checkpoint?.invoke("committed")
        }
    }
}

/** Narrow, versioned binary framing around standard PBKDF2-HMAC-SHA256 and AES-256-GCM. */
internal object BackupCodec {
    const val MAX_UNCOMPRESSED = 64L * 1024 * 1024
    const val MAX_PACKAGE = 80 * 1024 * 1024
    private const val MAX_MANIFEST = 16 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val magic = byteArrayOf(0x4b, 0x59, 0x57, 0x42) // KYWB

    fun encode(manifest: BackupManifest, files: Map<String, ByteArray>, password: String): ByteArray {
        if (password.length > 1024) throw DataProblem("备份口令过长")
        val json = dataJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        if (json.size > MAX_MANIFEST || json.size.toLong() + files.values.sumOf { it.size.toLong() } > MAX_UNCOMPRESSED) throw DataProblem("备份内容超过本版本 64 MB 限制")
        val packed = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json); zip.closeEntry()
                files.forEach { (name, contents) -> zip.putNextEntry(ZipEntry(name)); zip.write(contents); zip.closeEntry() }
            }
            bytes.toByteArray()
        }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { stream -> stream.write(magic); stream.writeInt(1); stream.writeInt(ITERATIONS); stream.write(salt); stream.write(iv) }
            bytes.toByteArray()
        }
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv, ITERATIONS)
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(packed)
        packed.fill(0)
        return header + encrypted
    }

    fun decode(bytes: ByteArray, password: String): PreparedRestore {
        if (bytes.size !in 56..MAX_PACKAGE) throw DataProblem("备份文件为空、过大或已损坏")
        if (password.length > 1024) throw DataProblem("备份口令过长")
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val foundMagic = ByteArray(4).also(input::readFully)
            if (!foundMagic.contentEquals(magic)) throw DataProblem("这不是康养工作簿的完整备份")
            if (input.readInt() != 1) throw DataProblem("此备份来自不支持的更新版本，请先更新应用")
            val iterations = input.readInt()
            if (iterations !in 100_000..1_000_000) throw DataProblem("备份加密参数不受支持")
            val salt = ByteArray(16).also(input::readFully)
            val iv = ByteArray(12).also(input::readFully)
            val header = bytes.copyOfRange(0, 40)
            val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv, iterations)
            cipher.updateAAD(header)
            val packed = try { cipher.doFinal(bytes, 40, bytes.size - 40) } catch (e: AEADBadTagException) { throw DataProblem("口令错误或备份已损坏，当前资料未修改", e) }
            try { return parseArchive(packed) } finally { packed.fill(0) }
        } catch (e: DataProblem) { throw e
        } catch (e: Exception) { throw DataProblem("无法读取备份，口令错误、文件损坏或格式不受支持；当前资料未修改", e) }
    }

    private fun parseArchive(bytes: ByteArray): PreparedRestore {
        val contents = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || (name != "manifest.json" && !name.matches(Regex("attachments/[a-f0-9]{64}\\.bin"))) || contents.containsKey(name)) throw DataProblem("备份含异常路径、重复项或不支持的文件")
                if (contents.size > 10_001) throw DataProblem("备份附件数量过多")
                val max = if (name == "manifest.json") MAX_MANIFEST.toLong() else AttachmentFiles.MAX_FILE_BYTES
                val data = ByteArrayOutputStream()
                var size = 0L
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n; total += n
                    if (size > max || total > MAX_UNCOMPRESSED) throw DataProblem("备份解压后超过安全大小限制")
                    data.write(buffer, 0, n)
                }
                contents[name] = data.toByteArray(); zip.closeEntry()
            }
        }
        val manifestBytes = contents.remove("manifest.json") ?: throw DataProblem("备份缺少数据清单")
        val manifest = try { dataJson.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8)) } catch (e: Exception) { throw DataProblem("备份数据清单无效或版本不兼容", e) }
        if (manifest.schemaVersion != 1) throw DataProblem("备份数据版本 ${manifest.schemaVersion} 不受当前应用支持，请先更新应用")
        if (manifest.appVersion.isBlank() || manifest.createdAt < 0) throw DataProblem("备份版本或时间信息无效")
        return PreparedRestore(manifest.snapshot, manifest.appVersion, manifest.createdAt, contents).also(::validatePrepared)
    }

    fun validatePrepared(prepared: PreparedRestore) {
        DataValidation.validateSnapshot(prepared.snapshot)
        val expected = prepared.snapshot.attachments.map { it.relativePath }.toSet()
        if (prepared.files.keys != expected) throw DataProblem("备份附件缺失或包含清单之外的文件，当前资料未修改")
        prepared.snapshot.attachments.forEach { attachment ->
            val bytes = prepared.files[attachment.relativePath] ?: throw DataProblem("备份缺少附件")
            if (bytes.size.toLong() != attachment.size || AttachmentFiles.sha256(bytes) != attachment.sha256) throw DataProblem("附件完整性校验失败，当前资料未修改")
        }
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray, iterations: Int): Cipher {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        chars.fill('\u0000')
        val keyBytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv)) } } finally { keyBytes.fill(0) }
    }
}
