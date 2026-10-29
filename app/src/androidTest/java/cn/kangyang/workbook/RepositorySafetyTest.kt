package cn.kangyang.workbook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.storage.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositorySafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repo(): Repository {
        check(context.packageName.endsWith(".qa")) { "Data safety tests require isolated .qa package" }
        val id = UUID.randomUUID().toString()
        return Repository(context, "safety-$id.db", "safety-$id")
    }

    @Test fun stableSubmissionDraftSealingIndependentRescheduleAndArchive() = runBlocking {
        val repository = repo()
        try {
            val org = repository.save(Entry(kind = Kind.ORGANIZATION, title = "示例·青禾养老中心", fields = mapOf("type" to "养老机构")))
            repository.save(Entry(kind = Kind.CONTACT, title = "示例·林女士", parentId = org.id, fields = mapOf("primary" to "true")))
            repository.save(Entry(kind = Kind.CONTACT, title = "示例·陈先生", parentId = org.id, fields = mapOf("primary" to "true")))
            val interaction = Entry(kind = Kind.INTERACTION, parentId = org.id, fields = mapOf("content" to "第一段\n第二段", "nextStep" to "三天后回访", "nextDate" to "2026-11-01"))
            val draft = Draft(UUID.randomUUID().toString(), interaction, 1)
            assertTrue(repository.saveDraft(draft)); assertTrue(repository.saveDraft(draft))
            val attachment = AttachmentFiles.write(repository.filesRoot, interaction.id, "归档关系验收.txt", "text/plain", "仅供验证附件归属的示例资料".byteInputStream())
            repository.database.dao().upsert(attachment)
            val saved = repository.save(interaction, draft.id)
            repository.save(interaction, draft.id)
            // V1.1 explicitly confirms a follow-up; body/draft submission is side-effect free.
            repository.createTaskIfAbsent(Entry(id = "followup-${saved.id}", kind = Kind.TASK,
                title = "三天后回访", parentId = saved.parentId, sourceId = saved.id,
                fields = mapOf("date" to "2026-11-01")))
            assertFalse(repository.saveDraft(draft.copy(revision = 2)))
            val one = repository.snapshot()
            assertEquals(1, one.entries.count { it.kind == Kind.INTERACTION })
            assertEquals(1, one.entries.count { it.kind == Kind.TASK })
            assertEquals(1, one.entries.count { it.kind == Kind.CONTACT && it["primary"] == "true" })
            assertTrue(one.drafts.isEmpty())
            val task = one.entries.single { it.kind == Kind.TASK }
            repository.save(task.copy(fields = task.fields + ("date" to "2026-11-05")))
            repository.save(saved.copy(fields = saved.fields + ("content" to "补充正文")))
            assertEquals("2026-11-05", repository.snapshot().find(task.id)!!["date"])
            repository.setTaskStatus(task.id, "已完成", "已联系")
            repository.save(saved.copy(fields = saved.fields + ("nextStep" to "更新标题")))
            assertEquals("已完成", repository.snapshot().find(task.id)!!["status"])
            repository.archive(org.id, true)
            assertTrue(repository.snapshot().find(org.id)!!.archived)
            assertEquals(1, repository.snapshot().entries.count { it.kind == Kind.INTERACTION })
            assertEquals(attachment, repository.snapshot().attachments.single())
            assertEquals(saved.id, repository.snapshot().attachments.single().ownerId)
            assertEquals(attachment.sha256, AttachmentFiles.sha256(repository.attachmentFile(attachment)))
            var refused = false
            try { repository.deleteEntry(org.id) } catch (_: DataProblem) { refused = true }
            assertTrue(refused)
            DataValidation.validateSnapshot(repository.snapshot())
        } finally { repository.close() }
    }

    @Test fun encryptedRoundTripIndependentSpaceRollbackAndPreCommitInterruption() = runBlocking {
        val source = repo(); val target = repo()
        try {
            val org = source.save(Entry(kind = Kind.ORGANIZATION, title = "示例单位", fields = mapOf("type" to "康复医院", "nature" to "暂不清楚")))
            val customer = source.save(Entry(kind = Kind.CUSTOMER, title = "示例家庭"))
            source.save(Entry(kind = Kind.CONTACT, title = "示例女儿", parentId = customer.id, fields = mapOf("role" to "女儿")))
            source.save(Entry(kind = Kind.RECIPIENT, title = "示例母亲", parentId = customer.id))
            val sourceNote = source.save(Entry(kind = Kind.INTERACTION, parentId = org.id, fields = mapOf("content" to "单位拜访", "nextStep" to "再次回访")))
            source.createTaskIfAbsent(Entry(id = "followup-${sourceNote.id}", kind = Kind.TASK, title = "再次回访", parentId = org.id, sourceId = sourceNote.id))
            source.save(Entry(kind = Kind.INTERACTION, parentId = customer.id, fields = mapOf("content" to "客户沟通")))
            source.save(Entry(kind = Kind.ACTIVITY, title = "示例活动", fields = mapOf("start" to "2026-11-10 09:00", "end" to "2026-11-10 11:00", "organizationId" to org.id)))
            val draftEntry = Entry(kind = Kind.INTERACTION, fields = mapOf("content" to "尚未提交的中文草稿"))
            source.saveDraft(Draft(UUID.randomUUID().toString(), draftEntry, 5))
            val file = AttachmentFiles.write(source.filesRoot, draftEntry.id, "示例附件.txt", "text/plain", "附件原始内容".byteInputStream())
            source.database.dao().upsert(file)
            source.save(defaultProfile().copy(fields = defaultProfile().fields + ("appName" to "示例工作簿")))
            val original = source.snapshot()
            val backup = BackupManager(source, context).export("test-backup-passphrase")
            val targetManager = BackupManager(target, context)
            val prepared = targetManager.inspect(backup, "test-backup-passphrase")
            assertTrue(target.snapshot().entries.isEmpty())
            targetManager.restore(prepared)
            assertEquals(normalized(original), normalized(target.snapshot()))
            assertEquals(file.sha256, AttachmentFiles.sha256(target.attachmentFile(file)))
            val stable = target.snapshot()
            try { targetManager.inspect(backup, "wrong-password"); fail("Wrong password accepted") } catch (_: DataProblem) { }
            val corrupt = backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 2).toByte() }
            try { targetManager.inspect(corrupt, "test-backup-passphrase"); fail("Corrupt backup accepted") } catch (_: DataProblem) { }
            assertEquals(normalized(stable), normalized(target.snapshot()))
            assertTrue(target.rollbackLastRestore())
            assertTrue(target.snapshot().entries.isEmpty())
            val old = target.save(Entry(kind = Kind.CUSTOMER, title = "恢复前旧资料"))
            try {
                targetManager.restore(prepared) { phase -> if (phase == "files_staged") throw InterruptedException("Injected interruption before atomic commit") }
                fail("Expected interruption")
            } catch (_: InterruptedException) { }
            assertEquals(listOf(old.id), target.snapshot().entries.map { it.id })
            targetManager.restore(prepared)
            assertEquals(normalized(original), normalized(target.snapshot()))
            assertTrue(target.rollbackLastRestore())
            assertEquals(listOf(old.id), target.snapshot().entries.map { it.id })
        } finally { source.close(); target.close() }
    }

    @Test fun activityCancellationPreservesHistoryAndIndependentTaskDates() = runBlocking {
        val repository = repo()
        try {
            val activity = repository.save(Entry(kind = Kind.ACTIVITY, title = "示例活动", fields = mapOf("start" to "2026-11-10 09:00", "end" to "2026-11-10 11:00", "remindMinutes" to "30")))
            val task = repository.save(Entry(kind = Kind.TASK, title = "准备物料", parentId = activity.id, fields = mapOf("date" to "2026-11-08")))
            repository.save(activity.copy(fields = activity.fields + mapOf("start" to "2026-11-11 09:00", "end" to "2026-11-11 11:00")))
            assertEquals("2026-11-08", repository.snapshot().find(task.id)!!["date"])
            repository.cancelActivity(activity.id, false)
            assertEquals("未完成", repository.snapshot().find(task.id)!!["status"])
            repository.cancelActivity(activity.id, true)
            assertEquals("已取消", repository.snapshot().find(activity.id)!!["status"])
            assertEquals("已取消", repository.snapshot().find(task.id)!!["status"])
            assertEquals(2, repository.snapshot().entries.size)
        } finally { repository.close() }
    }

    private fun normalized(s: Snapshot) = s.copy(entries = s.entries.sortedBy { it.id }, drafts = s.drafts.sortedBy { it.id }, attachments = s.attachments.sortedBy { it.id }, sealedDraftIds = s.sealedDraftIds.sorted())
}
