package cn.kangyang.workbook.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class Repository(context: Context, databaseName: String = "workbook.db", storageName: String = "workbook") {
    private val appContext = context.applicationContext
    internal val filesRoot: File = File(appContext.filesDir, storageName).apply { mkdirs() }
    internal val database = Room.databaseBuilder(appContext, WorkbookDatabase::class.java, databaseName).build()
    private val dao = database.dao()
    internal val mutationMutex = Mutex()
    val state: Flow<Snapshot> = dao.changes().map { snapshot() }

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) { database.withTransaction { snapshotUnlocked() } }
    internal suspend fun snapshotUnlocked(): Snapshot = Snapshot(dao.entries(), dao.drafts(), dao.attachments(), dao.sealedDrafts().map { it.id })

    suspend fun save(entry: Entry, draftId: String? = null, cancelActivityTasks: Boolean? = null): Entry = withContext(Dispatchers.IO) {
        mutationMutex.withLock { database.withTransaction {
            if (draftId != null && dao.sealed(draftId)) return@withTransaction dao.entry(entry.id) ?: throw DataProblem("该草稿已提交或已放弃，请重新打开记录")
            val previous = dao.entry(entry.id)
            if (previous != null && previous.kind != entry.kind) throw DataProblem("不能更改档案类型")
            val records = dao.entries().associateBy { it.id }.toMutableMap()
            val now = System.currentTimeMillis()
            var saved = entry.copy(title = entry.title.trim(), createdAt = previous?.createdAt ?: entry.createdAt, updatedAt = now)
            if (saved.kind == Kind.INTERACTION && saved.title.isBlank()) {
                val date = DateTimeFormatter.ofPattern("MM月dd日").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(saved.occurredAt))
                saved = saved.copy(title = "${records[saved.parentId]?.title ?: "临时速记"} · $date")
            }
            if (saved.kind == Kind.TASK) saved = saved.copy(fields = mapOf("status" to "未完成", "priority" to "普通") + saved.fields)
            if (saved.kind == Kind.ACTIVITY) saved = saved.copy(fields = mapOf("status" to "筹备中") + saved.fields)
            DataValidation.validateFields(saved)
            DataValidation.validateRelations(saved, records)
            dao.upsert(saved); records[saved.id] = saved
            if (saved.kind == Kind.ACTIVITY && saved["status"] == "已取消" && cancelActivityTasks == true) {
                records.values.filter { it.kind == Kind.TASK && it.parentId == saved.id && it["status"].ifBlank { "未完成" } == "未完成" }.forEach {
                    dao.upsert(it.copy(fields = it.fields + ("status" to "已取消"), updatedAt = now))
                }
            }
            if (saved.kind == Kind.CONTACT && saved["primary"] == "true") {
                records.values.filter { it.kind == Kind.CONTACT && it.parentId == saved.parentId && it.id != saved.id && it["primary"] == "true" }.forEach {
                    dao.upsert(it.copy(fields = it.fields + ("primary" to "false"), updatedAt = now))
                }
            }
            if (saved.kind == Kind.INTERACTION) {
                // Re-associating a quick note keeps every explicitly linked task in the same aggregate.
                records.values.filter { it.kind == Kind.TASK && it.sourceId == saved.id && it.parentId != saved.parentId }.forEach {
                    val moved = it.copy(parentId = saved.parentId, updatedAt = now)
                    dao.upsert(moved); records[moved.id] = moved
                }
                if (saved["nextStep"].isNotBlank()) {
                    val taskId = "followup-${saved.id}"
                    val oldTask = records[taskId]
                    val followupChanged = previous == null || listOf("nextStep", "nextDate", "nextTime", "nextReminder", "nextPriority").any { previous[it] != saved[it] }
                    if (oldTask == null || followupChanged) {
                    val task = Entry(
                        id = taskId, kind = Kind.TASK, title = saved["nextStep"].trim(), parentId = saved.parentId, sourceId = saved.id,
                        createdAt = oldTask?.createdAt ?: now, updatedAt = now, occurredAt = oldTask?.occurredAt ?: now,
                        fields = (oldTask?.fields ?: emptyMap()) + mapOf(
                            "date" to saved["nextDate"], "time" to saved["nextTime"], "reminder" to saved["nextReminder"],
                            "priority" to saved["nextPriority"].ifBlank { "普通" }, "status" to (oldTask?.get("status")?.ifBlank { "未完成" } ?: "未完成"),
                        ),
                    )
                    DataValidation.validateFields(task); DataValidation.validateRelations(task, records)
                    dao.upsert(task)
                    }
                }
            }
            if (draftId != null) { dao.seal(SealedDraft(draftId)); dao.deleteDraft(draftId) }
            saved
        } }
    }

    suspend fun saveDraft(draft: Draft): Boolean = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        DataValidation.requireSafeId(draft.id)
        if (dao.sealed(draft.id)) return@withTransaction false
        val old = dao.draft(draft.id)
        if (old != null && old.entry.id != draft.entry.id) throw DataProblem("草稿与记录不匹配")
        if (old != null && old.revision == draft.revision) return@withTransaction old.entry == draft.entry
        if (old != null && old.revision > draft.revision) return@withTransaction false
        if (draft.revision < 0) throw DataProblem("草稿版本无效")
        DataValidation.validateFields(draft.entry, draft = true)
        DataValidation.validateRelations(draft.entry, dao.entries().associateBy { it.id }, draft = true)
        dao.upsert(draft.copy(updatedAt = System.currentTimeMillis())); true
    } } }

    suspend fun discardDraft(id: String) = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        val draft = dao.draft(id)
        dao.seal(SealedDraft(id)); dao.deleteDraft(id)
        if (draft != null && dao.entry(draft.entry.id) == null && dao.drafts().none { it.entry.id == draft.entry.id }) {
            dao.attachments().filter { it.ownerId == draft.entry.id }.forEach { dao.deleteAttachment(it.id) }
        }
    } } }

    suspend fun archive(id: String, archived: Boolean) = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        val value = dao.entry(id) ?: throw DataProblem("档案不存在")
        if (value.kind !in setOf(Kind.ORGANIZATION, Kind.CUSTOMER)) throw DataProblem("此记录不支持归档")
        dao.upsert(value.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    } } }

    suspend fun setTaskStatus(id: String, status: String, note: String = "") = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        val task = dao.entry(id) ?: throw DataProblem("待办不存在")
        if (task.kind != Kind.TASK || status !in setOf("未完成", "已完成", "已取消")) throw DataProblem("待办状态无效")
        dao.upsert(task.copy(fields = task.fields + mapOf("status" to status, "completionNote" to note), updatedAt = System.currentTimeMillis()))
    } } }

    suspend fun cancelActivity(id: String, cancelTasks: Boolean) = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        val activity = dao.entry(id) ?: throw DataProblem("活动不存在")
        if (activity.kind != Kind.ACTIVITY) throw DataProblem("此记录不是活动")
        val now = System.currentTimeMillis()
        dao.upsert(activity.copy(fields = activity.fields + ("status" to "已取消"), updatedAt = now))
        if (cancelTasks) dao.entries().filter { it.kind == Kind.TASK && it.parentId == id && it["status"].ifBlank { "未完成" } == "未完成" }.forEach {
            dao.upsert(it.copy(fields = it.fields + ("status" to "已取消"), updatedAt = now))
        }
    } } }

    /** Deletion is deliberately restrictive. Archives preserve historical relationships. */
    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) { mutationMutex.withLock { database.withTransaction {
        val snapshot = snapshotUnlocked()
        val entry = snapshot.find(id) ?: return@withTransaction
        if (snapshot.entries.any { it.parentId == id || it.sourceId == id || it["linkedOrganization"] == id || it["linkedActivity"] == id || it["organizationId"] == id } || snapshot.drafts.any { it.entry.id == id || it.entry.parentId == id || it.entry.sourceId == id }) throw DataProblem("此档案有历史记录或待办，请使用归档；相关内容会继续保留")
        if (snapshot.attachments.any { it.ownerId == id }) throw DataProblem("请先确认并移除该记录的附件，再删除记录")
        if (entry.kind == Kind.PROFILE) throw DataProblem("本人资料可编辑，不能删除")
        dao.deleteEntry(id)
    } } }

    suspend fun importAttachment(ownerId: String, uri: Uri): Attachment = withContext(Dispatchers.IO) { mutationMutex.withLock {
        val snapshot = snapshot()
        if (snapshot.entries.none { it.id == ownerId } && snapshot.drafts.none { it.entry.id == ownerId }) throw DataProblem("请先等待草稿保存，再添加附件")
        val attachment = try { AttachmentFiles.import(appContext, filesRoot, ownerId, uri) }
        catch (e: DataProblem) { throw e } catch (e: Exception) { throw DataProblem("无法读取附件，已填写内容仍保留，请重试", e) }
        val existing = snapshot.attachments.find { it.ownerId == ownerId && it.sha256 == attachment.sha256 && it.name == attachment.name }
        if (existing != null) existing else { dao.upsert(attachment); attachment }
    } }

    suspend fun removeAttachment(id: String) = withContext(Dispatchers.IO) { mutationMutex.withLock {
        // Immutable files are retained for the last restore rollback. Only the selected reference is removed.
        dao.deleteAttachment(id)
    } }
    fun attachmentFile(attachment: Attachment): File = AttachmentFiles.checked(filesRoot, attachment)

    suspend fun <T> withConsistentSnapshot(block: suspend (Snapshot) -> T): T = withContext(Dispatchers.IO) {
        mutationMutex.withLock { block(snapshot()) }
    }

    internal suspend fun replaceSnapshot(snapshot: Snapshot) { database.withTransaction {
        val old = snapshotUnlocked()
        dao.rollback(RestoreRollback(snapshotJson = dataJson.encodeToString(old), createdAt = System.currentTimeMillis()))
        dao.clearAttachments(); dao.clearDrafts(); dao.clearEntries(); dao.clearSeals()
        dao.upsertEntries(snapshot.entries); dao.upsertDrafts(snapshot.drafts); dao.upsertAttachments(snapshot.attachments)
        dao.sealAll(snapshot.sealedDraftIds.map { SealedDraft(it) })
    } }

    suspend fun rollbackLastRestore(): Boolean = withContext(Dispatchers.IO) { mutationMutex.withLock {
        val rollback = dao.rollback() ?: return@withLock false
        val snapshot = dataJson.decodeFromString<Snapshot>(rollback.snapshotJson)
        DataValidation.validateSnapshot(snapshot)
        snapshot.attachments.forEach { AttachmentFiles.checked(filesRoot, it) }
        replaceSnapshot(snapshot); true
    } }
    suspend fun hasRollback(): Boolean = withContext(Dispatchers.IO) { dao.rollback() != null }
    fun close() { database.close() }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) { instance ?: Repository(context).also { instance = it } }
    }
}
