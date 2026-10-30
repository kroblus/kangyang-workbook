package cn.kangyang.workbook

import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.storage.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BackupAndExportTest {
    private val password = "isolated-test-passphrase"
    private fun fixture(): Snapshot {
        val org = Entry(id = "org", kind = Kind.ORGANIZATION, title = "示例·青禾中心", fields = mapOf("type" to "养老机构", "nature" to "暂不清楚"))
        val customer = Entry(id = "family", kind = Kind.CUSTOMER, title = "示例家庭")
        val contact = Entry(id = "contact", kind = Kind.CONTACT, title = "示例女儿", parentId = customer.id, fields = mapOf("phone" to "000-0000", "role" to "女儿"))
        val recipient = Entry(id = "recipient", kind = Kind.RECIPIENT, title = "示例母亲", parentId = customer.id, fields = mapOf("care" to "仅测试照护信息"))
        val interaction = Entry(id = "interaction", kind = Kind.INTERACTION, title = "=SUM(1,2)", parentId = customer.id,
            fields = mapOf("content" to "中文,包含\"引号\"\n下一段", "conclusion" to "待确认", "nextStep" to "示例回访"))
        val task = Entry(id = "task", kind = Kind.TASK, title = "回访", parentId = customer.id, sourceId = interaction.id, fields = mapOf("status" to "未完成"))
        return Snapshot(entries = listOf(org, customer, contact, recipient, interaction, task))
    }

    @Test fun passwordBackupRoundTripRejectsWrongPasswordAndTampering() {
        val snapshot = fixture()
        val bytes = BackupCodec.encode(BackupManifest(appVersion = "1.0.0", createdAt = 1, snapshot = snapshot), emptyMap(), password)
        assertEquals(snapshot, BackupCodec.decode(bytes, password).snapshot)
        val privateText = "示例·青禾中心".toByteArray(Charsets.UTF_8)
        assertFalse((0..bytes.size - privateText.size).any { offset -> privateText.indices.all { bytes[offset + it] == privateText[it] } })
        assertThrows(DataProblem::class.java) { BackupCodec.decode(bytes, "wrong-password") }
        val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(DataProblem::class.java) { BackupCodec.decode(damaged, password) }
        val oversizedKdf = bytes.copyOf().also { it[8] = 0x7f; it[9] = 0xff.toByte(); it[10] = 0xff.toByte(); it[11] = 0xff.toByte() }
        assertThrows(DataProblem::class.java) { BackupCodec.decode(oversizedKdf, password) }
    }

    @Test fun lookupIndexesLeaveBackupJsonUnchangedAndCopiesUseTheirOwnData() {
        val snapshot = fixture()
        val manifest = BackupManifest(appVersion = "1.1.0", createdAt = 1, snapshot = snapshot)
        val beforeLookup = dataJson.encodeToString(BackupManifest.serializer(), manifest)
        val family = requireNotNull(snapshot.find("family"))
        snapshot.entriesOf(Kind.CUSTOMER)
        snapshot.childrenOf(family.id)
        snapshot.sourcedFrom("interaction")
        snapshot.attachmentsFor("interaction")
        val afterLookup = dataJson.encodeToString(BackupManifest.serializer(), manifest)
        assertEquals(beforeLookup, afterLookup)
        assertEquals(setOf("entries", "drafts", "attachments", "sealedDraftIds"),
            dataJson.parseToJsonElement(afterLookup).jsonObject.getValue("snapshot").jsonObject.keys)
        val restored = dataJson.decodeFromString(BackupManifest.serializer(), afterLookup).snapshot
        assertEquals(snapshot, restored)
        assertEquals(family, restored.find(family.id))

        val renamed = family.copy(title = "更新后的家庭称呼")
        val added = Entry(id = "new-contact", kind = Kind.CONTACT, title = "新增联系人", parentId = family.id)
        val copied = snapshot.copy(entries = snapshot.entries.map { if (it.id == family.id) renamed else it } + added)
        assertEquals(renamed, copied.find(family.id))
        assertEquals(added, copied.find(added.id))
        assertTrue(copied.childrenOf(family.id).contains(added))
        assertEquals(family, snapshot.find(family.id))
        assertNull(snapshot.find(added.id))
    }

    @Test fun backupRejectsFutureSchemaAbnormalPathsOrMissingAttachment() {
        val snapshot = fixture()
        val future = BackupCodec.encode(BackupManifest(schemaVersion = 3, appVersion = "future", createdAt = 1, snapshot = snapshot), emptyMap(), password)
        assertThrows(DataProblem::class.java) { BackupCodec.decode(future, password) }
        val traversal = BackupCodec.encode(BackupManifest(appVersion = "1", createdAt = 1, snapshot = snapshot), mapOf("../outside" to byteArrayOf(1)), password)
        assertThrows(DataProblem::class.java) { BackupCodec.decode(traversal, password) }
        val hash = AttachmentFiles.sha256(byteArrayOf(42))
        val withAttachment = snapshot.copy(attachments = listOf(Attachment(id = "attach", ownerId = "interaction", name = "示例.txt", mime = "text/plain", relativePath = "attachments/$hash.bin", size = 1, sha256 = hash)))
        val missing = BackupCodec.encode(BackupManifest(appVersion = "1", createdAt = 1, snapshot = withAttachment), emptyMap(), password)
        assertThrows(DataProblem::class.java) { BackupCodec.decode(missing, password) }
        val corruptContent = BackupCodec.encode(BackupManifest(appVersion = "1", createdAt = 1, snapshot = withAttachment), mapOf("attachments/$hash.bin" to byteArrayOf(43)), password)
        assertThrows(DataProblem::class.java) { BackupCodec.decode(corruptContent, password) }
    }

    @Test fun csvPreservesChineseMultilineAndNeutralizesFormulasWhileExcludingSensitiveDefaults() {
        val snapshot = fixture()
        val records = snapshot.entries.filter { it.kind == Kind.INTERACTION }
        val csv = Exports.csv(snapshot, records, includeBody = true)
        assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), csv.copyOfRange(0, 3))
        val text = csv.copyOfRange(3, csv.size).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"'=SUM(1,2)\""))
        assertTrue(text.contains("\"中文,包含\"\"引号\"\"\n下一段\""))
        assertFalse(text.contains("000-0000")); assertFalse(text.contains("仅测试照护信息"))
        assertFalse(Exports.csv(snapshot, records).toString(Charsets.UTF_8).contains("下一段"))
        assertFalse(Exports.summary(records.first(), snapshot).contains("下一段"))
        assertTrue(Exports.summary(records.first(), snapshot, includeBody = true).contains("下一段"))
        assertTrue(Exports.csv(snapshot, records, includeSensitive = true).toString(Charsets.UTF_8).contains("000-0000"))
        listOf("=CMD()", "+1", "-1", "@SUM()", "  =SUM()", "\t=SUM()", "\r\n=1").forEach { assertTrue(Exports.csvCell(it).startsWith("\"'")) }
        assertEquals("\"00123\"", Exports.csvCell("00123"))
    }

    @Test fun relationValidationRejectsDanglingAndWrongTypeReferencesAndPreservesDistinctRoles() {
        val snapshot = fixture()
        DataValidation.validateSnapshot(snapshot)
        val interaction = snapshot.find("interaction")!!
        assertThrows(DataProblem::class.java) { DataValidation.validateSnapshot(snapshot.copy(entries = snapshot.entries.filter { it.id != "family" })) }
        assertThrows(DataProblem::class.java) { DataValidation.validateSnapshot(snapshot.copy(entries = snapshot.entries + interaction)) }
        assertThrows(DataProblem::class.java) { DataValidation.validateSnapshot(snapshot.copy(entries = snapshot.entries.map { if (it.id == "recipient") it.copy(parentId = "org") else it })) }
        assertThrows(DataProblem::class.java) { DataValidation.validateSnapshot(snapshot.copy(entries = snapshot.entries.map { if (it.id == "task") it.copy(parentId = "org") else it })) }
        DataValidation.validateFields(Entry(kind = Kind.CUSTOMER, title = "器具咨询", fields = mapOf("tags" to "康复器具与耗材")))
        DataValidation.validateFields(Entry(kind = Kind.CUSTOMER, title = "培训咨询", fields = mapOf("tags" to "长期照护师培训")))
    }

    @Test fun strictDatesAndFormalActivityValidationStillAllowIncompleteDrafts() {
        val task = Entry(kind = Kind.TASK, title = "跟进", fields = mapOf("date" to "2026-02-30"))
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(task) }
        val activity = Entry(kind = Kind.ACTIVITY, title = "活动", fields = mapOf("start" to "2026-10-29 11:00", "end" to "2026-10-29 10:00"))
        assertThrows(DataProblem::class.java) { DataValidation.validateFields(activity) }
        DataValidation.validateFields(activity, draft = true)
        DataValidation.validateFields(Entry(kind = Kind.INTERACTION, fields = mapOf("content" to "记录", "nextStep" to "", "nextReminder" to "true", "nextDate" to "")))
        val submitted = Draft("draft", Entry(kind = Kind.INTERACTION), 1)
        assertThrows(DataProblem::class.java) { DataValidation.validateSnapshot(Snapshot(drafts = listOf(submitted), sealedDraftIds = listOf("draft"))) }
    }
}
