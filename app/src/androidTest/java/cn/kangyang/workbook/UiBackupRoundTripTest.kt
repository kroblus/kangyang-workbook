package cn.kangyang.workbook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.kangyang.workbook.data.AttachmentFiles
import cn.kangyang.workbook.data.DataValidation
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Repository
import cn.kangyang.workbook.data.Snapshot
import cn.kangyang.workbook.data.dataJson
import cn.kangyang.workbook.storage.BackupManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Consumes the real backup exported through the UI/SAF. The host copies that exact file to
 * externalFiles/evidence/ui-roundtrip.kywb. Only a fresh, UUID-named target is ever restored.
 */
@RunWith(AndroidJUnit4::class)
class UiBackupRoundTripTest {
    @Test fun restoresUiExportIntoIndependentEmptySpaceAndLeavesQaDataUntouched(): Unit = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa")) { "This verification requires the isolated QA application" }
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "evidence").apply { mkdirs() }
        val input = File(evidence, "ui-roundtrip.kywb")
        val result = File(evidence, "ui-backup-roundtrip.txt")
        val report = StringBuilder("UI BACKUP ROUND TRIP\n")
        val token = UUID.randomUUID().toString()
        val targetDatabase = "ui-roundtrip-$token.db"
        val targetStorage = "ui-roundtrip-$token"
        var target: Repository? = null
        try {
            assertTrue("Actual UI/SAF backup file must be supplied by the host", input.isFile)
            assertTrue("Backup must be nonempty and within the supported encrypted package limit", input.length() in 1L..(80L * 1024 * 1024))
            report.appendLine("input=${input.absolutePath}")
            report.appendLine("inputBytes=${input.length()}")
            report.appendLine("inputSHA256=${AttachmentFiles.sha256(input)}")

            val source = Repository.get(context)
            val before = source.snapshot()
            assertBusinessCoverage(before, "source QA database")
            DataValidation.validateSnapshot(before)
            val sourceDigest = digest(before)
            val sourceAttachmentDigests = before.attachments.associate { attachment ->
                attachment.id to AttachmentFiles.sha256(source.attachmentFile(attachment))
            }

            val isolated = Repository(context, targetDatabase, targetStorage)
            target = isolated
            val initialTarget = isolated.snapshot()
            assertTrue("Restore target must initially have no entries", initialTarget.entries.isEmpty())
            assertTrue("Restore target must initially have no drafts", initialTarget.drafts.isEmpty())
            assertTrue("Restore target must initially have no attachments", initialTarget.attachments.isEmpty())
            assertTrue("Restore target must initially have no submitted draft IDs", initialTarget.sealedDraftIds.isEmpty())
            report.appendLine("targetDatabase=$targetDatabase")
            report.appendLine("targetStorage=$targetStorage")
            report.appendLine("targetInitiallyEmpty=PASS")

            val manager = BackupManager(isolated, context)
            val prepared = manager.inspect(input.readBytes(), "WorkbookQA-2026")
            assertBusinessCoverage(prepared.snapshot, "UI backup manifest")
            assertIdenticalById(before, prepared.snapshot, "UI backup matches real QA data")
            assertIdenticalById(initialTarget, isolated.snapshot(), "Inspection must not modify the empty target")
            assertIdenticalById(before, source.snapshot(), "Inspection must not modify source QA data")
            report.appendLine("backupAppVersion=${prepared.appVersion}")
            report.appendLine("backupCreatedAt=${prepared.createdAt}")
            report.appendLine("manifestMatchesSource=PASS")
            report.appendLine("inspectionReadOnly=PASS")

            manager.restore(prepared)
            val restored = isolated.snapshot()
            DataValidation.validateSnapshot(restored)
            assertBusinessCoverage(restored, "restored independent database")
            assertIdenticalById(before, restored, "All restored fields, IDs, relations and counts")
            before.attachments.forEach { attachment ->
                val restoredAttachment = restored.attachments.single { it.id == attachment.id }
                val restoredFile = isolated.attachmentFile(restoredAttachment)
                assertEquals("Restored attachment size ${attachment.id}", attachment.size, restoredFile.length())
                assertEquals("Restored attachment SHA-256 ${attachment.id}", attachment.sha256, AttachmentFiles.sha256(restoredFile))
                assertEquals("Source/restored attachment content ${attachment.id}", sourceAttachmentDigests.getValue(attachment.id), AttachmentFiles.sha256(restoredFile))
                report.appendLine("attachment=${attachment.id}; bytes=${attachment.size}; SHA256=${attachment.sha256}; PASS")
            }

            val after = source.snapshot()
            assertIdenticalById(before, after, "Source QA database remains unchanged after isolated restore")
            before.attachments.forEach { attachment ->
                assertEquals("Source attachment remains unchanged ${attachment.id}", sourceAttachmentDigests.getValue(attachment.id), AttachmentFiles.sha256(source.attachmentFile(attachment)))
            }
            assertEquals("Source logical snapshot checksum remains unchanged", sourceDigest, digest(after))
            assertEquals("Restored logical snapshot checksum", sourceDigest, digest(restored))

            Kind.entries.forEach { kind -> report.appendLine("${kind.name}=${restored.entries.count { it.kind == kind }}") }
            report.appendLine("drafts=${restored.drafts.size}")
            report.appendLine("attachments=${restored.attachments.size}")
            report.appendLine("sealedDraftIds=${restored.sealedDraftIds.size}")
            if (restored.entries.none { it.kind == Kind.PROFILE }) {
                report.appendLine("profile=Default display only; no saved profile entity in either source or backup")
            }
            report.appendLine("snapshotSHA256=$sourceDigest")
            report.appendLine("allFieldsAndRelationships=PASS")
            report.appendLine("sourceQaDatabaseAndAttachmentsUnchanged=PASS")
            report.appendLine("RESULT=PASS")
        } catch (failure: Throwable) {
            report.appendLine("RESULT=FAIL")
            report.appendLine("failure=${failure.javaClass.simpleName}: ${failure.message?.take(400)}")
            throw failure
        } finally {
            target?.close()
            result.writeText(report.toString(), Charsets.UTF_8)
        }
    }

    private fun assertBusinessCoverage(snapshot: Snapshot, label: String) {
        val required = listOf(Kind.ORGANIZATION, Kind.CUSTOMER, Kind.CONTACT, Kind.RECIPIENT, Kind.INTERACTION, Kind.ACTIVITY, Kind.TASK)
        required.forEach { kind -> assertTrue("$label must contain $kind", snapshot.entries.any { it.kind == kind }) }
        val byId = snapshot.entries.associateBy { it.id }
        val interactions = snapshot.entries.filter { it.kind == Kind.INTERACTION }
        assertTrue("$label must contain an organization visit", interactions.any { byId[it.parentId]?.kind == Kind.ORGANIZATION })
        assertTrue("$label must contain a customer conversation", interactions.any { byId[it.parentId]?.kind == Kind.CUSTOMER })
        assertTrue("$label must contain a separate customer contact", snapshot.entries.any { it.kind == Kind.CONTACT && byId[it.parentId]?.kind == Kind.CUSTOMER })
        assertTrue("$label must contain a separate service recipient", snapshot.entries.any { it.kind == Kind.RECIPIENT && byId[it.parentId]?.kind == Kind.CUSTOMER })
        assertFalse("$label must contain persisted drafts", snapshot.drafts.isEmpty())
        assertFalse("$label must contain real imported attachments", snapshot.attachments.isEmpty())
        val draftOwners = snapshot.drafts.map { it.entry.id }.toSet()
        assertTrue("$label must contain an attachment associated with a persistent draft", snapshot.attachments.any { it.ownerId in draftOwners })
    }

    private fun assertIdenticalById(expected: Snapshot, actual: Snapshot, label: String) {
        assertEquals("$label: entry count", expected.entries.size, actual.entries.size)
        assertEquals("$label: draft count", expected.drafts.size, actual.drafts.size)
        assertEquals("$label: attachment count", expected.attachments.size, actual.attachments.size)
        assertEquals("$label: sealed draft count", expected.sealedDraftIds.size, actual.sealedDraftIds.size)
        assertEquals("$label: all entry fields and stable references", expected.entries.associateBy { it.id }, actual.entries.associateBy { it.id })
        assertEquals("$label: all draft content, revisions and references", expected.drafts.associateBy { it.id }, actual.drafts.associateBy { it.id })
        assertEquals("$label: all attachment metadata and ownership", expected.attachments.associateBy { it.id }, actual.attachments.associateBy { it.id })
        assertEquals("$label: submitted draft seals", expected.sealedDraftIds.toSet(), actual.sealedDraftIds.toSet())
    }

    private fun digest(snapshot: Snapshot): String {
        val ordered = snapshot.copy(
            entries = snapshot.entries.sortedBy { it.id }.map { it.copy(fields = it.fields.toSortedMap()) },
            drafts = snapshot.drafts.sortedBy { it.id }.map { it.copy(entry = it.entry.copy(fields = it.entry.fields.toSortedMap())) },
            attachments = snapshot.attachments.sortedBy { it.id },
            sealedDraftIds = snapshot.sealedDraftIds.sorted(),
        )
        return AttachmentFiles.sha256(dataJson.encodeToString(ordered).toByteArray(Charsets.UTF_8))
    }
}
