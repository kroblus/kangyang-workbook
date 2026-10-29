package cn.kangyang.workbook.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
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
    internal val database = Room.databaseBuilder(appContext, WorkbookDatabase::class.java, databaseName)
        .addMigrations(WORKBOOK_MIGRATION_1_2).build()
    private val dao = database.dao()
    internal val mutationMutex = Mutex()
    private val sessionGeneration = AtomicLong(0)
    val generation: Long get() = sessionGeneration.get()
    val state: Flow<Snapshot> = dao.changes().map { snapshot() }

    /** Test seams are never wired to a production control. */
    internal var beforeNoteCommit: (suspend (Long) -> Unit)? = null
    internal var beforeAttachmentCommit: (suspend () -> Unit)? = null

    internal fun advanceGeneration(): Long = sessionGeneration.incrementAndGet()
    internal fun requireGeneration(expected: Long) {
        if (expected != generation) throw DataProblem("资料已恢复或编辑会话已更换，请重新打开记录")
    }
    private suspend fun <T> mutate(expected: Long, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutationMutex.withLock { database.withTransaction {
            requireGeneration(expected)
            val result = block()
            requireGeneration(expected)
            result
        } }
    }

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) { database.withTransaction { snapshotUnlocked() } }
    internal suspend fun snapshotUnlocked(): Snapshot = Snapshot(dao.entries(), dao.drafts(), dao.attachments(), dao.sealedDrafts().map { it.id })

    /** Claim a new editor, or resume the same persisted editor after process recreation. */
    suspend fun beginNoteSession(entryId: String, sessionId: String, expectedGeneration: Long = generation): Long = mutate(expectedGeneration) {
        DataValidation.requireSafeId(entryId); DataValidation.requireSafeId(sessionId)
        if (dao.sealed(sessionId)) throw DataProblem("这份编辑内容已经完成，请重新打开记录")
        val existing = dao.noteSession(entryId)
        if (existing?.sessionId == sessionId) return@mutate existing.revision
        // A second editor cannot leave the first editor free to write over it later.
        existing?.let {
            val oldBuffer = dao.draft(it.sessionId)
            val published = dao.entry(entryId)
            val hasAttachment = dao.attachments().any { attachment -> attachment.ownerId == entryId }
            if (oldBuffer != null && ((published == null && !hasAttachment) || (published != null && !sameNoteContent(oldBuffer.entry, published))) && oldBuffer.entry["content"].isNotBlank()) {
                dao.upsert(oldBuffer.copy(id = UUID.randomUUID().toString(), isNoteBuffer = false,
                    recoveryReason = "另一编辑会话的内容已保留，可查看后继续"))
            }
            retireNoteSession(it.sessionId, it.entryId)
        }
        val draft = dao.draft(sessionId)
        if (draft != null && draft.entry.id != entryId) throw DataProblem("编辑内容与记录不匹配")
        dao.noteSession(NoteSession(entryId, sessionId, -1))
        -1L
    }

    /** One transaction publishes the body and editor buffer; this never creates or reschedules tasks. */
    suspend fun saveNoteRevision(entry: Entry, sessionId: String, revision: Long, expectedGeneration: Long = generation): Entry? =
        mutate(expectedGeneration) {
            if (entry.kind != Kind.INTERACTION || revision < 0) throw DataProblem("记录编辑版本无效")
            val session = dao.noteSession(entry.id)
            if (session?.sessionId != sessionId || dao.sealed(sessionId)) throw DataProblem("此编辑会话已结束，请重新打开记录")
            if (session.revision > revision) throw DataProblem("已保留较新的内容，此旧版本未覆盖记录")
            val oldBuffer = dao.draft(sessionId)
            if (session.revision == revision) {
                if (oldBuffer == null || !sameNoteContent(oldBuffer.entry, entry)) throw DataProblem("同一保存版本含不同内容，请重新打开后重试")
                return@mutate dao.entry(entry.id)
            }
            beforeNoteCommit?.invoke(revision)
            requireGeneration(expectedGeneration)
            val previous = dao.entry(entry.id)
            if (previous != null && previous.kind != Kind.INTERACTION) throw DataProblem("不能更改档案类型")
            val hasContent = entry["content"].isNotBlank() || dao.attachments().any { it.ownerId == entry.id }
            val records = dao.entries().associateBy { it.id }
            DataValidation.validateFields(entry, draft = !hasContent && previous == null)
            DataValidation.validateRelations(entry, records, draft = !hasContent && previous == null)
            val now = System.currentTimeMillis()
            // Explicitly continuing a conflicting legacy editor preserves the currently published
            // side as another recoverable draft before replacing it, so neither side is discarded.
            if (oldBuffer?.recoveryReason?.isNotBlank() == true && previous != null && previous != entry) {
                dao.upsert(Draft(UUID.randomUUID().toString(), previous, 0, now,
                    recoveryReason = "采用另一份编辑内容前的记录已保留，可查看后继续"))
            }
            val buffer = Draft(sessionId, entry, revision, now, isNoteBuffer = true, baseUpdatedAt = previous?.updatedAt)
            dao.upsert(buffer)
            dao.noteSession(session.copy(revision = revision))
            if (!hasContent && previous == null) return@mutate null
            // A display title is derived, never cached, so clearing body clears its search matches.
            val saved = entry.copy(title = entry.title.trim(), createdAt = previous?.createdAt ?: entry.createdAt, updatedAt = now)
            dao.upsert(saved)
            moveLinkedTasks(saved, records.values, now)
            saved
        }

    suspend fun finishNoteSession(sessionId: String, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val session = dao.sessionById(sessionId)
        retireNoteSession(sessionId, session?.entryId ?: dao.draft(sessionId)?.entry?.id)
    }

    /** Attachment ownership can commit just before the editor's final body revision. Closing or
     * replacing that editor must never remove the sole owner of an already imported file. */
    private suspend fun retireNoteSession(sessionId: String, entryId: String?) {
        val buffer = dao.draft(sessionId)
        if (entryId != null && dao.entry(entryId) == null && dao.attachments().any { it.ownerId == entryId }) {
            if (buffer == null || buffer.entry.kind != Kind.INTERACTION) throw DataProblem("附件的记录内容尚未准备好，请重试保存后退出")
            val problem = runCatching {
                DataValidation.validateFields(buffer.entry)
                DataValidation.validateRelations(buffer.entry, dao.entries().associateBy { it.id })
            }.exceptionOrNull()
            if (problem != null) {
                // Keep the only valid attachment owner and its raw input available for correction.
                // Removing the active session rejects its late note writes without sealing this
                // recoverable draft, which the user can explicitly reopen later.
                dao.upsert(buffer.copy(isNoteBuffer = false, recoveryReason = "附件已保留，请检查这份记录：${problem.message}"))
                dao.deleteNoteSession(sessionId)
                return
            }
            dao.upsert(buffer.entry.copy(title = buffer.entry.title.trim(), updatedAt = System.currentTimeMillis()))
        }
        dao.seal(SealedDraft(sessionId)); dao.deleteNoteSession(sessionId); dao.deleteDraft(sessionId)
    }

    private suspend fun moveLinkedTasks(note: Entry, entries: Collection<Entry>, now: Long) {
        entries.filter { it.kind == Kind.TASK && it.sourceId == note.id && it.parentId != note.parentId }.forEach {
            dao.upsert(it.copy(parentId = note.parentId, updatedAt = now))
        }
    }

    private fun sameNoteContent(a: Entry, b: Entry) =
        a.copy(title = a.title.trim(), createdAt = 0, updatedAt = 0) == b.copy(title = b.title.trim(), createdAt = 0, updatedAt = 0)

    /** Explicit submission remains compatible; INTERACTION has no implicit next* task writes. */
    suspend fun save(entry: Entry, draftId: String? = null, cancelActivityTasks: Boolean? = null, expectedGeneration: Long = generation): Entry = mutate(expectedGeneration) {
        if (draftId != null && dao.sealed(draftId)) return@mutate dao.entry(entry.id) ?: throw DataProblem("该草稿已提交或已放弃，请重新打开记录")
        val previous = dao.entry(entry.id)
        if (previous != null && previous.kind != entry.kind) throw DataProblem("不能更改档案类型")
        val records = dao.entries().associateBy { it.id }.toMutableMap()
        val now = System.currentTimeMillis()
        var saved = entry.copy(title = entry.title.trim(), createdAt = previous?.createdAt ?: entry.createdAt, updatedAt = now)
        if (saved.kind == Kind.TASK) saved = saved.copy(fields = mapOf("status" to "未完成", "priority" to "普通") + saved.fields)
        if (saved.kind == Kind.ACTIVITY) saved = saved.copy(fields = mapOf("status" to "筹备中") + saved.fields)
        if (saved.kind == Kind.INTERACTION && previous == null && saved["content"].isBlank() && dao.attachments().none { it.ownerId == saved.id }) throw DataProblem("写下内容或添加附件后就会保存")
        DataValidation.validateFields(saved); DataValidation.validateRelations(saved, records)
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
        if (saved.kind == Kind.INTERACTION) moveLinkedTasks(saved, records.values, now)
        if (draftId != null) { dao.seal(SealedDraft(draftId)); dao.deleteDraft(draftId); dao.deleteNoteSession(draftId) }
        saved
    }

    /** Read TASK now; a title-only patch preserves a separately changed deadline and status. */
    suspend fun createTaskIfAbsent(entry: Entry, expectedGeneration: Long = generation): Entry = mutate(expectedGeneration) {
        if (entry.kind != Kind.TASK) throw DataProblem("此操作仅用于新增跟进")
        val existing = dao.entry(entry.id)
        if (existing != null) {
            if (existing.kind != Kind.TASK || existing.sourceId != entry.sourceId) throw DataProblem("跟进标识已被其他内容使用，请重新打开")
            return@mutate existing
        }
        val now = System.currentTimeMillis()
        val task = entry.copy(title = entry.title.trim(), updatedAt = now,
            fields = mapOf("status" to "未完成", "priority" to "普通") + entry.fields)
        DataValidation.validateFields(task)
        DataValidation.validateRelations(task, dao.entries().associateBy { it.id })
        dao.upsert(task); task
    }

    /** Read TASK now; a title-only patch preserves a separately changed deadline and status. */
    suspend fun patchTask(id: String, title: String? = null, fields: Map<String, String> = emptyMap(), expectedGeneration: Long = generation): Entry = mutate(expectedGeneration) {
        val task = dao.entry(id) ?: throw DataProblem("跟进不存在")
        if (task.kind != Kind.TASK) throw DataProblem("此记录不是跟进")
        val updated = task.copy(title = title?.trim() ?: task.title, fields = task.fields + fields, updatedAt = System.currentTimeMillis())
        DataValidation.validateFields(updated); dao.upsert(updated); updated
    }

    /** Generic task forms submit only changes from the version shown when the form opened. */
    suspend fun saveTaskChanges(entry: Entry, original: Entry, expectedGeneration: Long = generation): Entry = mutate(expectedGeneration) {
        if (entry.id != original.id || entry.kind != Kind.TASK || original.kind != Kind.TASK || entry.sourceId != original.sourceId) throw DataProblem("跟进编辑来源无效，请重新打开")
        val current = dao.entry(entry.id) ?: throw DataProblem("跟进不存在")
        if (current.kind != Kind.TASK) throw DataProblem("此记录不是跟进")
        val changes = (entry.fields.keys + original.fields.keys).filter { entry[it] != original[it] }.associateWith { entry[it] }
        val updated = current.copy(
            title = if (entry.title.trim() != original.title.trim()) entry.title.trim() else current.title,
            parentId = if (entry.parentId != original.parentId) entry.parentId else current.parentId,
            fields = current.fields + changes,
            updatedAt = System.currentTimeMillis(),
        )
        DataValidation.validateFields(updated)
        DataValidation.validateRelations(updated, dao.entries().associateBy { it.id })
        dao.upsert(updated); updated
    }

    suspend fun saveDraft(draft: Draft, expectedGeneration: Long = generation): Boolean = mutate(expectedGeneration) {
        DataValidation.requireSafeId(draft.id)
        if (dao.sealed(draft.id)) return@mutate false
        if (draft.isNoteBuffer && dao.noteSession(draft.entry.id)?.sessionId != draft.id) return@mutate false
        val old = dao.draft(draft.id)
        if (old != null && old.entry.id != draft.entry.id) throw DataProblem("草稿与记录不匹配")
        if (old != null && old.revision == draft.revision) return@mutate old.entry == draft.entry
        if (old != null && old.revision > draft.revision) return@mutate false
        if (draft.revision < 0) throw DataProblem("草稿版本无效")
        DataValidation.validateFields(draft.entry, draft = true)
        DataValidation.validateRelations(draft.entry, dao.entries().associateBy { it.id }, draft = true)
        dao.upsert(draft.copy(updatedAt = System.currentTimeMillis())); true
    }

    suspend fun migrateLegacyDrafts(expectedGeneration: Long = generation): LegacyMigrationResult = mutate(expectedGeneration) {
        val old = snapshotUnlocked()
        val result = LegacyNotes.convert(old)
        if (result.snapshot != old) {
            dao.upsertEntries(result.snapshot.entries)
            val remaining = result.snapshot.drafts.map { it.id }.toSet()
            old.drafts.filter { it.id !in remaining }.forEach { dao.deleteDraft(it.id) }
            dao.upsertDrafts(result.snapshot.drafts)
            dao.sealAll(result.snapshot.sealedDraftIds.map(::SealedDraft))
        }
        result
    }

    suspend fun discardDraft(id: String, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val draft = dao.draft(id)
        dao.seal(SealedDraft(id)); dao.deleteDraft(id); dao.deleteNoteSession(id)
        if (draft != null && dao.entry(draft.entry.id) == null && dao.drafts().none { it.entry.id == draft.entry.id }) {
            dao.attachments().filter { it.ownerId == draft.entry.id }.forEach { dao.deleteAttachment(it.id) }
        }
    }

    suspend fun archive(id: String, archived: Boolean, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val value = dao.entry(id) ?: throw DataProblem("档案不存在")
        if (value.kind !in setOf(Kind.ORGANIZATION, Kind.CUSTOMER)) throw DataProblem("此记录不支持归档")
        dao.upsert(value.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setTaskStatus(id: String, status: String, note: String? = null, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val task = dao.entry(id) ?: throw DataProblem("待办不存在")
        if (task.kind != Kind.TASK || status !in setOf("未完成", "已完成", "已取消")) throw DataProblem("待办状态无效")
        val patch = mapOf("status" to status) + if (note == null) emptyMap() else mapOf("completionNote" to note)
        dao.upsert(task.copy(fields = task.fields + patch, updatedAt = System.currentTimeMillis()))
    }

    suspend fun cancelActivity(id: String, cancelTasks: Boolean, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val activity = dao.entry(id) ?: throw DataProblem("活动不存在")
        if (activity.kind != Kind.ACTIVITY) throw DataProblem("此记录不是活动")
        if (activity["status"] == "已完成") throw DataProblem("已完成活动保留复盘，不再取消")
        val now = System.currentTimeMillis()
        dao.upsert(activity.copy(fields = activity.fields + ("status" to "已取消"), updatedAt = now))
        if (cancelTasks) dao.entries().filter { it.kind == Kind.TASK && it.parentId == id && it["status"].ifBlank { "未完成" } == "未完成" }.forEach {
            dao.upsert(it.copy(fields = it.fields + ("status" to "已取消"), updatedAt = now))
        }
    }

    suspend fun deleteEntry(id: String, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        val snapshot = snapshotUnlocked()
        val entry = snapshot.find(id) ?: return@mutate
        if (snapshot.entries.any { it.parentId == id || it.sourceId == id || it["linkedOrganization"] == id || it["linkedActivity"] == id || it["organizationId"] == id } || snapshot.drafts.any { it.entry.id == id || it.entry.parentId == id || it.entry.sourceId == id }) throw DataProblem("此档案有历史记录或待办，请使用归档；相关内容会继续保留")
        if (snapshot.attachments.any { it.ownerId == id }) throw DataProblem("请先确认并移除该记录的附件，再删除记录")
        if (entry.kind == Kind.PROFILE) throw DataProblem("本人资料可编辑，不能删除")
        dao.deleteEntry(id)
    }

    suspend fun importAttachment(ownerId: String, uri: Uri, expectedGeneration: Long = generation): Attachment = withContext(Dispatchers.IO) { mutationMutex.withLock {
        requireGeneration(expectedGeneration)
        val snapshot = snapshot()
        if (snapshot.entries.none { it.id == ownerId } && snapshot.drafts.none { it.entry.id == ownerId }) throw DataProblem("编辑内容尚未准备好，请稍后再添加附件")
        val attachment = try { AttachmentFiles.import(appContext, filesRoot, ownerId, uri) }
        catch (e: DataProblem) { throw e } catch (e: Exception) { throw DataProblem("无法读取附件，已填写内容仍保留，请重试", e) }
        beforeAttachmentCommit?.invoke()
        requireGeneration(expectedGeneration)
        val existing = snapshot.attachments.find { it.ownerId == ownerId && it.sha256 == attachment.sha256 && it.name == attachment.name }
        if (existing != null) existing else { dao.upsert(attachment); attachment }
    } }

    suspend fun removeAttachment(id: String, expectedGeneration: Long = generation) = mutate(expectedGeneration) {
        dao.deleteAttachment(id)
    }
    fun attachmentFile(attachment: Attachment): File = AttachmentFiles.checked(filesRoot, attachment)

    suspend fun <T> withConsistentSnapshot(block: suspend (Snapshot) -> T): T = withContext(Dispatchers.IO) {
        mutationMutex.withLock { block(snapshot()) }
    }

    internal suspend fun replaceSnapshot(snapshot: Snapshot) { database.withTransaction {
        val old = snapshotUnlocked()
        dao.rollback(RestoreRollback(snapshotJson = dataJson.encodeToString(old), createdAt = System.currentTimeMillis()))
        dao.clearAttachments(); dao.clearDrafts(); dao.clearEntries(); dao.clearSeals(); dao.clearNoteSessions()
        dao.upsertEntries(snapshot.entries); dao.upsertDrafts(snapshot.drafts); dao.upsertAttachments(snapshot.attachments)
        dao.sealAll(snapshot.sealedDraftIds.map(::SealedDraft))
    } }

    suspend fun rollbackLastRestore(): Boolean {
        val nextGeneration = advanceGeneration()
        return withContext(Dispatchers.IO) { mutationMutex.withLock {
            requireGeneration(nextGeneration)
            val rollback = dao.rollback() ?: return@withLock false
            val snapshot = LegacyNotes.convert(dataJson.decodeFromString<Snapshot>(rollback.snapshotJson)).snapshot
            DataValidation.validateSnapshot(snapshot)
            snapshot.attachments.forEach { AttachmentFiles.checked(filesRoot, it) }
            replaceSnapshot(snapshot); true
        } }
    }
    suspend fun hasRollback(): Boolean = withContext(Dispatchers.IO) { dao.rollback() != null }
    fun close() { database.close() }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) { instance ?: Repository(context).also { instance = it } }
    }
}
