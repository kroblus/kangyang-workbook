package cn.kangyang.workbook

import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.storage.*
import org.junit.Assert.*
import org.junit.Test

class V11DataContractTest {
    private val at = requireNotNull(TimeRules.parseDateTime("2026-10-29 09:30"))
    private fun note(id: String, body: String = "正文") = Entry(id = id, kind = Kind.INTERACTION,
        occurredAt = at, createdAt = at, updatedAt = at, fields = mapOf("content" to body))

    @Test fun noteTimeHasOneStrictMinuteContractAndNeverAcceptsMismatchedTimestamp() {
        val valid = note("time").copy(fields = mapOf("content" to "记录", "occurredDate" to "2026-10-29", "occurredTime" to "09:30"))
        DataValidation.validateFields(valid)
        assertNull(TimeRules.parseTime("09:30:45"))
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(valid.copy(fields = valid.fields + ("occurredTime" to "09:30:45"))) }
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(valid.copy(occurredAt = at + 60_000)) }
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(valid.copy(fields = valid.fields + ("occurredDate" to "2026-02-30"))) }
        DataValidation.validateFields(note("no-metadata"))
    }

    @Test fun pendingActivityIsFormalAndPortableButPartialOrScheduledDatesRemainValidated() {
        val pending = Entry(id = "pending", kind = Kind.ACTIVITY, title = "先筹备方案",
            fields = mapOf("status" to "筹备中", "plan" to "联系场地，带服务资料", "remindMinutes" to "30"))
        DataValidation.validateFields(pending)
        assertNull(TimeRules.reminderAt(pending))
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(pending.copy(fields = pending.fields + ("status" to "待开展"))) }
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(pending.copy(fields = pending.fields + ("start" to "2026-10-30 09:00"))) }
        val valid = pending.copy(fields = pending.fields + mapOf("start" to "2026-10-30 09:00", "end" to "2026-10-30 10:00", "status" to "待开展"))
        DataValidation.validateFields(valid)
        assertNotNull(TimeRules.reminderAt(valid))
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(valid.copy(fields = valid.fields + ("end" to "2026-10-30 08:00"))) }
        val bytes = BackupCodec.encode(BackupManifest(appVersion = "1.1.0", createdAt = at,
            snapshot = Snapshot(entries = listOf(pending))), emptyMap(), "test-password")
        assertEquals(pending, BackupCodec.decode(bytes, "test-password").snapshot.entries.single())
        assertTrue(Exports.summary(pending, Snapshot(entries = listOf(pending)), includeBody = true).contains("时间待定"))
    }

    @Test fun legacyDraftMigrationIsIdempotentKeepsIdentityAndNeverGeneratesTasks() {
        val new = note("new").copy(title = "手动标题", fields = mapOf("content" to "旧新建正文", "nextStep" to "旧建议", "nextDate" to "2026-11-01"))
        val editOld = note("edit", "原正文")
        val edit = editOld.copy(fields = mapOf("content" to "编辑后正文"))
        val conflictOld = note("conflict", "较新正式版本").copy(updatedAt = at + 20)
        val conflict = note("conflict", "较旧编辑缓冲")
        val invalid = note("invalid").copy(fields = mapOf("content" to "非法日期仍要保留", "date" to "2026-02-30", "time" to "09:30:45"))
        val attachmentOnly = note("attachment", "")
        val bytes = "仅测试附件".toByteArray()
        val sha = AttachmentFiles.sha256(bytes)
        val attachment = Attachment(id = "a", ownerId = attachmentOnly.id, name = "附件验收.txt", mime = "text/plain", relativePath = "attachments/$sha.bin", size = bytes.size.toLong(), sha256 = sha)
        val customer = Entry(id = "customer", kind = Kind.CUSTOMER, title = "客户草稿")
        val original = Snapshot(entries = listOf(editOld, conflictOld), drafts = listOf(new, edit, conflict, invalid, attachmentOnly, customer).map {
            Draft("draft-${it.id}", it, 7, at + 10)
        }, attachments = listOf(attachment))
        val first = LegacyNotes.convert(original)
        assertEquals(3, first.promoted)
        assertEquals(2, first.needsReview)
        assertEquals("手动标题", first.snapshot.find("new")!!.title)
        assertEquals(at, first.snapshot.find("new")!!.occurredAt)
        assertEquals("编辑后正文", first.snapshot.find("edit")!!["content"])
        assertEquals("较新正式版本", first.snapshot.find("conflict")!!["content"])
        assertEquals(attachment, first.snapshot.attachments.single())
        assertNotNull(first.snapshot.find("attachment"))
        assertNull(first.snapshot.find("customer"))
        assertEquals(0, first.snapshot.entries.count { it.kind == Kind.TASK })
        assertEquals(setOf("draft-new", "draft-edit", "draft-attachment"), first.snapshot.sealedDraftIds.toSet())
        assertEquals(first.snapshot, LegacyNotes.convert(first.snapshot).snapshot)
        DataValidation.validateSnapshot(first.snapshot)
    }

    @Test fun genuineV1SemanticsAreValidatedBeforeCompatibleConversion() {
        val original = Snapshot(drafts = listOf(Draft("old-draft", note("old-note"), 7, at + 1)))
        val bytes = BackupCodec.encode(BackupManifest(schemaVersion = 1, appVersion = "1.0.0", createdAt = at, snapshot = original), emptyMap(), "test-password")
        val decoded = BackupCodec.decode(bytes, "test-password")
        assertEquals(1, decoded.sourceSchemaVersion)
        assertEquals("正文", decoded.snapshot.find("old-note")!!["content"])
        assertTrue(decoded.snapshot.drafts.isEmpty())
        assertEquals(listOf("old-draft"), decoded.snapshot.sealedDraftIds)
        val invalidV1 = Snapshot(entries = listOf(note("empty-v1", "")))
        val invalid = BackupCodec.encode(BackupManifest(schemaVersion = 1, appVersion = "1.0.0", createdAt = at, snapshot = invalidV1), emptyMap(), "test-password")
        assertThrows(DataProblem::class.java) { BackupCodec.decode(invalid, "test-password") }
        val inconsistent = note("old-stale-time").copy(fields = mapOf("content" to "旧正式正文", "occurredDate" to "2026-10-29", "occurredTime" to "09:30:45"))
        val oldBytes = BackupCodec.encode(BackupManifest(schemaVersion = 1, appVersion = "1.0.0", createdAt = at,
            snapshot = Snapshot(entries = listOf(inconsistent))), emptyMap(), "test-password")
        val fixed = BackupCodec.decode(oldBytes, "test-password").snapshot.entries.single()
        assertEquals(at, fixed.occurredAt)
        assertEquals("09:30:45", fixed["legacyOccurredTime"])
        assertEquals("09:30", fixed["occurredTime"])
    }

    @Test fun emptyNotesDoNotKeepDerivedSearchTitlesOrLeakBodyWhenExportIsOff() {
        val text = note("one", "正文独有关键词\n另一行")
        assertEquals("正文独有关键词", text.displayTitle(Snapshot()))
        val empty = text.copy(fields = mapOf("content" to ""))
        assertEquals("空白记录", empty.displayTitle(Snapshot()))
        DataValidation.validateSnapshot(Snapshot(entries = listOf(empty)))
        val export = Exports.csv(Snapshot(entries = listOf(text)), listOf(text)).toString(Charsets.UTF_8)
        assertFalse(export.contains("正文独有关键词"))
        assertTrue(Exports.csv(Snapshot(entries = listOf(text)), listOf(text), includeBody = true).toString(Charsets.UTF_8).contains("正文独有关键词"))
    }

    @Test fun foregroundWakeupChoosesDeadlineThenShanghaiMidnight() {
        val due = requireNotNull(TimeRules.parseDateTime("2026-10-29 10:00"))
        val task = Entry(kind = Kind.TASK, title = "联系", fields = mapOf("date" to "2026-10-29", "time" to "10:00"))
        assertEquals(due + 1, TimeRules.nextRefreshAt(listOf(task), at))
        val midnight = requireNotNull(TimeRules.parseDateTime("2026-10-30 00:00"))
        assertEquals(midnight, TimeRules.nextRefreshAt(listOf(task), due + 1))
        assertEquals(midnight, TimeRules.nextRefreshAt(listOf(task.copy(fields = task.fields + ("status" to "已完成"))), at))
    }
}
