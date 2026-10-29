package cn.kangyang.workbook

import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Repository
import cn.kangyang.workbook.data.Snapshot
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Run once per host-selected wm/font configuration. This test never changes global device settings.
 * Existing business content is read only. Its only business write is a clearly labelled quick note.
 * Restore preview reuses 康养验收备份.kywb from the original E10 system-file export, whose verified
 * copy is evidence/ui-roundtrip.kywb. It cancels the preview and never restores the QA database.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LayoutFlowTest {
    @get:Rule(order = 0) val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val evidenceDir get() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private val label: String get() = (arguments.getString("layoutLabel")
        ?: "${context.resources.configuration.screenWidthDp}dp-${context.resources.configuration.fontScale}")
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private val configFile get() = File(evidenceDir, "e14-$label-config.txt")

    @get:Rule(order = 1) val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val prefix = "e14-$label-FAIL"
            runCatching { device.takeScreenshot(File(evidenceDir, "$prefix.png")) }
            runCatching { device.dumpWindowHierarchy(File(evidenceDir, "$prefix.window.xml")) }
            runCatching {
                File(evidenceDir, "$prefix.compose.txt").writeText(
                    ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 100),
                    Charsets.UTF_8,
                )
            }
            runCatching {
                File(evidenceDir, "$prefix.error.txt").writeText(
                    "${error.stackTraceToString()}\nroute=${ui.activity.model.route}\neditor=${ui.activity.model.editor}\nmessage=${ui.activity.model.message}",
                    Charsets.UTF_8,
                )
                record("instrumentation=FAIL ${description.methodName}: ${error.message}")
            }
        }
    }

    @Test fun captureConfiguredLayoutAndKeyboardSave() {
        check(context.packageName.endsWith(".qa")) { "LayoutFlowTest requires the independent .qa package" }
        ui.waitForIdle()
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        configFile.writeText(
            "label=$label\npackage=${context.packageName}\napi=${Build.VERSION.SDK_INT}\n" +
                "screenWidthDp=${configuration.screenWidthDp}\nscreenHeightDp=${configuration.screenHeightDp}\n" +
                "smallestScreenWidthDp=${configuration.smallestScreenWidthDp}\nfontScale=${configuration.fontScale}\n" +
                "densityDpi=${metrics.densityDpi}\ndensity=${metrics.density}\n" +
                "pixelWidth=${metrics.widthPixels}\npixelHeight=${metrics.heightPixels}\n" +
                "displayWidth=${device.displayWidth}\ndisplayHeight=${device.displayHeight}\n" +
                "orientation=${configuration.orientation}\nstartedAt=${System.currentTimeMillis()}\n" +
                "globalSettingsChangedByTest=false\n",
            Charsets.UTF_8,
        )
        arguments.getString("expectedWidthDp")?.toIntOrNull()?.let { expected ->
            assertTrue("Configured width ${configuration.screenWidthDp}dp differs from $expected", abs(configuration.screenWidthDp - expected) <= 2)
        }
        arguments.getString("expectedFontScale")?.toFloatOrNull()?.let { expected ->
            assertTrue("Configured fontScale ${configuration.fontScale} differs from $expected", abs(configuration.fontScale - expected) <= .03f)
        }

        val initial = snapshot()
        val organizations = initial.entries.filter { it.kind == Kind.ORGANIZATION }
        val organization = organizations.maxWithOrNull(compareBy<Entry> { candidate ->
            initial.entries.count { it.kind == Kind.INTERACTION && it.parentId == candidate.id }
        }.thenBy { it.title.length }) ?: error("No real UI-created organization is available for layout review")
        val visits = initial.entries.filter { it.kind == Kind.INTERACTION && it.parentId == organization.id }.sortedByDescending { it.occurredAt }
        check(visits.size >= 2) { "The unit timeline layout requires at least two UI-created visits" }
        val longNote = initial.entries.filter { it.kind == Kind.INTERACTION }.maxByOrNull { it["content"].length }
            ?: error("No communication is available")
        check(longNote["content"].length >= 2_000) { "The long-text layout requires a real saved note of at least 2000 characters" }
        val activity = initial.entries.filter { it.kind == Kind.ACTIVITY && it["plan"].isNotBlank() }.maxByOrNull { it["plan"].length }
            ?: error("No activity with a saved plan is available")
        record("organization=${organization.id};titleChars=${organization.title.length};visits=${visits.size};archived=${organization.archived}")
        record("longNote=${longNote.id};contentChars=${longNote["content"].length}")
        record("activity=${activity.id};planChars=${activity["plan"].length}")

        root("home")
        assertControl("quick_record", scroll = true)
        listOf("nav_home", "nav_orgs", "nav_customers", "nav_activities").forEach { assertControl(it) }
        shot("home")

        root("orgs")
        tag("list_search").performScrollTo().performTextReplacement(organization.title)
        if (organization.archived) ui.onNode(isToggleable()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        closeSoftKeyboard()
        tag("entry_${organization.id}").performScrollTo().assertIsDisplayed()
        shot("unit-list")
        click("entry_${organization.id}", scroll = true)
        assertControl("edit_entry")
        shot("unit-detail-top")
        ui.onNodeWithText("拜访时间线").performScrollTo().assertIsDisplayed()
        tag("entry_${visits.first().id}").performScrollTo().assertIsDisplayed()
        shot("unit-timeline")
        tag("entry_${visits.last().id}").performScrollTo().assertIsDisplayed()
        shot("unit-timeline-earlier")

        openGlobalEntry(longNote, longNote["content"].lineSequence().first().take(35))
        assertControl("edit_entry")
        shot("long-note-top")
        tag("export_summary").performScrollTo().assertIsDisplayed()
        shot("long-note-bottom")
        click("edit_entry")
        tag("field_content").performScrollTo().assertTextContains(longNote["content"], substring = true)
        assertControl("save_entry")
        shot("long-editor")
        // Merely opening the existing editor does not change its record or create a draft revision.
        root("home")
        assertEquals(longNote, snapshot().find(longNote.id))

        // Dedicated new note: real keyboard is shown, Chinese is entered, Save stays reachable.
        click("quick_record", scroll = true)
        click("quick_临时速记")
        val noteText = "示例·字号适配验收 $label ${UUID.randomUUID().toString().take(6)}\n键盘打开时输入中文并保存，已有工作资料保持原样。"
        tag("field_content").performScrollTo().performClick()
        ui.waitUntil(10_000) { keyboardVisible() }
        tag("field_content").performTextReplacement(noteText)
        ui.waitForIdle()
        assertTrue("Keyboard must still be visible for this save check", keyboardVisible())
        assertControl("save_entry")
        shot("keyboard-form")
        val noteId = ui.runOnIdle { ui.activity.model.editor!!.entry.id }
        tag("save_entry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitUntil(15_000) { ui.activity.model.route == "detail:$noteId" }
        assertEquals(noteText, snapshot().find(noteId)!!["content"])
        assertControl("edit_entry")
        shot("keyboard-saved")
        record("keyboardVisibleBeforeSave=true;keyboardSave=PASS;newLayoutNote=$noteId")

        root("activities")
        tag("list_search").performScrollTo().performTextReplacement(activity.title)
        closeSoftKeyboard()
        click("entry_${activity.id}", scroll = true)
        assertControl("edit_entry")
        shot("activity-top")
        ui.onNodeWithText(activity["plan"]).performScrollTo().assertIsDisplayed()
        shot("activity-plan")
        tag("export_summary").performScrollTo().assertIsDisplayed()
        shot("activity-bottom")

        root("home")
        click("settings")
        click("open_backup", scroll = true)
        tag("backup_password").performScrollTo().assertIsDisplayed()
        shot("backup-top")
        assertControl("create_backup", scroll = true)
        assertControl("inspect_backup", scroll = true)
        shot("backup-actions")
        previewExistingBackupThroughPicker()

        record("instrumentation=PASS;geometry=key_controls_visible_and_within_horizontal_viewport")
        record("visualReview=Screenshots require visual review; instrumentation alone does not prove every glyph is unclipped")
        record("finishedAt=${System.currentTimeMillis()}")
    }

    private fun previewExistingBackupThroughPicker() {
        // Reuse the original E10 SAF export. ui-roundtrip.kywb is the host-verified copy/marker
        // of that same export; this layout test creates no new document and deletes no document.
        val marker = File(evidenceDir, "ui-roundtrip.kywb")
        if (!marker.isFile) {
            record("restorePreview=NOT_RUN;reason=ui-roundtrip.kywb is absent;restoreEntryScreenshot=backup-actions")
            return
        }
        val name = "康养验收备份.kywb"
        val fileTitles = listOf(By.res("android:id/title").text(name), By.res("com.google.android.documentsui:id/title").text(name))
        record("restorePreviewFixture=reuse_original_E10_SAF_export;documentName=$name;verifiedMarker=ui-roundtrip.kywb;createdOrDeletedDocuments=false")
        tag("backup_password").performScrollTo().performTextReplacement("WorkbookQA-2026")
        closeSoftKeyboard()
        val before = snapshot()
        click("inspect_backup", scroll = true)
        device.waitForIdle()
        fun fileVisible(): Boolean = fileTitles.any { device.wait(Until.hasObject(it), 1_000) }
        if (!fileVisible()) {
            val isRecent = device.hasObject(By.text("Recent")) || device.hasObject(By.text("最近")) || device.hasObject(By.text("最近使用"))
            if (!isRecent) {
                clickFreshPickerTarget("打开文件位置", listOf(By.desc("Show roots"), By.desc("显示根目录")), required = false)
                clickFreshPickerTarget("最近文件", listOf(By.res("android:id/title").text("Recent"), By.text("Recent"), By.text("最近"), By.text("最近使用")))
            }
            if (!fileVisible()) {
                clickFreshPickerTarget("系统文件搜索", listOf(
                    By.res("com.google.android.documentsui:id/option_menu_search"),
                    By.res("com.android.documentsui:id/option_menu_search"),
                    By.desc("Search"), By.desc("搜索"),
                ))
                setFreshPickerSearch(name)
            }
        }
        shot("restore-file-picker", systemSurface = true)
        // Fresh title lookup after screenshot; selecting it opens the real production inspector.
        clickFreshPickerTarget("已实际导出的验收备份", fileTitles)
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("confirm_restore").fetchSemanticsNodes().isNotEmpty() }
        tag("confirm_restore").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText("确认完整替换恢复").assertIsDisplayed()
        shot("restore-preview")
        val cancel = ui.onNode(hasText("取消") and hasAnyAncestor(isDialog()))
        cancel.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
        assertEquals("Preview and cancel must not replace current data", before, snapshot())
        record("restorePreview=PASS;source=$name;sameOriginalExportAs=ui-roundtrip.kywb;path=real_SAF_to_production_BackupManager.inspect;confirmation=cancelled;dataUnchanged=true")
    }

    private fun setFreshPickerSearch(query: String) {
        val selectors = listOf(By.res("android:id/search_src_text"), By.res("com.google.android.documentsui:id/search_src_text"), By.clazz("android.widget.EditText"))
        var lastStale: StaleObjectException? = null
        repeat(3) { attempt ->
            device.waitForIdle()
            val field = selectors.firstNotNullOfOrNull { device.wait(Until.findObject(it), 2_000) }
                ?: throw AssertionError("系统文件搜索框未打开", lastStale)
            try {
                field.text = query
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
                device.waitForIdle()
                record("pickerSearchExactName=$query;attempt=${attempt + 1}")
                return
            } catch (error: StaleObjectException) {
                lastStale = error
            }
        }
        throw AssertionError("系统文件搜索框连续3次失效", lastStale)
    }

    private fun clickFreshPickerTarget(description: String, selectors: List<BySelector>, required: Boolean = true): Boolean {
        var lastStale: StaleObjectException? = null
        repeat(3) { attempt ->
            device.waitForIdle()
            val fresh = selectors.firstNotNullOfOrNull { selector ->
                device.wait(Until.findObject(selector), 2_000)
            }
            if (fresh == null) {
                if (!required && lastStale == null) return false
                throw AssertionError("系统文件选择器没有显示${description}；未忽略未打开或找不到文件的情况", lastStale)
            }
            try {
                fresh.click()
                // Wait for the drawer/directory transition before looking up the next target.
                device.waitForIdle()
                record("pickerClick=$description;freshLookupAttempt=${attempt + 1}")
                return true
            } catch (error: StaleObjectException) {
                lastStale = error
                record("pickerRetry=$description;staleAttempt=${attempt + 1}")
            }
        }
        throw AssertionError("系统文件选择器的${description}在3次重新查询后仍失效", lastStale)
    }

    private fun openGlobalEntry(entry: Entry, query: String) {
        root("home")
        click("search_all")
        tag("global_search").performTextReplacement(query)
        if (entry.archived) ui.onNode(isToggleable()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        closeSoftKeyboard()
        click("entry_${entry.id}", scroll = true)
    }

    private fun snapshot(): Snapshot = runBlocking { Repository.get(context).snapshot() }
    private fun tag(value: String) = ui.onNodeWithTag(value)

    private fun click(value: String, scroll: Boolean = false) {
        val node = tag(value)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
    }

    private fun assertControl(value: String, scroll: Boolean = false) {
        val node = tag(value)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val width = ui.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        assertTrue("$value extends left of viewport: $bounds", bounds.left >= -1f)
        assertTrue("$value extends right of viewport ($width): $bounds", bounds.right <= width + 1f)
    }

    private fun root(tab: String) {
        closeSoftKeyboard()
        repeat(60) {
            if (ui.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()) {
                click("nav_$tab")
                return
            }
            ui.onNodeWithContentDescription("返回").performSemanticsAction(SemanticsActions.OnClick) { it() }
            ui.waitForIdle()
        }
        error("Main navigation was not reachable")
    }

    private fun keyboardVisible(): Boolean = ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun shot(name: String, systemSurface: Boolean = false) {
        if (systemSurface) device.waitForIdle() else ui.waitForIdle()
        check(device.takeScreenshot(File(evidenceDir, "e14-$label-$name.png"))) { "Cannot capture $name" }
    }

    private fun record(line: String) = configFile.appendText("$line\n", Charsets.UTF_8)
}
