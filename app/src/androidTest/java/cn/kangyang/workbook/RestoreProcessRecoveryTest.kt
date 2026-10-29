package cn.kangyang.workbook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly invoked by an external driver; does not touch the normal QA or personal database. */
@RunWith(AndroidJUnit4::class)
class RestoreProcessRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun prepareInterruption(): Unit = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interruptRestore") == "true")
        check(context.packageName.endsWith(".qa"))
        val token = UUID.randomUUID().toString()
        val source = Repository(context, "interrupt-source-$token.db", "interrupt-source-$token")
        val target = Repository(context, "interrupt-target-$token.db", "interrupt-target-$token")
        try {
            val old = target.save(Entry(id = "old-intact", kind = Kind.CUSTOMER, title = "中断前完整旧资料"))
            source.save(Entry(id = "new-only", kind = Kind.CUSTOMER, title = "尚未提交的新资料"))
            val draft = Entry(id = "new-draft", kind = Kind.INTERACTION, fields = mapOf("content" to "待恢复草稿"))
            source.saveDraft(Draft("new-draft-id", draft, 1))
            val attachment = AttachmentFiles.write(source.filesRoot, draft.id, "中断验证.txt", "text/plain", "模拟附件".byteInputStream())
            source.database.dao().upsert(attachment)
            val encrypted = BackupManager(source, context).export("interrupt-test-password")
            val manager = BackupManager(target, context)
            val prepared = manager.inspect(encrypted, "interrupt-test-password")
            File(context.filesDir, "restore-interruption-token.txt").writeText(token)
            File(context.filesDir, "restore-interruption-ready.txt").delete()
            manager.restore(prepared) { phase ->
                if (phase == "files_staged") {
                    File(context.filesDir, "restore-interruption-ready.txt").writeText("files_staged:$token:${old.id}")
                    Thread.sleep(60_000) // External host force-stops only the QA target process during this window.
                    throw AssertionError("External interruption driver did not stop the QA process within 60 seconds")
                }
            }
        } finally { source.close(); target.close() }
    }

    @Test fun verifyAfterInterruption(): Unit = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interruptRestore") == "true")
        check(context.packageName.endsWith(".qa"))
        val token = File(context.filesDir, "restore-interruption-token.txt").readText().trim()
        check(token.matches(Regex("[a-f0-9-]{36}")))
        val target = Repository(context, "interrupt-target-$token.db", "interrupt-target-$token")
        try {
            val snapshot = target.snapshot()
            assertEquals(listOf("old-intact"), snapshot.entries.map { it.id })
            assertTrue(snapshot.drafts.isEmpty()); assertTrue(snapshot.attachments.isEmpty())
            DataValidation.validateSnapshot(snapshot)
            File(context.filesDir, "restore-interruption-verified.txt").writeText("PASS: complete old snapshot after process interruption; token=$token")
        } finally { target.close() }
    }
}
