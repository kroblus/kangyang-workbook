package cn.kangyang.workbook

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.storage.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/filesystem regressions in uniquely named QA databases, never the personal database. */
@RunWith(AndroidJUnit4::class)
class RepositoryV11Test {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repo(): Repository {
        check(context.packageName.endsWith(".qa"))
        val id = UUID.randomUUID().toString()
        return Repository(context, "v11-safety-$id.db", "v11-safety-$id")
    }
    private fun note(body: String = "最短中文内容") = Entry(kind = Kind.INTERACTION, fields = mapOf("content" to body))
    private suspend fun published(r: Repository, e: Entry, session: String = UUID.randomUUID().toString()): Entry {
        r.beginNoteSession(e.id, session)
        return requireNotNull(r.saveNoteRevision(e, session, 1))
    }
    private fun fileUri(name: String = "文件名检索.txt"): android.net.Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}-$name").apply { writeText("合成附件原始内容\n第二行") }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    @Test fun continuousRevisionCommitRejectsStaleWritesAndClearingKeepsIdentity(): Unit = runBlocking {
        val r = repo()
        try {
            val e = note("正文独有词待删除"); val session = "editor-one"
            r.beginNoteSession(e.id, session)
            val first = requireNotNull(r.saveNoteRevision(e, session, 1))
            assertEquals(first, r.saveNoteRevision(first, session, 1)) // retry after DB metadata returned
            val latest = first.copy(fields = first.fields + ("content" to "最新第三版本"))
            r.saveNoteRevision(latest, session, 3)
            assertTrue(runCatching { r.saveNoteRevision(first, session, 2) }.exceptionOrNull() is DataProblem)
            assertEquals("最新第三版本", r.snapshot().find(e.id)!!["content"])
            val cleared = requireNotNull(r.saveNoteRevision(latest.copy(fields = latest.fields + ("content" to "")), session, 4))
            assertEquals(first.id, cleared.id); assertEquals(first.createdAt, cleared.createdAt)
            assertEquals(first.occurredAt, cleared.occurredAt); assertEquals("", cleared.title)
            assertEquals("空白记录", cleared.displayTitle(r.snapshot()))
            DataValidation.validateSnapshot(r.snapshot())
            r.finishNoteSession(session)
            assertTrue(r.snapshot().drafts.isEmpty())
            assertTrue(runCatching { r.saveNoteRevision(first, session, 5) }.exceptionOrNull() is DataProblem)
            assertFalse(r.saveDraft(Draft(session, first, 6)))
            val empty = note("   ")
            r.beginNoteSession(empty.id, "empty-editor")
            assertNull(r.saveNoteRevision(empty, "empty-editor", 0))
            r.finishNoteSession("empty-editor")
            assertNull(r.snapshot().find(empty.id))
        } finally { r.close() }
    }

    @Test fun taskTruthPreservesIndependentDateReminderStatusAndCompletionNote(): Unit = runBlocking {
        val r = repo()
        try {
            val e = note().copy(fields = mapOf("content" to "沟通", "nextStep" to "旧建议", "nextDate" to "2026-11-01", "nextReminder" to "true"))
            val n = published(r, e, "session")
            assertEquals(0, r.snapshot().entries.count { it.kind == Kind.TASK })
            val task = r.save(Entry(id = "followup-${n.id}", kind = Kind.TASK, title = "联系", sourceId = n.id,
                fields = mapOf("date" to "2026-11-01", "reminder" to "true")))
            r.patchTask(task.id, fields = mapOf("date" to "2026-11-05", "reminder" to "false"))
            r.createTaskIfAbsent(task) // duplicate-submit retry must not reset the independent changes
            assertEquals("2026-11-05", r.snapshot().find(task.id)!!["date"])
            r.saveNoteRevision(n.copy(fields = n.fields + mapOf("content" to "改正文", "nextStep" to "修改旧下一步文字")), "session", 2)
            r.patchTask(task.id, title = "只改跟进文字")
            r.setTaskStatus(task.id, "已完成", "原完成备注必须保留")
            r.saveTaskChanges(task.copy(title = "旧表单只修改名称"), task)
            assertEquals("已完成", r.snapshot().find(task.id)!!["status"])
            assertEquals("2026-11-05", r.snapshot().find(task.id)!!["date"])
            r.patchTask(task.id, title = "完成后修改文字")
            r.setTaskStatus(task.id, "未完成"); r.setTaskStatus(task.id, "已完成")
            val current = r.snapshot().find(task.id)!!
            assertEquals("2026-11-05", current["date"]); assertEquals("false", current["reminder"])
            assertEquals("已完成", current["status"]); assertEquals("原完成备注必须保留", current["completionNote"])
            val beforeInvalidRelation = r.snapshot().find(n.id)!!
            assertTrue(runCatching {
                r.saveNoteRevision(beforeInvalidRelation.copy(parentId = "missing-object"), "session", 3)
            }.exceptionOrNull() is DataProblem)
            assertEquals(beforeInvalidRelation, r.snapshot().find(n.id))
            val org = r.save(Entry(kind = Kind.ORGANIZATION, title = "示例·后补关联", fields = mapOf("type" to "养老机构")))
            r.saveNoteRevision(r.snapshot().find(n.id)!!.copy(parentId = org.id), "session", 3)
            val moved = r.snapshot().find(task.id)!!
            assertEquals(org.id, moved.parentId); assertEquals(current.fields, moved.fields)
            r.setTaskStatus(task.id, "已完成", "")
            assertEquals("", r.snapshot().find(task.id)!!["completionNote"])
        } finally { r.close() }
    }

    @Test fun reminderFlowIgnoresBodySavesAndKeepsTaskChanges(): Unit = runBlocking {
        val r = repo()
        val updates = Channel<Snapshot>(Channel.UNLIMITED)
        val collector = launch { r.reminderState.collect { updates.send(it) } }
        try {
            assertTrue(withTimeout(5000) { updates.receive() }.entries.isEmpty())
            val n = published(r, note("正文不需要重建提醒"), "reminder-flow-note")
            assertNull(withTimeoutOrNull(500) { updates.receive() })
            val task = r.createTaskIfAbsent(Entry(kind = Kind.TASK, title = "明天联系", sourceId = n.id,
                fields = mapOf("date" to "2026-11-01", "reminder" to "true")))
            assertEquals(listOf(task), withTimeout(5000) { updates.receive() }.entries)
            r.saveNoteRevision(n.copy(fields = n.fields + ("content" to "继续补充正文")), "reminder-flow-note", 2)
            assertNull(withTimeoutOrNull(500) { updates.receive() })
            assertEquals(listOf(task), r.reminderSnapshot().entries)
            val changed = r.patchTask(task.id, fields = mapOf("date" to "2026-11-05"))
            assertEquals(listOf(changed), withTimeout(5000) { updates.receive() }.entries)
            assertTrue(r.reminderSnapshot().drafts.isEmpty())
        } finally {
            collector.cancelAndJoin(); updates.close(); r.close()
        }
    }

    @Test fun attachmentOnlyHasStableOwnerSurvivesBackupAndFailedImportLeavesNoVisibleNote(): Unit = runBlocking {
        val source = repo(); val restored = repo()
        try {
            val e = note(""); val s = "attachment-session"
            source.beginNoteSession(e.id, s)
            source.saveDraft(Draft(s, e, 0, isNoteBuffer = true))
            val a = source.importAttachment(e.id, fileUri())
            val saved = requireNotNull(source.saveNoteRevision(e, s, 1))
            assertEquals(e.id, a.ownerId); assertEquals(a.name, saved.displayTitle(source.snapshot()))
            source.finishNoteSession(s)
            val sourceSnapshot = source.snapshot()
            val bytes = BackupManager(source, context).export("test-v11-passphrase")
            val manager = BackupManager(restored, context)
            manager.restore(manager.inspect(bytes, "test-v11-passphrase"))
            assertEquals(sourceSnapshot, restored.snapshot())
            assertEquals(a.sha256, AttachmentFiles.sha256(restored.attachmentFile(a)))
            val failed = note(""); source.beginNoteSession(failed.id, "failed-attachment")
            source.saveDraft(Draft("failed-attachment", failed, 0, isNoteBuffer = true))
            assertTrue(runCatching { source.importAttachment(failed.id, android.net.Uri.parse("content://missing.invalid/file")) }.isFailure)
            assertNull(source.snapshot().find(failed.id))
            assertTrue(source.snapshot().attachments.none { it.ownerId == failed.id })
        } finally { source.close(); restored.close() }
    }

    @Test fun restoreRejectsSlowNoteAndOldDraftAndRollbackStartsAnotherGeneration(): Unit = runBlocking {
        val r = repo()
        try {
            val baseline = r.save(Entry(kind = Kind.CUSTOMER, title = "示例·备份保留资料"))
            val manager = BackupManager(r, context)
            val prepared = manager.inspect(manager.export("test-v11-passphrase"), "test-v11-passphrase")
            val e = note("恢复前的旧编辑内容"); val generation = r.generation
            r.beginNoteSession(e.id, "slow-session", generation)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            r.beforeNoteCommit = { started.complete(Unit); release.await() }
            val saving = async(Dispatchers.IO) { runCatching { r.saveNoteRevision(e, "slow-session", 1, generation) } }
            started.await()
            val restoring = async(Dispatchers.IO) { manager.restore(prepared) }
            withTimeout(5000) { while (r.generation == generation) delay(5) }
            release.complete(Unit)
            assertTrue(saving.await().exceptionOrNull() is DataProblem)
            restoring.await(); r.beforeNoteCommit = null
            assertEquals(listOf(baseline.id), r.snapshot().entries.map { it.id })
            assertTrue(runCatching { r.saveDraft(Draft("old-customer", Entry(kind = Kind.CUSTOMER, title = "客户A"), 1), generation) }.exceptionOrNull() is DataProblem)
            val restoredGeneration = r.generation
            assertTrue(r.rollbackLastRestore())
            assertTrue(r.generation > restoredGeneration)
            assertTrue(runCatching { r.saveNoteRevision(e, "slow-session", 2, generation) }.exceptionOrNull() is DataProblem)
            assertNull(r.snapshot().find(e.id))
        } finally { r.close() }
    }

    @Test fun interruptedAttachmentOnlyPublicationIsRecoverableOnNextOpen(): Unit = runBlocking {
        val r = repo()
        try {
            val empty = note(""); val session = "import-before-note"
            r.beginNoteSession(empty.id, session)
            r.saveDraft(Draft(session, empty, 0, isNoteBuffer = true))
            val a = r.importAttachment(empty.id, fileUri("回收附件.txt"))
            assertNull(r.snapshot().find(empty.id))
            // Equivalent durable state to a process ending between these two commits.
            r.migrateLegacyDrafts()
            val recovered = requireNotNull(r.snapshot().find(empty.id))
            assertEquals(a.name, recovered.displayTitle(r.snapshot()))
            assertTrue(r.snapshot().drafts.isEmpty())
            assertTrue(session in r.snapshot().sealedDraftIds)
            r.migrateLegacyDrafts()
            assertEquals(1, r.snapshot().entries.size)
            assertEquals(a.sha256, AttachmentFiles.sha256(r.attachmentFile(a)))
        } finally { r.close() }
    }

    @Test fun finishingOrReplacingAnEditorNeverOrphansImportedAttachments(): Unit = runBlocking {
        val r = repo()
        try {
            val closing = note("")
            r.beginNoteSession(closing.id, "close-during-import")
            assertNull(r.saveNoteRevision(closing, "close-during-import", 0))
            val first = r.importAttachment(closing.id, fileUri("返回时附件.txt"))
            // Back captured the same empty revision before the attachment callback incremented it.
            assertNull(r.saveNoteRevision(closing, "close-during-import", 0))
            r.finishNoteSession("close-during-import")
            assertEquals(first.name, r.snapshot().find(closing.id)!!.displayTitle(r.snapshot()))
            assertTrue("close-during-import" in r.snapshot().sealedDraftIds)

            val replacing = note("")
            r.beginNoteSession(replacing.id, "replaced-import")
            r.saveNoteRevision(replacing, "replaced-import", 0)
            val second = r.importAttachment(replacing.id, fileUri("切换时附件.txt"))
            r.beginNoteSession(replacing.id, "new-editor")
            assertNotNull(r.snapshot().find(replacing.id))
            assertEquals(replacing.id, second.ownerId)
            assertTrue("replaced-import" in r.snapshot().sealedDraftIds)
            r.finishNoteSession("new-editor")

            val invalid = note("").copy(fields = mapOf("content" to "", "occurredDate" to "2026-02-30", "occurredTime" to "09:30:45"))
            r.beginNoteSession(invalid.id, "invalid-import")
            r.saveNoteRevision(invalid, "invalid-import", 0)
            val third = r.importAttachment(invalid.id, fileUri("待修正附件.txt"))
            r.finishNoteSession("invalid-import")
            val retained = r.snapshot().drafts.single { it.id == "invalid-import" }
            assertFalse(retained.isNoteBuffer); assertTrue(retained.recoveryReason.isNotBlank())
            assertFalse("invalid-import" in r.snapshot().sealedDraftIds)
            assertNull(r.snapshot().find(invalid.id))
            assertFalse(r.saveDraft(Draft("invalid-import", invalid, 1, isNoteBuffer = true)))
            assertEquals(third.sha256, AttachmentFiles.sha256(r.attachmentFile(third)))
            DataValidation.validateSnapshot(r.snapshot())
            val manager = BackupManager(r, context)
            val backup = manager.inspect(manager.export("test-v11-passphrase"), "test-v11-passphrase")
            assertEquals(3, backup.snapshot.attachments.size)
            assertTrue(backup.snapshot.drafts.single().recoveryReason.isNotBlank())
        } finally { r.close() }
    }

    @Test fun restoreRejectsSlowAttachmentBeforeMetadataPublication(): Unit = runBlocking {
        val r = repo()
        try {
            val manager = BackupManager(r, context)
            val prepared = manager.inspect(manager.export("test-v11-passphrase"), "test-v11-passphrase")
            val e = note(""); val generation = r.generation
            r.beginNoteSession(e.id, "slow-attachment", generation)
            r.saveDraft(Draft("slow-attachment", e, 0, isNoteBuffer = true), generation)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            r.beforeAttachmentCommit = { started.complete(Unit); release.await() }
            val importing = async(Dispatchers.IO) { runCatching { r.importAttachment(e.id, fileUri("慢附件.txt"), generation) } }
            started.await()
            val restoring = async(Dispatchers.IO) { manager.restore(prepared) }
            withTimeout(5000) { while (r.generation == generation) delay(5) }
            release.complete(Unit)
            assertTrue(importing.await().exceptionOrNull() is DataProblem)
            restoring.await()
            assertTrue(r.snapshot().attachments.isEmpty()); assertTrue(r.snapshot().drafts.isEmpty())
            assertTrue(r.snapshot().entries.isEmpty())
        } finally { r.close() }
    }

    @Test fun failedWriteHasNoFalseCommitAndSameRevisionCanRetry(): Unit = runBlocking {
        val r = repo()
        try {
            val original = note("原来内容"); val first = published(r, original, "retry-session")
            val update = first.copy(fields = mapOf("content" to "修改后内容"))
            r.beforeNoteCommit = { throw IOException("Injected disk write failure") }
            assertTrue(runCatching { r.saveNoteRevision(update, "retry-session", 2) }.isFailure)
            assertEquals("原来内容", r.snapshot().find(first.id)!!["content"])
            r.beforeNoteCommit = null
            r.saveNoteRevision(update, "retry-session", 2)
            assertEquals("修改后内容", r.snapshot().find(first.id)!!["content"])
        } finally { r.close() }
    }

    @Test fun explicitConflictRecoveryKeepsBothVersionsAndDoesNotDuplicateFormalNote(): Unit = runBlocking {
        val r = repo()
        try {
            val existing = r.save(note("正式较新内容"))
            val conflicting = existing.copy(updatedAt = existing.updatedAt - 100, fields = mapOf("content" to "冲突草稿内容"))
            r.saveDraft(Draft("conflict-draft", conflicting, 7))
            r.migrateLegacyDrafts()
            assertTrue(r.snapshot().drafts.single().recoveryReason.isNotBlank())
            r.beginNoteSession(existing.id, "conflict-draft")
            r.saveNoteRevision(conflicting, "conflict-draft", 8)
            r.finishNoteSession("conflict-draft")
            assertEquals("冲突草稿内容", r.snapshot().find(existing.id)!!["content"])
            assertEquals(1, r.snapshot().entries.size)
            assertEquals("正式较新内容", r.snapshot().drafts.single().entry["content"])
            assertTrue(r.snapshot().drafts.single().recoveryReason.isNotBlank())
            r.migrateLegacyDrafts()
            assertEquals("冲突草稿内容", r.snapshot().find(existing.id)!!["content"])
        } finally { r.close() }
    }

    @Test fun canceledPendingActivityRemainsValidForBackupAndIndependentPreparations(): Unit = runBlocking {
        val r = repo()
        try {
            val pending = r.save(Entry(kind = Kind.ACTIVITY, title = "示例·时间待定筹备", fields = mapOf("status" to "筹备中", "plan" to "先准备方案")))
            val task = r.createTaskIfAbsent(Entry(kind = Kind.TASK, title = "准备物料", parentId = pending.id, fields = mapOf("date" to "2026-11-03")))
            assertNull(TimeRules.reminderAt(pending))
            r.cancelActivity(pending.id, false)
            val canceled = r.snapshot().find(pending.id)!!
            assertEquals("已取消", canceled["status"])
            assertNull(TimeRules.reminderAt(canceled))
            assertEquals("2026-11-03", r.snapshot().find(task.id)!!["date"])
            assertEquals("未完成", r.snapshot().find(task.id)!!["status"])
            DataValidation.validateSnapshot(r.snapshot())
            val manager = BackupManager(r, context)
            val restored = manager.inspect(manager.export("test-v11-passphrase"), "test-v11-passphrase")
            assertEquals(canceled, restored.snapshot.find(pending.id))
        } finally { r.close() }
    }
}
