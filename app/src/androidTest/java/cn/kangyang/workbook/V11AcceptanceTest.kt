package cn.kangyang.workbook

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.storage.BackupManager
import cn.kangyang.workbook.storage.Exports
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UI acceptance for V1.1. The host selects methods and explicitly locks adb to
 * emulator-5554. Do not indiscriminately execute this class: process restoration,
 * migration, layout and personal checks have documented host prerequisites.
 *
 * UI input drives production persistence. Repository reads only assert the
 * committed fact; they do not create the notes, tasks or activities under test.
 */
@RunWith(AndroidJUnit4::class)
class V11AcceptanceTest {
    @get:Rule(order = 0) val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val captureFailures = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) { failureEvidence(e) }
    }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val repo get() = Repository.get(ui.activity)
    private val evidence get() = File(ui.activity.getExternalFilesDir(null), "v11-evidence").apply { mkdirs() }
    private fun snap() = runBlocking { repo.snapshot() }
    private fun tag(name: String) = ui.onNodeWithTag(name)
    private fun qa() = check(ui.activity.packageName.endsWith(".qa"))
    private fun failureEvidence(failure: Throwable) {
        val name = "failure-${System.currentTimeMillis()}"
        runCatching {
            val model = ui.activity.model
            val editor = model.editor
            File(evidence, "$name.txt").writeText("${failure.javaClass.simpleName}: ${failure.message}\n" +
                "route=${model.route}\nmessage=${model.message}\ntransitioning=${model.transitioning}\nuiEpoch=${model.uiEpoch}\ngeneration=${repo.generation}\n" +
                "editorId=${editor?.entry?.id}\nstatus=${editor?.status}\nrevision=${editor?.revision}\npersisted=${editor?.persistedRevision}\nattachment=${editor?.attachmentStatus}\n")
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            println("V11 diagnostic=$name; route=${model.route}; message=${model.message}; transitioning=${model.transitioning}; epoch=${model.uiEpoch}; editorStatus=${editor?.status}")
        }
    }
    private fun waitFor(block: () -> Boolean) {
        try { ui.waitUntil(20_000, block) }
        catch (failure: Throwable) { failureEvidence(failure); throw failure }
    }
    private fun home() { ui.runOnUiThread { ui.activity.model.navigate("home", true) }; ui.waitForIdle() }
    private fun shot(name: String) {
        ui.waitForIdle(); device.waitForIdle()
        check(device.takeScreenshot(File(evidence, "$name.png")))
    }
    private fun scrollToIfNeeded(node: SemanticsNodeInteraction, matcher: SemanticsMatcher) {
        if (node.isDisplayed()) return
        if (ui.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            // The modal object's list takes precedence over its underlying page.
            val container = listOf("object_list", "task_list", "list_scroll", "search_scroll", "draft_list", "detail_scroll")
                .firstOrNull { tag(it).isDisplayed() }
            if (container != null) {
                tag(container).performScrollToNode(matcher)
                return
            }
        }
        node.performScrollTo()
    }
    /** Inject a real device touch at the observed rendered target, not SemanticsActions.OnClick. */
    private fun touch(name: String, scroll: Boolean = false) {
        val node = tag(name)
        // A fixed footer (for example save_entry) is already reachable and has no
        // scroll parent. Scrolling is only allowed when a caller requested it and
        // the actual rendered target is offscreen. T01 never requests scrolling.
        if (scroll) scrollToIfNeeded(node, hasTestTag(name))
        touchNode(node)
    }
    private fun touchNode(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed().assertIsEnabled()
        device.waitForIdle()
        val semantics = node.fetchSemanticsNode()
        var screen = Offset.Zero
        ui.runOnUiThread {
            // A Material AlertDialog has its own centered Window. Window-local
            // coordinates alone would tap the wrong place in the underlying app.
            val coordinates = semantics.layoutInfo.coordinates
            screen = coordinates.localToScreen(coordinates.windowToLocal(semantics.boundsInWindow.center))
        }
        check(device.click(screen.x.toInt(), screen.y.toInt()))
        ui.waitForIdle()
    }
    private fun text(value: String) {
        val node = ui.onNodeWithText(value, useUnmergedTree = false)
        scrollToIfNeeded(node, hasText(value))
        touchNode(node)
    }
    private fun closeKeyboard() { device.pressBack(); ui.waitForIdle() }
    private fun newNote(): String {
        home(); touch("quick_record")
        waitFor { ui.activity.model.editor?.entry?.kind == Kind.INTERACTION }
        tag("note_body").assertIsFocused()
        return ui.activity.model.editor!!.entry.id
    }
    private fun body(value: String) { tag("note_body").performTextReplacement(value); ui.waitForIdle() }
    private fun saved(id: String, value: String? = null) {
        waitFor {
            val e = snap().find(id)
            e != null && (value == null || e["content"] == value) &&
                ui.activity.model.editor?.status == "已保存" &&
                ui.activity.model.editor?.persistedRevision == ui.activity.model.editor?.revision
        }
        tag("note_save_status").assertTextEquals("已保存")
    }
    private fun readEntry(id: String) {
        home(); touch("search_all"); tag("global_search").performTextReplacement("")
        // The search UI is deliberately used; a larger fixture database may need scrolling.
        touch("entry_$id", scroll = true)
    }
    private fun editEntry(id: String, query: String) {
        home(); touch("search_all"); tag("global_search").performTextReplacement(query); ui.waitForIdle()
        touch("entry_$id", scroll = true); touch("edit_entry")
        waitFor { ui.activity.model.editor?.entry?.id == id }
    }
    private fun genericField(key: String, value: String) {
        tag("field_$key").performScrollTo().performTextReplacement(value); ui.waitForIdle()
    }
    private fun genericSave() {
        closeKeyboardIfVisible()
        touch("save_entry", scroll = true)
        waitFor { ui.activity.model.editor == null }
    }
    private fun closeKeyboardIfVisible() {
        var shown = false
        ui.runOnUiThread { shown = ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        if (shown) closeKeyboard()
    }
    private fun writeIdentity(name: String, e: Entry) {
        File(evidence, "$name.json").writeText(dataJson.encodeToString(e))
    }
    private fun readIdentity(name: String): Entry = dataJson.decodeFromString(File(evidence, "$name.json").readText())

    @Test fun oneTouchChineseNoteAndEmptySession() {
        qa(); home()
        val initial = snap()
        // IMPORTANT: no performScrollTo, swipe or scrolling occurs before this evidence.
        tag("quick_record").assertIsDisplayed()
        shot("t01-home-unscrolled")
        touch("quick_record")
        waitFor { ui.activity.model.editor != null }
        tag("note_body").assertIsFocused()
        waitFor {
            var visible = false
            ui.runOnUiThread { visible = ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            visible
        }
        shot("t01-body-and-keyboard")
        val id = ui.activity.model.editor!!.entry.id
        val content = "周院长希望先看服务介绍，下周再联系。"
        body(content); saved(id, content)
        val note = snap().find(id)!!
        assertTrue(note.title.isBlank()); assertNull(note.parentId)
        assertEquals(initial.entries.count { it.kind == Kind.TASK }, snap().entries.count { it.kind == Kind.TASK })
        touch("note_back")
        waitFor { ui.activity.model.route == "home" }
        tag("entry_$id").assertIsDisplayed()
        shot("t02-recent-saved")
        touch("entry_$id"); tag("note_detail_body").assertTextEquals(content)
        shot("t02-body-first-detail")
        home(); touch("search_all"); tag("global_search").performTextReplacement("周院长希望先看服务介绍")
        tag("entry_$id").assertExists()
        assertEquals(note, snap().find(id))
        writeIdentity("short-note", note)
        home(); val count = snap().entries.size
        touch("quick_record"); waitFor { ui.activity.model.editor != null }
        val blankId = ui.activity.model.editor!!.entry.id
        touch("note_back"); waitFor { ui.activity.model.route == "home" }
        assertNull(snap().find(blankId)); assertEquals(count, snap().entries.size)
        File(evidence, "t01-t02.txt").writeText("PASS physical single touch; focused body and real IME; Chinese body committed; same ID in home/search; empty session creates no record; no automatic TASK\nnoteId=$id")
    }

    @Test fun rapidNoteTypingAndCursorMovementKeepLatestText() {
        qa(); val id = newNote()
        fun typeKeys(value: String) {
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            ui.mainClock.autoAdvance = false
            val input = kotlin.concurrent.thread {
                try { device.executeShellCommand("input text ${value.replace(" ", "%s")}") }
                catch (e: Throwable) { failure.set(e) }
            }
            try {
                // Render between actual key events, as the standalone app does.
                while (input.isAlive) { ui.mainClock.advanceTimeByFrame(); SystemClock.sleep(8) }
                failure.get()?.let { throw it }
            } finally { input.join(); ui.mainClock.autoAdvance = true }
            ui.waitForIdle()
        }
        val initial = "Perf rapid typing 0123456789 keeps every character"
        typeKeys(initial); saved(id, initial)
        tag("note_body").assertTextEquals(initial).assertIsFocused()
        val revision = ui.activity.model.editor!!.revision
        tag("note_body").performTextInputSelection(androidx.compose.ui.text.TextRange(5))
        ui.waitForIdle()
        assertEquals("Cursor movement must not schedule another save", revision, ui.activity.model.editor!!.revision)
        typeKeys("INSERT")
        val expected = initial.take(5) + "INSERT" + initial.drop(5)
        saved(id, expected)
        tag("note_body").assertTextEquals(expected).assertIsFocused()
        var keyboardShown = false
        ui.runOnUiThread { keyboardShown = ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        assertTrue("Typing and autosave must keep IME visible", keyboardShown)
        touch("note_back"); waitFor { ui.activity.model.editor == null }
        assertEquals(expected, snap().find(id)!!["content"])
    }

    @Test fun appendObjectYesterdayAndIndependentFollowup() {
        qa()
        val id = newNote()
        val content = "追加整理验收：先记下今天讨论的合作内容。"
        body(content); saved(id, content)
        val before = snap().find(id)!!
        val org = snap().entries.first { it.kind == Kind.ORGANIZATION && !it.archived }
        touch("note_link"); tag("object_search").performTextReplacement(org.title)
        shot("t04-searchable-object-panel")
        touch("object_${org.id}", scroll = true)
        waitFor { snap().find(id)?.parentId == org.id }
        assertEquals(before.createdAt, snap().find(id)!!.createdAt)
        assertEquals(before.occurredAt, snap().find(id)!!.occurredAt)
        closeKeyboardIfVisible(); touch("note_date", scroll = true)
        touch("occur_yesterday"); touch("occur_save", scroll = true)
        waitFor { Instant.ofEpochMilli(snap().find(id)!!.occurredAt).atZone(TimeRules.zone).toLocalDate() == TimeRules.today().minusDays(1) }
        val organized = snap().find(id)!!
        assertEquals(content, organized["content"]); assertEquals(before.createdAt, organized.createdAt)
        assertTrue(Exports.csv(snap(), listOf(organized), false, true).toString(Charsets.UTF_8).contains(TimeRules.today().minusDays(1).toString()))
        touch("note_followup"); tag("followup_title").performTextReplacement("给周院长发送服务介绍")
        touch("followup_tomorrow"); closeKeyboardIfVisible(); touch("followup_reminder", scroll = true)
        device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 2500)?.click()
        ui.waitForIdle(); shot("t06-tomorrow-followup")
        touch("followup_submit", scroll = true)
        waitFor { snap().entries.any { it.kind == Kind.TASK && it.sourceId == id } }
        val task = snap().entries.single { it.kind == Kind.TASK && it.sourceId == id }
        assertEquals(TimeRules.today().plusDays(1).toString(), task["date"])
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        // Independently edit the task through its real detail/editor path.
        home(); touch("tasks_all"); touch("entry_${task.id}", scroll = true); touch("edit_entry")
        touch("field_date", scroll = true); touch("date_today")
        genericField("title", "准备发送介绍并电话确认")
        // A false reminder must remain false after returning to the source note.
        if (ui.activity.model.editor!!.entry["reminder"] == "true") touch("field_reminder", scroll = true)
        genericSave()
        val independentlyEdited = snap().find(task.id)!!
        editEntry(id, "追加整理验收")
        body("$content\n追加：对方已经确认收到资料。"); saved(id)
        touch("note_followup"); tag("followup_title").performTextReplacement("独立改期后只改跟进文字")
        closeKeyboardIfVisible(); touch("followup_submit", scroll = true)
        waitFor { snap().find(task.id)?.title == "独立改期后只改跟进文字" }
        val finalTask = snap().find(task.id)!!
        assertEquals(independentlyEdited["date"], finalTask["date"])
        assertEquals(independentlyEdited["time"], finalTask["time"])
        assertEquals(independentlyEdited["reminder"], finalTask["reminder"])
        assertEquals(independentlyEdited["status"], finalTask["status"])
        assertEquals(organized.occurredAt, snap().find(id)!!.occurredAt)
        writeIdentity("organized-note", snap().find(id)!!)
        touch("note_done"); home(); touch("tasks_all"); touch("entry_${task.id}", scroll = true)
        touch("reschedule_task", scroll = true); text("时刻、优先级与备注")
        tag("followup_note").performScrollTo().performTextReplacement("已完成电话确认，备注需要保留。")
        closeKeyboardIfVisible(); touch("followup_submit", scroll = true)
        touch("complete_task", scroll = true); waitFor { snap().find(task.id)?.get("status") == "已完成" }
        text("详细资料与更多操作"); text("重新打开")
        waitFor { snap().find(task.id)?.get("status") == "未完成" }
        touch("complete_task", scroll = true); waitFor { snap().find(task.id)?.get("status") == "已完成" }
        assertEquals("已完成电话确认，备注需要保留。", snap().find(task.id)!!["completionNote"])
        editEntry(id, "追加整理验收"); touch("note_followup")
        tag("followup_title").performTextReplacement("已完成后补充跟进文字")
        closeKeyboardIfVisible(); touch("followup_submit", scroll = true)
        waitFor { snap().find(task.id)?.title == "已完成后补充跟进文字" }
        assertEquals("已完成", snap().find(task.id)!!["status"])
        assertEquals("已完成电话确认，备注需要保留。", snap().find(task.id)!!["completionNote"])
        assertEquals(independentlyEdited["date"], snap().find(task.id)!!["date"])
        File(evidence, "t04-t06.txt").writeText("PASS stable note ID/createdAt; object only did not change occurredAt; yesterday persisted/exported; explicit TASK created; independent date/reminder preserved after body and task-text edit; complete/reopen/complete retains note; editing completed task text never reopens\nnoteId=$id\ntaskId=${task.id}")
    }

    @Test fun immediateBackAndExistingBodyCanBeCleared() {
        qa(); val id = newNote(); val keyword = "清空索引验收${System.currentTimeMillis()}"
        body(keyword)
        // Leave immediately without waiting for debounce: return is a coordinated durable commit.
        touch("note_back"); waitFor { ui.activity.model.route == "home" }
        assertEquals(keyword, snap().find(id)!!["content"])
        touch("entry_$id"); touch("edit_entry"); body(""); saved(id, "")
        val empty = snap().find(id)!!; assertEquals(id, empty.id)
        assertEquals("", empty["content"]); assertEquals("", empty.title)
        touch("note_back"); home(); touch("search_all"); tag("global_search").performTextReplacement(keyword)
        tag("entry_$id").assertDoesNotExist()
        home(); tag("entry_$id").assertExists()
        touch("entry_$id", scroll = true); tag("note_detail_body").assertTextEquals("空白记录")
        shot("t05-cleared-same-id")
        File(evidence, "t03-immediate-t05-empty.txt").writeText("PASS immediate back commits latest; existing emptied note retained same ID; old body query no longer matches\nid=$id")
    }

    private fun createPhoto() {
        val context = ui.activity
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "V11示例活动照片.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WorkbookQA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(231, 245, 238))
            val paint = Paint().apply { color = Color.rgb(15, 81, 79); textSize = 38f; isAntiAlias = true }
            canvas.drawText("V1.1 示例活动照片", 40f, 190f, paint)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
    private fun choosePhoto() {
        touch("note_attach"); touch("note_photo"); selectPreparedPhoto()
    }
    private fun selectPreparedPhoto() {
        device.waitForIdle()
        val image = device.wait(Until.findObject(By.res("com.google.android.providers.media.module:id/icon_thumbnail")), 6000)
            ?: device.findObject(By.res("com.android.providers.media.module:id/icon_thumbnail"))
            ?: device.findObject(By.descContains("Photo taken"))
        checkNotNull(image) { "System photo picker did not show the prepared photo" }.click()
        device.findObject(By.text("Add"))?.click(); device.findObject(By.text("添加"))?.click()
        ui.waitForIdle()
    }

    @Test fun preparePhotoNoteForRealProcessDeath() {
        qa(); createPhoto()
        val id = newNote()
        val content = "进程恢复验收：中文多段正文。\n照片和发生时间一起保留。"
        body(content); saved(id, content); choosePhoto()
        waitFor { snap().attachments.any { it.ownerId == id } }
        saved(id, content)
        val before = snap().find(id)!!
        val files = snap().attachments.filter { it.ownerId == id }
        files.forEach { assertEquals(it.sha256, AttachmentFiles.sha256(repo.attachmentFile(it))) }
        writeIdentity("process-note", before)
        File(evidence, "process-attachments.json").writeText(dataJson.encodeToString(files))
        shot("t03-photo-note-saved")
        touch("note_back"); waitFor { ui.activity.model.editor == null }
        device.pressHome()
    }

    @Test fun verifyAfterRealProcessDeathAndRotation() {
        qa(); val before = readIdentity("process-note")
        assertEquals(before, snap().find(before.id))
        val files = dataJson.decodeFromString<List<Attachment>>(File(evidence, "process-attachments.json").readText())
        assertEquals(files, snap().attachments.filter { it.ownerId == before.id })
        files.forEach { assertEquals(it.sha256, AttachmentFiles.sha256(repo.attachmentFile(it))) }
        editEntry(before.id, "进程恢复验收")
        tag("note_body").assertTextEquals(before["content"])
        try { device.setOrientationLeft(); ui.waitForIdle(); device.setOrientationNatural(); ui.waitForIdle() }
        finally { device.unfreezeRotation() }
        tag("note_body").assertTextEquals(before["content"])
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        assertEquals(before.occurredAt, snap().find(before.id)!!.occurredAt)
        assertEquals(before.createdAt, snap().find(before.id)!!.createdAt)
        shot("t03-reopened-after-real-process")
        File(evidence, "t03-process.txt").writeText("PASS previously committed body/ID/createdAt/occurredAt/attachment metadata+SHA after host process termination and rotation\nid=${before.id}")
    }

    @Test fun attachmentOnlyAndCanceledPicker() {
        qa(); createPhoto(); val id = newNote()
        touch("note_attach"); touch("note_photo"); device.waitForIdle(); device.pressBack(); ui.waitForIdle()
        touch("note_back"); waitFor { ui.activity.model.editor == null }
        assertNull(snap().find(id)); assertTrue(snap().attachments.none { it.ownerId == id })
        val imageOnly = newNote(); choosePhoto(); waitFor { snap().find(imageOnly) != null && snap().attachments.any { it.ownerId == imageOnly } }
        saved(imageOnly, "")
        val file = snap().attachments.first { it.ownerId == imageOnly }
        touch("note_back"); home(); touch("search_all"); tag("global_search").performTextReplacement(file.name)
        tag("entry_$imageOnly").assertExists()
        assertEquals(file.sha256, AttachmentFiles.sha256(repo.attachmentFile(file)))
        touch("entry_$imageOnly", scroll = true); shot("t05-attachment-only-detail")
        File(evidence, "t05-attachment.txt").writeText("PASS canceled picker leaves no note; attachment-only persisted; filename search; metadata/hash\nid=$imageOnly\nattachmentId=${file.id}")
    }

    @Test fun pendingActivityThroughRealForm() {
        qa(); createPhoto(); home(); touch("nav_activities"); text("新增活动")
        val title = "示例·康养交流筹备 ${System.currentTimeMillis().toString().takeLast(5)}"
        genericField("title", title); genericField("plan", "先收集社区的参与意向，时间和地点随后确定。")
        val activityId = ui.activity.model.editor!!.entry.id
        closeKeyboardIfVisible(); touch("add_photo", scroll = true); selectPreparedPhoto()
        waitFor { snap().attachments.any { it.ownerId == activityId } }
        genericSave()
        val activity = snap().entries.single { it.kind == Kind.ACTIVITY && it.title == title }
        assertEquals("筹备中", activity["status"])
        assertEquals("", activity["start"]); assertEquals("", activity["end"])
        assertNull(TimeRules.reminderAt(activity))
        val attachment = snap().attachments.single { it.ownerId == activityId }
        assertEquals(attachment.sha256, AttachmentFiles.sha256(repo.attachmentFile(attachment)))
        home(); touch("nav_activities"); tag("list_search").performTextReplacement(title)
        tag("entry_${activity.id}").assertExists(); shot("t08-pending-activity-list")
        touch("entry_${activity.id}", scroll = true); shot("t08-pending-activity-detail")
        text("添加筹备待办"); genericField("title", "先联系社区确认参与意向")
        genericSave()
        val task = snap().entries.single { it.kind == Kind.TASK && it.parentId == activity.id }
        assertEquals("", task["date"]); assertNull(TimeRules.reminderAt(task))
        writeIdentity("pending-activity", activity)
        home(); touch("nav_activities"); tag("list_search").performTextReplacement(title)
        touch("entry_${activity.id}", scroll = true); touch("edit_entry")
        touch("activity_schedule", scroll = true)
        touch("field_start", scroll = true); touch("date_today"); timeInput("09", "30")
        touch("field_end", scroll = true); touch("date_today"); timeInput("09", "00")
        closeKeyboardIfVisible(); touch("save_entry", scroll = true)
        tag("form_error").assertTextContains("结束时间不能早于开始时间")
        assertEquals("", snap().find(activity.id)!!["start"])
        assertEquals(activity["plan"], ui.activity.model.editor!!.entry["plan"])
        shot("t08-invalid-end-safe-feedback")
        touch("field_end", scroll = true); touch("date_today"); timeInput("10", "30")
        touch("field_status", scroll = true); touch("option_待开展", scroll = true)
        genericSave()
        assertTrue(snap().find(activity.id)!!["start"].isNotBlank())
        assertEquals("待开展", snap().find(activity.id)!!["status"])
        assertEquals(task, snap().find(task.id))
        assertNull(TimeRules.reminderAt(snap().find(activity.id)!!))
        assertEquals(attachment, snap().attachments.single { it.id == attachment.id })
        touch("edit_entry"); touch("field_status", scroll = true); touch("option_已完成", scroll = true)
        genericSave(); assertEquals("已完成", snap().find(activity.id)!!["status"])
        assertTrue(ui.onAllNodesWithText("取消活动").fetchSemanticsNodes().isEmpty())
        text("详细资料与更多操作")
        assertTrue(ui.onAllNodesWithText("取消活动").fetchSemanticsNodes().isEmpty())
        assertEquals(task, snap().find(task.id))
        shot("t08-completed-no-cancel")
        File(evidence, "t08-pending.txt").writeText("PASS pending plan+real photo saved/list findable/no alarm; undated independent preparation TASK; actual pickers rejected end<start without overwriting formal data or plan; valid scheduled edit preserved undated TASK and attachment; reminder stayed off; completed activity has no cancel action\nactivityId=${activity.id}\ntaskId=${task.id}\nattachmentId=${attachment.id}\n")
    }

    private fun timeInput(hour: String, minute: String) {
        val fields = ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("time_input")), useUnmergedTree = true)
        fields.assertCountEquals(2)
        fields[0].performTextReplacement(hour)
        fields[1].performTextReplacement(minute)
        closeKeyboardIfVisible(); touch("time_confirm", scroll = true)
    }

    @Test fun occurrenceTimeAndAutomaticMidnightLifecycleRefresh() {
        qa(); val noteId = newNote()
        body("发生时间验收：正文与时间分别修改。"); saved(noteId)
        val before = snap().find(noteId)!!
        closeKeyboardIfVisible(); touch("note_date", scroll = true)
        touch("occur_today"); touch("occur_time", scroll = true); timeInput("09", "30")
        touch("occur_save", scroll = true)
        val selectedDay = TimeRules.today()
        val expected = selectedDay.atTime(9, 30).atZone(TimeRules.zone).toInstant().toEpochMilli()
        waitFor { snap().find(noteId)?.occurredAt == expected }
        assertEquals("09:30", snap().find(noteId)!!["occurredTime"])
        assertEquals(before.createdAt, snap().find(noteId)!!.createdAt)
        assertNull("The same strict contract rejects seconds instead of silently retaining an old timestamp", TimeRules.parseDateTime("$selectedDay 09:30:45"))
        shot("t07-occurrence-0930")
        touch("note_done"); home(); touch("tasks_all"); text("新增")
        genericField("title", "示例·北京时间跨午夜状态验收")
        touch("field_date", scroll = true); touch("date_today")
        val taskId = ui.activity.model.editor!!.entry.id; genericSave()
        val task = snap().find(taskId)!!
        val end = selectedDay.plusDays(1).atStartOfDay(TimeRules.zone).toInstant().toEpochMilli()
        val realtimeStart = SystemClock.elapsedRealtime()
        try {
            // Test-only business clock. The Android/device/system clock is never changed.
            // It advances naturally with elapsedRealtime; the app's scheduled timer must refresh.
            ui.runOnUiThread {
                ui.activity.model.clockProvider = { end - 4_000 + (SystemClock.elapsedRealtime() - realtimeStart) }
                ui.activity.model.refreshClock()
            }
            ui.onNodeWithText("今日", useUnmergedTree = false).assertExists()
            shot("t07-before-business-midnight")
            waitFor {
                ui.activity.model.clockNow >= end &&
                    ui.onAllNodesWithText("逾期").fetchSemanticsNodes().isNotEmpty()
            }
            // No navigation, data mutation, or manual refresh after setting the pre-midnight clock.
            assertEquals(task, snap().find(taskId))
            shot("t07-automatic-business-midnight")

            val dayBefore = selectedDay.minusDays(1).atTime(12, 0).atZone(TimeRules.zone).toInstant().toEpochMilli()
            ui.runOnUiThread { ui.activity.model.clockProvider = { dayBefore }; ui.activity.model.refreshClock() }
            ui.onNodeWithText("未来").assertExists()
            device.pressHome(); device.waitForIdle()
            ui.runOnUiThread { ui.activity.model.clockProvider = { end + 60_000 } }
            returnToForeground()
            waitFor { ui.activity.model.clockNow == end + 60_000 }
            ui.onNodeWithText("逾期").assertExists()
            shot("t07-onresume-refresh")
            File(evidence, "t07-time-clock.txt").writeText("PASS real picker selected 09:30; occurredAt/createdAt consistent; strict shared parser rejects seconds; actual VM scheduled timer crossed virtual Asia/Shanghai midnight without input; actual HOME/resume refreshes future-to-overdue\nnote=$noteId\ntask=$taskId\nclockMode=test-only business clock advancing with elapsedRealtime; system/device clock unchanged\n")
        } finally {
            ui.runOnUiThread { ui.activity.model.clockProvider = TimeRules::now; ui.activity.model.refreshClock() }
        }
    }

    @Test fun realMinuteDeadlineRefreshesWithoutInteraction() {
        qa(); home(); touch("tasks_all"); text("新增")
        genericField("title", "示例·真实时钟截止刷新")
        val at = Instant.ofEpochMilli(TimeRules.now()).atZone(TimeRules.zone).plusMinutes(2).withSecond(0).withNano(0)
        touch("field_date", scroll = true)
        touch(if (at.toLocalDate() == TimeRules.today()) "date_today" else "date_tomorrow")
        touch("field_time", scroll = true); timeInput(at.hour.toString().padStart(2, '0'), at.minute.toString().padStart(2, '0'))
        val id = ui.activity.model.editor!!.entry.id; genericSave()
        val task = snap().find(id)!!
        val planned = at.toInstant().toEpochMilli()
        assertEquals(planned, TimeRules.taskDue(task))
        assertTrue("Deadline must still be in the future before passive observation", TimeRules.now() < planned)
        shot("t07-real-minute-before")
        ui.waitUntil(150_000) { ui.onAllNodesWithText("逾期").fetchSemanticsNodes().isNotEmpty() }
        val observed = TimeRules.now()
        assertTrue(observed >= planned)
        assertEquals(task, snap().find(id))
        shot("t07-real-minute-after")
        File(evidence, "t07-real-minute.txt").writeText("PASS actual system clock; app remained foreground with no user action or data changes; scheduled UI timer changed task to overdue\nid=$id\nplanned=$planned\nobserved=$observed\ndelayMs=${observed - planned}\n")
    }

    @Test fun verifyLegacyMigrationAfterUpgradeOrReopen() {
        qa()
        waitFor { snap().find("v11legacy_new") != null }
        val s = snap()
        val fresh = s.find("v11legacy_new")!!
        assertEquals("旧版手写标题应保留", fresh.title)
        assertTrue(fresh["content"].contains("V11旧新建草稿"))
        assertEquals("v11legacy_org", fresh.parentId)
        assertEquals(1793232000000L, fresh.createdAt)
        assertEquals(1793232000000L, fresh.occurredAt)
        assertTrue(s.find("v11legacy_edit")!!["content"].contains("V11已有记录编辑草稿"))
        assertTrue(s.find("v11legacy_conflict")!!["content"].contains("V11冲突正式版本"))
        assertTrue(s.drafts.any { it.entry.id == "v11legacy_conflict" && it.entry["content"].contains("V11冲突编辑草稿") })
        assertNotNull(s.find("v11legacy_attachment"))
        val attachment = s.attachments.single { it.id == "v11legacy_attachment_file" }
        assertEquals("v11legacy_attachment", attachment.ownerId)
        assertEquals(attachment.sha256, AttachmentFiles.sha256(repo.attachmentFile(attachment)))
        assertTrue((s.entries + s.drafts.map { it.entry }).any { it.id == "v11legacy_invalid" && it["content"].contains("V11非法元数据正文") })
        assertTrue(s.drafts.any { it.entry.id == "v11legacy_customer" })
        assertNull(s.find("v11legacy_customer"))
        assertTrue(s.entries.none { it.kind == Kind.TASK && it.sourceId?.startsWith("v11legacy_") == true })
        assertTrue(listOf("v11legacy_new_draft", "v11legacy_edit_draft", "v11legacy_attachment_draft").all { it in s.sealedDraftIds })
        assertTrue(listOf("v11legacy_conflict_draft", "v11legacy_invalid_draft", "v11legacy_customer_draft").none { it in s.sealedDraftIds })
        val fixture = s.copy(entries = s.entries.filter { it.id.startsWith("v11legacy_") }.sortedBy { it.id },
            drafts = s.drafts.filter { it.entry.id.startsWith("v11legacy_") }.sortedBy { it.id },
            attachments = s.attachments.filter { it.ownerId.startsWith("v11legacy_") }.sortedBy { it.id },
            sealedDraftIds = s.sealedDraftIds.filter { it.startsWith("v11legacy_") }.sorted())
        val encoded = dataJson.encodeToString(fixture)
        val prior = File(evidence, "legacy-migration-first.json")
        if (prior.exists()) assertEquals("Second process launch must not migrate twice", prior.readText(), encoded)
        else prior.writeText(encoded)
        File(evidence, "t09-migration.txt").appendText("PASS fixture IDs/content/manual title/relationships/time/attachments preserved; conflicts recoverable; customer stays draft; no automatic TASK; fixture digest=${AttachmentFiles.sha256(encoded.toByteArray())}\n")
    }

    private fun recoveryList() {
        home(); val s = snap()
        val count = s.drafts.count { d -> !d.isNoteBuffer && (d.entry.title.isNotBlank() || d.entry["content"].isNotBlank() || d.entry["plan"].isNotBlank() || s.attachments.any { it.ownerId == d.entry.id }) }
        text("查看 $count 份")
    }

    /** Requires the final DraftRow choice UI and unconsumed legacy fixtures from the verified V1 backup. */
    @Test fun legacyInvalidFieldsAndConflictAreExplicitlyRecoverableThroughUi() {
        qa(); val original = snap()
        val invalid = original.drafts.single { it.id == "v11legacy_invalid_draft" }
        val currentConflict = original.find("v11legacy_conflict")!!
        val oldConflict = original.drafts.single { it.id == "v11legacy_conflict_draft" }
        val tasks = original.entries.filter { it.kind == Kind.TASK }.associateBy { it.id }
        recoveryList(); touch("draft_${invalid.id}", scroll = true)
        ui.onNodeWithText("修正旧填写").assertExists(); text("继续修正")
        waitFor { ui.activity.model.editor?.entry?.id == invalid.entry.id }
        tag("note_body").assertTextEquals(invalid.entry["content"])
        closeKeyboardIfVisible(); touch("note_date", scroll = true)
        ui.onNodeWithText("2026-02-30").assertExists()
        ui.onNodeWithText("09:30:45").assertExists()
        shot("t09-old-invalid-fields-visible")
        touch("occur_today"); touch("occur_time", scroll = true); timeInput("09", "30")
        touch("occur_save", scroll = true); saved(invalid.entry.id, invalid.entry["content"])
        val corrected = snap().find(invalid.entry.id)!!
        assertEquals(invalid.entry.createdAt, corrected.createdAt)
        assertEquals(TimeRules.today().atTime(9, 30).atZone(TimeRules.zone).toInstant().toEpochMilli(), corrected.occurredAt)
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        assertTrue(invalid.id in snap().sealedDraftIds)
        home(); touch("search_all"); tag("global_search").performTextReplacement("V11非法元数据正文")
        tag("entry_${invalid.entry.id}").assertExists()

        recoveryList(); touch("draft_${oldConflict.id}", scroll = true)
        ui.onNodeWithText("选择继续编辑的内容").assertExists()
        ui.onNodeWithText(currentConflict["content"]).assertExists()
        ui.onNodeWithText(oldConflict.entry["content"]).assertExists()
        shot("t09-conflict-both-versions-choice")
        text("保留当前记录")
        assertEquals(currentConflict, snap().find(currentConflict.id))
        assertTrue(snap().drafts.any { it.id == oldConflict.id })
        touch("draft_${oldConflict.id}", scroll = true); text("采用旧填写继续编辑")
        waitFor { ui.activity.model.editor?.entry?.id == currentConflict.id }
        tag("note_body").assertTextEquals(oldConflict.entry["content"])
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        assertEquals(oldConflict.entry["content"], snap().find(currentConflict.id)!!["content"])
        assertTrue("The previously current side remains recoverable after explicit adoption", snap().drafts.any { it.entry.id == currentConflict.id && it.entry["content"] == currentConflict["content"] })
        assertEquals(tasks, snap().entries.filter { it.kind == Kind.TASK }.associateBy { it.id })
        File(evidence, "t09-recovery-ui.txt").writeText("PASS real recovery list; invalid original date/time visible and corrected through picker; same ID/createdAt/body searchable; conflict dialog shows both sides; decline leaves current unchanged; explicit adoption preserves prior current as recovery content; no TASK side effects\ninvalidId=${invalid.entry.id}\nconflictId=${currentConflict.id}\n")
    }

    @Test fun visibleSaveFailureRetryAndSlowRevisionState() {
        qa(); val id = newNote()
        try {
            repo.beforeNoteCommit = { throw java.io.IOException("验收注入：当前写入失败，请重试") }
            body("保存失败验收：这句话必须在重试后才显示已保存。")
            waitFor { ui.activity.model.editor?.status == "未保存，点击重试" }
            assertNull(snap().find(id))
            tag("note_save_status").assertTextEquals("未保存，点击重试")
            shot("t03-save-failure-visible")
            repo.beforeNoteCommit = null
            touch("note_save_status")
            saved(id, "保存失败验收：这句话必须在重试后才显示已保存。")
            shot("t03-save-retried")

            val firstEntered = AtomicBoolean(false)
            val secondEntered = AtomicBoolean(false)
            val calls = AtomicInteger(0)
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            try {
                repo.beforeNoteCommit = {
                    when (calls.incrementAndGet()) {
                        1 -> { firstEntered.set(true); withContext(NonCancellable) { firstRelease.await() } }
                        2 -> { secondEntered.set(true); withContext(NonCancellable) { secondRelease.await() } }
                    }
                }
                body("慢写入的旧版本")
                waitFor { firstEntered.get() }
                body("慢写入后继续输入的最新正文，不能被旧版本覆盖。")
                assertEquals("正在保存…", ui.activity.model.editor?.status)
                firstRelease.complete(Unit)
                waitFor { secondEntered.get() }
                // Do not read the Room transaction while its deliberately held commit is pending.
                assertEquals("正在保存…", ui.activity.model.editor?.status)
                assertTrue(ui.activity.model.editor!!.persistedRevision < ui.activity.model.editor!!.revision)
                shot("t03-latest-revision-still-pending")
                secondRelease.complete(Unit)
                saved(id, "慢写入后继续输入的最新正文，不能被旧版本覆盖。")
            } finally {
                firstRelease.complete(Unit); secondRelease.complete(Unit); repo.beforeNoteCommit = null
            }
            touch("note_done"); waitFor { ui.activity.model.editor == null }
            assertEquals("慢写入后继续输入的最新正文，不能被旧版本覆盖。", snap().find(id)!!["content"])
            File(evidence, "t03-failure-slow-revisions.txt").writeText("PASS real UI editing; repository fault before commit yields visible failure; no persisted false success; touch retry; noncancelable delayed older revision cannot overwrite latest; pending latest status stays pending\nid=$id")
        } finally { repo.beforeNoteCommit = null }
    }

    @Test fun attachmentSuccessDoesNotClaimPendingBodySaved() {
        qa(); createPhoto(); val id = newNote()
        body("附件慢导入前的正文。"); saved(id)
        val attachmentEntered = AtomicBoolean(false)
        val bodyEntered = AtomicBoolean(false)
        val releaseAttachment = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()
        try {
            repo.beforeAttachmentCommit = { attachmentEntered.set(true); withContext(NonCancellable) { releaseAttachment.await() } }
            choosePhoto(); waitFor { attachmentEntered.get() }
            body("附件导入期间继续输入的最新正文。"); assertEquals("正在保存…", ui.activity.model.editor!!.status)
            repo.beforeNoteCommit = { bodyEntered.set(true); withContext(NonCancellable) { releaseBody.await() } }
            releaseAttachment.complete(Unit)
            waitFor { bodyEntered.get() }
            assertTrue("Attachment completion must not mark the new body revision saved", ui.activity.model.editor!!.status != "已保存")
            shot("t05-attachment-body-state-separated")
            releaseBody.complete(Unit)
            saved(id, "附件导入期间继续输入的最新正文。")
            assertEquals(1, snap().attachments.count { it.ownerId == id })
            File(evidence, "t05-attachment-body-revision.txt").writeText("PASS actual system photo selection; attachment import deliberately delayed while Chinese body edited; pending body revision never reported saved by attachment callback; final body and attachment committed\nid=$id")
        } finally {
            releaseAttachment.complete(Unit); releaseBody.complete(Unit)
            repo.beforeAttachmentCommit = null; repo.beforeNoteCommit = null
        }
    }

    @Test fun backWaitsForEmptyNoteAttachmentAndPublishesOnlySuccessfulImport() {
        qa(); createPhoto()
        val id = newNote()
        val entered = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        try {
            repo.beforeAttachmentCommit = { entered.set(true); withContext(NonCancellable) { release.await() } }
            choosePhoto(); waitFor { entered.get() }
            // The picker has returned, but the owned attachment has not committed.
            // Back is a real device touch while the intentionally slow import is in flight.
            touch("note_back")
            Thread.sleep(300)
            assertEquals("editor", ui.activity.model.route)
            assertEquals(id, ui.activity.model.editor?.entry?.id)
            assertEquals("", ui.activity.model.editor!!.entry["content"])
            shot("t05-empty-photo-back-waits")
            release.complete(Unit)
            waitFor { ui.activity.model.route == "home" && ui.activity.model.editor == null }
            val published = snap().find(id)!!
            assertEquals("", published["content"])
            val attachment = snap().attachments.single { it.ownerId == id }
            assertEquals(attachment.sha256, AttachmentFiles.sha256(repo.attachmentFile(attachment)))
            tag("entry_$id").assertExists(); shot("t05-empty-photo-back-home")
            home(); touch("search_all"); tag("global_search").performTextReplacement(attachment.name)
            tag("entry_$id").assertExists()
            touch("entry_$id", scroll = true); tag("note_detail_body").assertTextEquals("附件记录")

            // The actual encrypted full backup is restored to a fresh, isolated QA
            // database/storage root. The user's current QA aggregate is never replaced.
            val token = java.util.UUID.randomUUID().toString()
            val isolated = Repository(ui.activity, "v11-return-$token.db", "v11-return-$token")
            var backupHash = ""
            try {
                runBlocking {
                    val bytes = BackupManager(repo, ui.activity).export("V11AttachmentReturn-2026")
                    backupHash = AttachmentFiles.sha256(bytes)
                    val manager = BackupManager(isolated, ui.activity)
                    val prepared = manager.inspect(bytes, "V11AttachmentReturn-2026")
                    assertEquals(published, prepared.snapshot.find(id))
                    assertEquals(attachment, prepared.snapshot.attachments.single { it.id == attachment.id })
                    manager.restore(prepared)
                    val restored = isolated.snapshot()
                    DataValidation.validateSnapshot(restored)
                    assertEquals(published, restored.find(id))
                    assertEquals(attachment, restored.attachments.single { it.id == attachment.id })
                    assertEquals(attachment.sha256, AttachmentFiles.sha256(isolated.attachmentFile(attachment)))
                }
            } finally { isolated.close() }
            assertEquals(published, snap().find(id))
            File(evidence, "t05-empty-photo-back-backup.txt").writeText("PASS real empty note/system photo picker; back waited for noncancelable import; one formal attachment-only note appears on home/search with stable owner; complete encrypted backup restored exact entry/attachment/hash in independent database; source unchanged\nnote=$id\nattachment=${attachment.id}\nbackupSHA256=$backupHash\n")
        } finally { release.complete(Unit); repo.beforeAttachmentCommit = null }

        val canceled = newNote()
        touch("note_attach"); touch("note_photo"); device.waitForIdle(); device.pressBack(); ui.waitForIdle()
        touch("note_back"); waitFor { ui.activity.model.editor == null }
        assertNull(snap().find(canceled)); assertTrue(snap().attachments.none { it.ownerId == canceled })

        val failed = newNote()
        val failureEntered = AtomicBoolean(false)
        val failRelease = CompletableDeferred<Unit>()
        try {
            repo.beforeAttachmentCommit = {
                failureEntered.set(true); withContext(NonCancellable) { failRelease.await() }
                throw java.io.IOException("定向验收：附件提交失败，请重试")
            }
            choosePhoto(); waitFor { failureEntered.get() }
            touch("note_back"); assertEquals("editor", ui.activity.model.route)
            failRelease.complete(Unit)
            waitFor { ui.activity.model.editor?.attachmentStatus?.startsWith("附件未导入") == true && ui.activity.model.editor?.saving == false }
            assertEquals("editor", ui.activity.model.route)
            assertEquals(failed, ui.activity.model.editor!!.entry.id)
            tag("note_attachment_status").assertTextContains("附件未导入", substring = true)
            assertNull(snap().find(failed)); assertTrue(snap().attachments.none { it.ownerId == failed })
            shot("t05-empty-photo-waited-failure-visible")
            repo.beforeAttachmentCommit = null
            touch("note_back"); waitFor { ui.activity.model.editor == null }
            assertNull(snap().find(failed)); assertTrue(snap().attachments.none { it.ownerId == failed })
            File(evidence, "t05-empty-photo-cancel-failure.txt").writeText("PASS canceled picker creates no visible note; back during failed import stays for explicit failure; second explicit back exits without empty entry or attachment reference\ncanceled=$canceled\nfailed=$failed\n")
        } finally { failRelease.complete(Unit); repo.beforeAttachmentCommit = null }
    }

    @Test fun staleGenericTaskDraftCannotUndoIndependentScheduleChanges() {
        qa(); val suffix = System.currentTimeMillis().toString().takeLast(6)
        home(); touch("tasks_all"); text("新增")
        genericField("title", "示例·旧填写保护跟进$suffix")
        touch("field_date", scroll = true); touch("date_tomorrow")
        touch("field_reminder", scroll = true)
        device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 2_000)?.click()
        val id = ui.activity.model.editor!!.entry.id; genericSave()
        val initial = snap().find(id)!!
        assertEquals(TimeRules.today().plusDays(1).toString(), initial["date"])
        assertEquals("true", initial["reminder"])

        touch("edit_entry")
        val oldTitle = "旧编辑标题保留供参考$suffix"
        genericField("title", oldTitle)
        waitFor { snap().drafts.any { it.entry.id == id && it.entry.title == oldTitle } }
        val oldDraft = snap().drafts.single { it.entry.id == id && it.entry.title == oldTitle }
        closeKeyboardIfVisible(); ui.onNodeWithContentDescription("返回").performTouchInput { click() }
        waitFor { ui.activity.model.editor == null }
        assertEquals(initial, snap().find(id))

        touch("reschedule_task", scroll = true)
        touch("followup_today"); touch("followup_reminder", scroll = true)
        touch("followup_submit", scroll = true)
        waitFor { snap().find(id)?.get("date") == TimeRules.today().toString() && snap().find(id)?.get("reminder") == "false" }
        val independentlyChanged = snap().find(id)!!
        assertTrue(independentlyChanged.updatedAt > oldDraft.entry.updatedAt)

        touch("edit_entry")
        waitFor { ui.activity.model.editor?.entry?.id == id }
        assertEquals(independentlyChanged.title, ui.activity.model.editor!!.entry.title)
        assertEquals(independentlyChanged["date"], ui.activity.model.editor!!.entry["date"])
        assertEquals("false", ui.activity.model.editor!!.entry["reminder"])
        val finalTitle = "明确修改当前跟进标题$suffix"
        genericField("title", finalTitle); genericSave()
        val current = snap().find(id)!!
        assertEquals(finalTitle, current.title)
        listOf("date", "time", "reminder", "status", "priority", "completionNote").forEach { assertEquals("Current TASK $it changed unexpectedly", independentlyChanged[it], current[it]) }
        assertEquals(independentlyChanged.createdAt, current.createdAt)
        assertEquals(oldDraft.entry, snap().drafts.single { it.id == oldDraft.id }.entry)

        recoveryList(); touch("draft_${oldDraft.id}", scroll = true)
        ui.onNodeWithText("查看旧跟进填写").assertExists()
        val inPreview = hasAnyAncestor(hasTestTag("stale_task_draft_preview"))
        ui.onNode(hasText(oldTitle) and inPreview).assertExists()
        ui.onNode(hasText(initial["date"], substring = true) and inPreview).performScrollTo().assertIsDisplayed()
        ui.onNode(hasText("开启", substring = true) and inPreview).performScrollTo().assertIsDisplayed()
        assertEquals(current, snap().find(id))
        shot("t06-stale-task-draft-read-only-preview")
        touch("edit_current_task")
        waitFor { ui.activity.model.editor?.entry?.id == id }
        assertEquals(current, ui.activity.model.editor!!.entry)
        assertTrue(snap().drafts.any { it.id == oldDraft.id && it.entry.title == oldTitle })
        closeKeyboardIfVisible(); ui.onNodeWithContentDescription("返回").performTouchInput { click() }
        waitFor { ui.activity.model.editor == null }
        File(evidence, "t06-stale-task-draft.txt").writeText("PASS real generic title-only edit draft retained; independent panel changes date and disables reminder; reopened generic editor reads current TASK; explicit title save preserves date/reminder/status/note; old draft remains read-only with full old fields; Edit current task does not adopt stale fields\ntask=$id\noldDraft=${oldDraft.id}\n")
    }

    /** Host deliberately terminates QA after the ready marker; this preparatory run must not be reported PASS. */
    @Test fun prepareUncommittedWriteForHostInterruption() {
        qa(); assumeTrue(InstrumentationRegistry.getArguments().getString("interruptPending") == "true")
        val id = newNote()
        body("中断边界验收：这是已经确认保存的正文。"); saved(id)
        writeIdentity("pending-interruption-baseline", snap().find(id)!!)
        val entered = AtomicBoolean(false)
        val never = CompletableDeferred<Unit>()
        repo.beforeNoteCommit = { entered.set(true); withContext(NonCancellable) { never.await() } }
        body("中断边界验收：这段仍在保存，不能承诺已落盘。")
        waitFor { entered.get() }
        assertEquals("正在保存…", ui.activity.model.editor!!.status)
        shot("t03-before-interruption-saving-indicator")
        File(evidence, "pending-interruption-ready.txt").writeText("id=$id\nstate=正在保存\ncommittedBaseline=pending-interruption-baseline.json\n")
        // A bounded host script kills this specific package. No result is manufactured here.
        while (true) Thread.sleep(1_000)
    }

    @Test fun verifyInterruptedUncommittedWriteBoundary() {
        qa(); val expected = readIdentity("pending-interruption-baseline")
        assertEquals(expected, snap().find(expected.id))
        assertEquals(1, snap().entries.count { it.id == expected.id })
        editEntry(expected.id, "中断边界验收")
        tag("note_body").assertTextEquals(expected["content"])
        assertFalse(ui.activity.model.editor!!.entry["content"].contains("这段仍在保存"))
        shot("t03-after-interruption-last-committed")
        File(evidence, "t03-pending-boundary.txt").writeText("PASS controlled host interruption while UI honestly displayed 正在保存; last committed record/ID/time remained; uncommitted replacement was not falsely claimed durable\nid=${expected.id}\nLIMIT: content interrupted before its transaction commits cannot be guaranteed\n")
    }

    private fun chooseLegacyBackup() {
        chooseSystemDocument("康养验收备份 (1).kywb")
    }
    private fun chooseSystemDocument(fileName: String) {
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000)
        var file = device.wait(Until.findObject(By.text(fileName)), 2500)
        if (file == null) {
            (device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("显示根目录")))?.click()
            (device.findObject(By.text("Downloads")) ?: device.findObject(By.text("下载")))?.click()
            file = device.wait(Until.findObject(By.text(fileName)), 5000)
        }
        if (file == null) {
            device.dumpWindowHierarchy(File(evidence, "legacy-picker-failure.xml"))
            throw AssertionError("The requested synthetic fixture is not visible in Android DocumentsUI: $fileName")
        }
        file.click(); ui.waitForIdle()
    }

    /** Run on the final generic-editor attachment feedback implementation. */
    @Test fun malformedAttachmentsKeepNoteAndActivityStateHonest() {
        qa()
        val name = "V11异常附件-${System.currentTimeMillis()}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = ui.activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        ui.activity.contentResolver.openOutputStream(uri)!!.use { it.write("这不是有效的PDF内容，仅用于格式错误验收".toByteArray()) }
        val noteId = newNote(); touch("note_attach"); touch("note_file"); chooseSystemDocument(name)
        waitFor { ui.activity.model.editor?.attachmentStatus?.startsWith("附件未导入") == true }
        tag("note_attachment_status").assertTextContains("附件未导入", substring = true)
        assertNull(snap().find(noteId)); assertTrue(snap().attachments.none { it.ownerId == noteId })
        shot("t05-malformed-blank-note-error")
        touch("note_back"); waitFor { ui.activity.model.editor == null }
        assertNull(snap().find(noteId))

        home(); touch("nav_activities"); text("新增活动")
        genericField("title", "示例·附件失败后的筹备活动")
        genericField("plan", "已经写好的方案，在附件导入失败后仍需保留。")
        val activityId = ui.activity.model.editor!!.entry.id
        closeKeyboardIfVisible(); touch("add_file", scroll = true); chooseSystemDocument(name)
        waitFor { ui.activity.model.editor?.attachmentStatus?.startsWith("附件未导入") == true }
        tag("attachment_status").assertTextContains("附件未导入", substring = true)
        assertTrue(snap().drafts.any { it.entry.id == activityId && it.entry["plan"] == "已经写好的方案，在附件导入失败后仍需保留。" })
        assertTrue(snap().attachments.none { it.ownerId == activityId })
        shot("t12-malformed-activity-visible-error")
        genericSave()
        assertEquals("已经写好的方案，在附件导入失败后仍需保留。", snap().find(activityId)!!["plan"])
        File(evidence, "t05-t12-malformed-ui.txt").writeText("PASS actual SAF malformed PDF selected twice; blank note remains invisible; activity retains title/plan/draft and can save pending; distinct visible import failure, no attachment metadata or false success\nnote=$noteId\nactivity=$activityId\n")
    }
    private fun customerDraft(label: String): String {
        home(); touch("nav_customers"); text("新增客户"); genericField("title", label)
        val id = ui.activity.model.editor!!.entry.id
        waitFor { snap().drafts.any { it.entry.id == id } }
        closeKeyboardIfVisible(); ui.onNodeWithContentDescription("返回").performTouchInput { click() }
        waitFor { ui.activity.model.editor == null }
        return id
    }
    private fun backgroundAndReturn() {
        device.pressHome(); device.waitForIdle()
        returnToForeground()
    }
    private fun returnToForeground() {
        // The existing API36 launcher exposes this exact observed QA task card.
        // Recents returns to the same task/Activity without inventing a new intent.
        device.pressRecentApps()
        val card = device.wait(Until.findObject(By.desc("康养工作簿·验收")), 6_000)
        checkNotNull(card) { "Existing QA task card is not visible in Android Recents" }.click()
        device.wait(Until.hasObject(By.pkg(ui.activity.packageName)), 6_000)
        ui.waitForIdle()
    }

    @Test fun v1RestoreAndRollbackThroughUiIsolatesExitedEditors() {
        qa(); val a = customerDraft("示例·恢复前客户A草稿")
        val generation = repo.generation
        home(); touch("settings"); touch("open_restore", scroll = true)
        tag("backup_password").performScrollTo().performTextReplacement("WorkbookQA-2026")
        closeKeyboardIfVisible(); touch("inspect_backup", scroll = true); chooseLegacyBackup()
        waitFor { ui.onAllNodesWithTag("confirm_restore").fetchSemanticsNodes().isNotEmpty() }
        shot("t09-v1-backup-inspected")
        touch("confirm_restore")
        waitFor { ui.activity.model.message == "完整恢复成功" }
        assertTrue(repo.generation > generation)
        backgroundAndReturn()
        assertNull(ui.activity.model.editor)
        assertTrue(snap().drafts.none { it.entry.id == a }); assertNull(snap().find(a))
        assertNotNull(snap().find("v11legacy_new"))
        val legacyFile = snap().attachments.single { it.id == "v11legacy_attachment_file" }
        assertEquals(legacyFile.sha256, AttachmentFiles.sha256(repo.attachmentFile(legacyFile)))
        shot("t10-restored-v1-no-old-editor")
        val b = customerDraft("示例·回滚前客户B草稿")
        home(); touch("settings"); touch("open_restore", scroll = true)
        touch("rollback_restore", scroll = true); text("确认回滚")
        waitFor { ui.activity.model.message == "已回到恢复前的资料" }
        backgroundAndReturn()
        assertNull(ui.activity.model.editor)
        assertTrue(snap().drafts.none { it.entry.id == b }); assertNull(snap().find(b))
        assertTrue("A is legitimately present in the pre-restore rollback snapshot", snap().drafts.any { it.entry.id == a })
        File(evidence, "t09-t10-ui-restore-rollback.txt").writeText("PASS real SAF verified V1 backup selection/inspect/confirm; upgraded app imports V1 and legacy attachment; customer A editor exited before restore never returns through onStop; rollback similarly rejects later B editor; legitimate rollback A retained\nremovedA=$a\nremovedB=$b")
    }

    @Test fun restoreWaitsForSlowSaveAndAttachmentCallbacks() {
        qa()
        val file = File(ui.activity.getExternalFilesDir(null), "evidence/ui-roundtrip.kywb")
        assertTrue("Host must provide exact verified V1 backup", file.isFile)
        val manager = BackupManager(repo, ui.activity)
        val prepared = runBlocking { manager.inspect(file.readBytes(), "WorkbookQA-2026") }
        val saveId = newNote(); body("恢复期间旧保存任务的正文。"); saved(saveId)
        val saveEntered = AtomicBoolean(false)
        val releaseSave = CompletableDeferred<Unit>()
        try {
            repo.beforeNoteCommit = { saveEntered.set(true); withContext(NonCancellable) { releaseSave.await() } }
            body("恢复期间不能回流的旧版本。")
            waitFor { saveEntered.get() }
            ui.runOnUiThread { ui.activity.model.action { ui.activity.model.restoreData(manager, prepared) } }
            waitFor { ui.activity.model.transitioning }
            releaseSave.complete(Unit)
            waitFor { !ui.activity.model.transitioning && ui.activity.model.message == "完整恢复成功" }
            backgroundAndReturn()
            assertNull(snap().find(saveId)); assertTrue(snap().drafts.none { it.entry.id == saveId })
        } finally { releaseSave.complete(Unit); repo.beforeNoteCommit = null }

        createPhoto(); val attachmentId = newNote(); body("恢复期间附件旧回调验收。"); saved(attachmentId)
        val entered = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        try {
            repo.beforeAttachmentCommit = { entered.set(true); withContext(NonCancellable) { release.await() } }
            choosePhoto(); waitFor { entered.get() }
            ui.runOnUiThread { ui.activity.model.action { ui.activity.model.restoreData(manager, prepared) } }
            waitFor { ui.activity.model.transitioning }
            release.complete(Unit)
            waitFor { !ui.activity.model.transitioning && ui.activity.model.message == "完整恢复成功" }
            backgroundAndReturn()
            assertNull(ui.activity.model.editor)
            assertNull(snap().find(attachmentId)); assertTrue(snap().drafts.none { it.entry.id == attachmentId })
            assertTrue(snap().attachments.none { it.ownerId == attachmentId })
            shot("t10-late-operations-isolated")
            File(evidence, "t10-slow-callbacks.txt").writeText("PASS real Chinese editor and system photo picker with test-only noncancelable commit gates; production restore coordinator waits/cancels old work; onStop/reopen no old note/draft/attachment; current editor invalidated\noldSave=$saveId\noldAttachment=$attachmentId")
        } finally { release.complete(Unit); repo.beforeAttachmentCommit = null }
    }

    @Test fun dossiersRolesArchiveAndChineseCsvThroughUi() {
        qa(); val suffix = System.currentTimeMillis().toString().takeLast(6)
        home(); touch("nav_orgs"); text("新增单位"); genericField("title", "示例·V11回归单位$suffix")
        val orgId = ui.activity.model.editor!!.entry.id; genericSave()
        text("添加联系人"); genericField("title", "示例负责人"); genericField("role", "业务联系人")
        genericSave()
        home(); touch("nav_customers"); text("新增客户"); genericField("title", "示例·V11回归家庭$suffix")
        genericField("needs", "先了解服务安排，稍后再确认。")
        val customerId = ui.activity.model.editor!!.entry.id; genericSave()
        text("添加联系人"); genericField("title", "示例家属"); genericField("role", "女儿")
        genericSave()
        home(); touch("nav_customers"); tag("list_search").performTextReplacement("V11回归家庭$suffix")
        touch("entry_$customerId", scroll = true); text("添加服务对象")
        genericField("title", "示例服务对象"); genericField("relationship", "示例家属的母亲")
        val recipientId = ui.activity.model.editor!!.entry.id; genericSave()
        home(); touch("nav_customers"); tag("list_search").performTextReplacement("V11回归家庭$suffix")
        touch("entry_$customerId", scroll = true); touch("add_interaction", scroll = true)
        val customerNote = ui.activity.model.editor!!.entry.id
        body("客户沟通回归：已经说明服务安排，后续再确认。"); saved(customerNote)
        assertEquals(customerId, snap().find(customerNote)!!.parentId)
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        tag("entry_$customerNote").performScrollTo().assertIsDisplayed()
        val children = snap().entries.filter { it.parentId == customerId }.associateBy { it.id }
        assertEquals(customerId, snap().find(recipientId)!!.parentId)
        text("详细资料与更多操作"); touch("archive_entry", scroll = true); text("确认")
        waitFor { snap().find(customerId)?.archived == true }
        assertEquals(children, snap().entries.filter { it.parentId == customerId }.associateBy { it.id })
        home(); touch("nav_customers"); tag("list_search").performTextReplacement("V11回归家庭$suffix")
        tag("entry_$customerId").assertDoesNotExist()
        touch("list_filters"); ui.onNode(isToggleable()).performTouchInput { click() }; text("查看结果")
        tag("entry_$customerId").assertIsDisplayed()

        home(); touch("nav_orgs"); tag("list_search").performTextReplacement("V11回归单位$suffix")
        touch("entry_$orgId", scroll = true); touch("add_interaction", scroll = true)
        val orgNote = ui.activity.model.editor!!.entry.id
        body("=1+1,示例\"引号\"\n第二行中文导出核验"); saved(orgNote)
        assertEquals(orgId, snap().find(orgNote)!!.parentId)
        touch("note_done"); home(); touch("settings"); touch("open_export", scroll = true)
        touch("export_body", scroll = true); touch("preview_csv", scroll = true)
        shot("t12-readable-export-preview")
        val current = snap()
        val expected = Exports.csv(current, current.entries.filter { it.kind == Kind.INTERACTION }.sortedBy { it.occurredAt }, false, true)
        assertTrue(expected.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        assertTrue(expected.toString(Charsets.UTF_8).contains("'=1+1,示例\"\"引号\"\"\n第二行中文导出核验"))
        File(evidence, "v11-expected-export.csv").writeBytes(expected)
        val filename = "V11验收导出-$suffix.csv"
        touch("save_csv", scroll = true); savePicker(filename)
        waitFor { ui.activity.model.message == "文件已保存" }
        File(evidence, "t12-csv-filename.txt").writeText(filename)
        File(evidence, "t12-business-csv.txt").writeText("PASS real unit/customer/contact/recipient forms, distinct IDs, source-specific note, archive preserves relationships, archive filter, readable preview then actual SAF CSV save; expected BOM/quotes/newline/formula protection validated; host must compare saved bytes\norganization=$orgId\ncustomer=$customerId\nrecipient=$recipientId\ncsv=$filename\nexpectedSHA256=${AttachmentFiles.sha256(expected)}")
    }

    private fun savePicker(name: String) {
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000)
        val field = device.wait(Until.findObject(By.res("com.google.android.documentsui:id/title")), 5000)
            ?: device.findObject(By.clazz("android.widget.EditText"))
        checkNotNull(field) { "System save location picker did not appear" }.text = name
        (device.findObject(By.res("com.google.android.documentsui:id/action_menu_save"))
            ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("Save"))
            ?: device.findObject(By.text("保存")))!!.click()
        device.waitForIdle(); ui.waitForIdle()
    }

    @Test fun deniedReminderFromBlankEditorCreatesOnlyIndependentTask() {
        qa()
        val permission = "android.permission.POST_NOTIFICATIONS"
        check(InstrumentationRegistry.getArguments().getString("permissionDeniedPrepared") == "true") {
            "Host must revoke QA notification permission and clear user-set/user-fixed flags before launching this method; revoking inside instrumentation may kill its UID"
        }
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, ui.activity.checkSelfPermission(permission))
        val id = newNote(); val beforeTasks = snap().entries.filter { it.kind == Kind.TASK }.map { it.id }.toSet()
        val firstTitle = "示例·先有独立跟进，还没写正文"
        val secondTitle = "示例·同一空白记录再加一条独立跟进"
        touch("note_followup"); tag("followup_title").performTextReplacement(firstTitle)
        touch("followup_tomorrow"); closeKeyboardIfVisible(); touch("followup_reminder", scroll = true)
        val deny = device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("com.android.permissioncontroller:id/permission_deny.*"))), 6000)
        checkNotNull(deny) { "Expected actual Android notification permission dialog" }.click()
        ui.waitForIdle(); shot("t12-notification-denied-still-editable")
        touch("followup_submit", scroll = true)
        waitFor { snap().entries.any { it.kind == Kind.TASK && it.id !in beforeTasks } }
        waitFor { !tag("followup_submit").isDisplayed() }
        val firstTask = snap().entries.single { it.kind == Kind.TASK && it.id !in beforeTasks }
        assertEquals(firstTitle, firstTask.title)
        assertNull(firstTask.sourceId); assertNull(snap().find(id))
        assertEquals(id, ui.activity.model.editor?.entry?.id)

        // Stay in the same empty note: a new panel must create a different task,
        // not report success after returning the first task's deterministic ID.
        touch("note_followup"); tag("followup_title").performTextReplacement(secondTitle)
        touch("followup_tomorrow"); closeKeyboardIfVisible()
        touch("followup_submit", scroll = true)
        waitFor { snap().entries.count { it.kind == Kind.TASK && it.id !in beforeTasks } == 2 }
        waitFor { !tag("followup_submit").isDisplayed() }
        val tasks = snap().entries.filter { it.kind == Kind.TASK && it.id !in beforeTasks }
        assertEquals(2, tasks.map { it.id }.toSet().size)
        assertEquals(setOf(firstTitle, secondTitle), tasks.map { it.title }.toSet())
        tasks.forEach { assertNull(it.sourceId) }
        assertEquals(firstTitle, snap().find(firstTask.id)?.title)
        assertEquals(id, ui.activity.model.editor?.entry?.id)
        assertNull(snap().find(id))
        touch("note_done"); waitFor { ui.activity.model.editor == null }
        assertNull(snap().find(id))
        assertEquals(2, snap().entries.count { it.kind == Kind.TASK && it.id !in beforeTasks })
        home(); touch("settings"); tag("enable_notifications").performScrollTo().assertExists()
        File(evidence, "t12-denied-independent-task.txt").writeText("PASS actual Android permission denied; two independent TASKs with distinct IDs and both titles saved from the same empty note; blank note never created; enabling notification remains available\ntasks=${tasks.joinToString { it.id }}")
    }

    @Test fun representativeWidthAndFontLayout() {
        qa(); val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("layoutLabel") ?: "unspecified"
        // UIAutomator initialization can reset the host's rotation lock. Rotate the
        // launched activity, then verify the real Android resources before testing.
        if(label.startsWith("landscape")) device.setOrientationLeft() else device.setOrientationNatural()
        ui.waitForIdle()
        arguments.getString("expectedWidthDp")?.toInt()?.let { expected ->
            waitFor { kotlin.math.abs(ui.activity.resources.configuration.screenWidthDp - expected) <= 1 }
        }
        val config = ui.activity.resources.configuration
        arguments.getString("expectedWidthDp")?.toInt()?.let {
            assertTrue("Actual Android configuration must match the requested width", kotlin.math.abs(config.screenWidthDp - it) <= 1)
        }
        arguments.getString("expectedFontScale")?.toFloat()?.let { assertEquals("Actual font scale", it, config.fontScale, .01f) }
        val longName = "示例·武汉市社区康养服务与养老机构康复医院协作交流中心（长名称换行和大字号可访问性验收专用）"
        if (snap().entries.none { it.kind == Kind.ORGANIZATION && it.title == longName }) {
            home(); touch("nav_orgs"); text("新增单位"); genericField("title", longName); genericSave()
        }
        val longObject = snap().entries.single { it.kind == Kind.ORGANIZATION && it.title == longName }
        home(); tag("quick_record").assertIsDisplayed(); shot("t11-$label-home-unscrolled")
        touch("quick_record"); waitFor { ui.activity.model.editor != null }
        tag("note_body").assertIsFocused()
        val id = ui.activity.model.editor!!.entry.id
        val long = "示例长文：拜访后的需求、合作条件和下一步都能完整阅读。\n".repeat(65)
        body(long); saved(id, long)
        tag("note_done").assertIsDisplayed(); tag("note_link").assertIsDisplayed()
        shot("t11-$label-body-ime")
        touch("note_link"); tag("object_search").performTextReplacement("长名称换行和大字号")
        tag("object_${longObject.id}").performScrollTo().assertIsDisplayed()
        shot("t11-$label-object-panel")
        touch("object_${longObject.id}", scroll = true)
        closeKeyboardIfVisible(); touch("note_done"); waitFor { ui.activity.model.editor == null }
        home(); touch("entry_$id", scroll = true); tag("note_detail_body").assertTextEquals(long)
        shot("t11-$label-long-detail")
        ui.onNodeWithText("更多信息").performScrollTo().performTouchInput { click() }
        touch("edit_entry"); waitFor { ui.activity.model.editor != null }
        // Wait for the editor's asynchronous initial IME before dismissing it;
        // otherwise a late keyboard moves the date target after its tap coordinate.
        waitFor {
            var visible=false
            ui.runOnUiThread { visible=ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true }
            visible
        }
        closeKeyboardIfVisible()
        waitFor {
            var visible=true
            ui.runOnUiThread { visible=ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true }
            !visible
        }
        touch("note_date", scroll = true)
        shot("t11-$label-date-panel")
        touch("occur_date", scroll = true); shot("t11-$label-date-picker")
        touch("date_yesterday", scroll = true)
        touch("occur_time", scroll = true); shot("t11-$label-time-input")
        touch("time_confirm", scroll = true)
        touch("occur_save", scroll = true); touch("note_done")
        home(); touch("settings"); touch("open_backup", scroll = true)
        shot("t11-$label-backup-theme")
        val c = ui.activity.resources.configuration
        File(evidence, "t11-$label-config.txt").writeText("widthDp=${c.screenWidthDp}\nheightDp=${c.screenHeightDp}\nfontScale=${c.fontScale}\ndensityDpi=${c.densityDpi}\norientation=${c.orientation}\nexpectedWidthDp=${arguments.getString("expectedWidthDp")}\nexpectedFontScale=${arguments.getString("expectedFontScale")}\nPASS real home/no-scroll; body+IME; long text; actual long-name object panel selection; date picker and time input confirm reachable; backup screen\n")
    }

    /** Run before starting screenrecord, so the recording opens on the untouched home viewport. */
    @Test fun prepareFinalDeliveryOrganization() {
        qa(); home(); touch("nav_orgs"); text("新增单位")
        genericField("title", "示例·江城康养中心 ${System.currentTimeMillis().toString().takeLast(5)}")
        val id = ui.activity.model.editor!!.entry.id; genericSave()
        writeIdentity("final-organization", snap().find(id)!!); home()
    }

    @Test fun finalDeliveryWalkthroughAndEvidence() {
        qa(); check(BuildConfig.VERSION_CODE >= 5) { "Capture final evidence only on the final delivery build" }
        val org = readIdentity("final-organization")
        assertEquals(org, snap().find(org.id))
        fun hold() { Thread.sleep(1_200) }
        home(); tag("quick_record").assertIsDisplayed()
        shot("final-home-unscrolled"); hold()
        touch("quick_record"); waitFor { ui.activity.model.editor != null }
        tag("note_body").assertIsFocused()
        waitFor {
            var visible = false
            ui.runOnUiThread { visible = ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            visible
        }
        shot("final-one-tap-body-keyboard"); hold()
        val id = ui.activity.model.editor!!.entry.id
        val content = "周院长希望先看服务介绍，下周再联系。"
        body(content); saved(id, content); hold(); shot("final-body-really-saved")
        touch("note_back"); waitFor { ui.activity.model.route == "home" }
        tag("entry_$id").assertIsDisplayed(); shot("final-saved-note-on-home"); hold()
        touch("entry_$id"); tag("note_detail_body").assertTextEquals(content)
        shot("final-body-first-detail"); hold()
        touch("edit_entry")
        waitFor { ui.activity.model.editor != null && tag("note_link").isDisplayed() }
        touch("note_link")
        tag("object_search").performTextReplacement(org.title)
        tag("object_${org.id}").performScrollTo().assertIsDisplayed()
        shot("final-searchable-association-panel"); hold()
        touch("object_${org.id}", scroll = true)
        waitFor { snap().find(id)?.parentId == org.id }
        touch("note_followup"); tag("followup_title").performTextReplacement("给周院长发送服务介绍")
        touch("followup_tomorrow"); closeKeyboardIfVisible()
        shot("final-tomorrow-followup-panel"); hold()
        touch("followup_submit", scroll = true)
        waitFor { snap().entries.any { it.kind == Kind.TASK && it.sourceId == id } }
        val task = snap().entries.single { it.kind == Kind.TASK && it.sourceId == id }
        assertEquals(TimeRules.today().plusDays(1).toString(), task["date"])
        assertEquals(content, snap().find(id)!!["content"])
        hold(); touch("note_done"); waitFor { ui.activity.model.editor == null }
        home(); touch("entry_$id", scroll = true)
        shot("final-note-and-explicit-followup"); hold()
        val c = ui.activity.resources.configuration
        val metrics = ui.activity.resources.displayMetrics
        val identity = mapOf(
            "applicationId" to ui.activity.packageName, "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE.toString(), "api" to android.os.Build.VERSION.SDK_INT.toString(),
            "model" to android.os.Build.MODEL, "widthDp" to c.screenWidthDp.toString(), "heightDp" to c.screenHeightDp.toString(),
            "pixelWidth" to metrics.widthPixels.toString(), "pixelHeight" to metrics.heightPixels.toString(),
            "densityDpi" to c.densityDpi.toString(), "fontScale" to c.fontScale.toString(),
            "installedApkSHA256" to AttachmentFiles.sha256(File(ui.activity.applicationInfo.sourceDir)),
            "noteId" to id, "organizationId" to org.id, "taskId" to task.id,
            "result" to "PASS real UI touch, keyboard, Chinese body, committed save, home retrieval, body-first read, association, explicit tomorrow TASK",
        )
        File(evidence, "final-walkthrough-identity.json").writeText(dataJson.encodeToString(identity))
    }

    @Test fun finalPersonalUpgradeSmoke() {
        check(ui.activity.packageName == "cn.kangyang.workbook")
        val before = snap()
        assertTrue("Personal V1 records must remain after covering install", before.entries.isNotEmpty())
        home(); tag("quick_record").assertIsDisplayed(); shot("personal-v11-home-unscrolled")
        touch("quick_record"); waitFor { ui.activity.model.editor != null }
        val id = ui.activity.model.editor!!.entry.id
        val content = "V1.1安装验收：先记一句，返回后继续查看。这是模拟器示例。"
        body(content); saved(id, content); touch("note_back")
        waitFor { ui.activity.model.editor == null }
        before.entries.forEach { assertEquals("Preexisting personal entry changed", it, snap().find(it.id)) }
        tag("entry_$id").assertIsDisplayed(); touch("entry_$id")
        tag("note_detail_body").assertTextEquals(content)
        writeIdentity("personal-note", snap().find(id)!!)
        shot("personal-v11-note")
        File(evidence, "personal-upgrade-smoke.txt").writeText("PASS personal package actual UI; all preexisting entries preserved; created, returned and read same committed ID\nid=$id\npriorEntries=${before.entries.size}")
        // The host performs ordinary HOME/reopen after instrumentation has finished.
    }

    @Test fun finalPersonalReopenWithoutDestructiveTesting() {
        check(ui.activity.packageName == "cn.kangyang.workbook")
        val expected = readIdentity("personal-note")
        assertEquals(expected, snap().find(expected.id))
        home(); touch("entry_${expected.id}", scroll = true)
        tag("note_detail_body").assertTextEquals(expected["content"])
        shot("personal-v11-reopened")
        File(evidence, "personal-reopen.txt").writeText("PASS same committed personal note after ordinary close/reopen; no restore/rollback/fault injection\nid=${expected.id}")
    }
}
