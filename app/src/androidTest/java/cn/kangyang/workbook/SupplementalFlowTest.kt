package cn.kangyang.workbook

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Repository
import cn.kangyang.workbook.data.Snapshot
import cn.kangyang.workbook.domain.TimeRules
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** All mutations use the installed QA app's visible forms/actions; repository access is read-only. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SupplementalFlowTest {
    @get:Rule(order = 0) val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val suffix = UUID.randomUUID().toString().take(7)
    private val evidenceDir get() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }

    @get:Rule(order = 1) val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val name = "FAIL-supplemental-${description.methodName}-$suffix"
            runCatching { device.takeScreenshot(File(evidenceDir, "$name.png")) }
            runCatching { device.dumpWindowHierarchy(File(evidenceDir, "$name.window.xml")) }
            runCatching { File(evidenceDir, "$name.compose.txt").writeText(ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 100)) }
            runCatching { File(evidenceDir, "$name.error.txt").writeText(error.stackTraceToString()) }
            runCatching { evidence("FAIL ${description.methodName}; run=$suffix; ${error.message}") }
        }
    }

    @Before fun guardQa() {
        check(context.packageName.endsWith(".qa")) { "SupplementalFlowTest is restricted to the independent .qa package" }
        ui.waitForIdle()
        evidence("START package=${context.packageName}; run=$suffix; no data or settings cleared")
    }

    @Test fun offlineCompletionAndDateFiltering() {
        // The external driver owns disconnect/reconnect. This test never changes radio settings.
        val wifi = device.executeShellCommand("settings get global wifi_on").trim()
        val mobile = device.executeShellCommand("settings get global mobile_data").trim()
        val airplane = device.executeShellCommand("settings get global airplane_mode_on").trim()
        evidence("OFFLINE_PRECONDITION wifi_on=$wifi mobile_data=$mobile airplane_mode_on=$airplane")
        assertEquals("External driver must disable Wi-Fi before the offline scenario", "0", wifi)
        assertEquals("External driver must disable mobile data before the offline scenario", "0", mobile)

        val today = TimeRules.today()
        root("home")
        clickText("全部待办", scroll = true)
        clickText("新增")
        fill("title", "示例·离线日期跟进·$suffix")
        fill("date", today.toString())
        fill("time", "")
        val task = save()
        assertEquals(today.toString(), task["date"])
        assertTrue(task["time"].isBlank())
        assertEquals("今日", TimeRules.taskBucket(task))
        root("home")
        clickText("全部待办", scroll = true)
        clickTag("bucket_今日", scroll = true)
        clickTag("entry_${task.id}", scroll = true)
        screenshot("E07-offline-date-only-today")
        clickTag("complete_task", scroll = true)
        ui.waitUntil(15_000) { snapshot().find(task.id)?.get("status") == "已完成" }
        root("home")
        clickText("全部待办", scroll = true)
        clickTag("bucket_已完成", scroll = true)
        ui.onNodeWithTag("entry_${task.id}").performScrollTo().assertIsDisplayed()
        screenshot("E07-offline-task-completed")

        val organization = newOrganization("示例·离线历史单位·$suffix")
        val marker = "离线日期检索-$suffix"
        val current = newVisit(organization, "示例·今日沟通·$suffix", today.toString(), "14:30", "$marker：今天沟通，先保存这一条。")
        val historicalDay = today.minusDays(2)
        val historical = newVisit(organization, "示例·历史补记·$suffix", historicalDay.toString(), "09:20", "$marker：补记前两天的面谈，发生日期与创建日期不同。")
        assertTrue("The historical row was entered after the current row", historical.createdAt >= current.createdAt)
        assertTrue("Its business occurrence must still be earlier", historical.occurredAt < current.occurredAt)
        assertEquals(historicalDay.toString(), TimeRules.formatDate(historical.occurredAt))
        assertEquals(today.toString(), TimeRules.formatDate(historical.createdAt))

        // An actual offline edit follows the same form/save path and retains the stable record ID.
        clickTag("edit_entry")
        fill("content", historical["content"] + "\n离线补充：确认下次由示例联系人接洽。")
        val edited = save()
        assertEquals(historical.id, edited.id)
        assertEquals(historical.occurredAt, edited.occurredAt)
        assertTrue(edited["content"].contains("离线补充"))
        openListEntry("orgs", organization)
        ui.onNodeWithText("拜访时间线").performScrollTo()
        val timelineOrder = ui.onAllNodes(hasTestTag("entry_${current.id}") or hasTestTag("entry_${historical.id}"))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals("Timeline must use occurrence order even though the historical row was created later", listOf("entry_${current.id}", "entry_${historical.id}"), timelineOrder)
        screenshot("E07-offline-occurrence-timeline")

        root("home")
        clickTag("search_all")
        ui.onNodeWithTag("global_search").performTextReplacement(marker)
        fillSearchDate("记录开始日期 yyyy-MM-dd", historicalDay.toString())
        fillSearchDate("记录结束日期 yyyy-MM-dd", historicalDay.toString())
        closeSoftKeyboard()
        ui.onNodeWithTag("entry_${current.id}").assertDoesNotExist()
        ui.onNodeWithText("沟通记录 · 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("entry_${historical.id}").performScrollTo().assertIsDisplayed()
        screenshot("E07-offline-historical-date-filter")
        searchDateNode("记录开始日期 yyyy-MM-dd").performScrollTo()
        screenshot("E07-offline-search-date-range")

        fillSearchDate("记录开始日期 yyyy-MM-dd", today.toString())
        fillSearchDate("记录结束日期 yyyy-MM-dd", today.toString())
        closeSoftKeyboard()
        ui.onNodeWithTag("entry_${historical.id}").assertDoesNotExist()
        ui.onNodeWithTag("entry_${current.id}").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("沟通记录 · 1").performScrollTo().assertIsDisplayed()
        screenshot("E07-offline-today-filter-excludes-later-created-history")
        evidence("PASS offlineCompletionAndDateFiltering; task=${task.id} date=$today time=empty completed through UI; historical=${historical.id} occurred=${TimeRules.formatDateTime(historical.occurredAt)} created=${TimeRules.formatDateTime(historical.createdAt)}; current=${current.id}; timeline and both date ranges verified; radios left unchanged wifi_on=$wifi mobile_data=$mobile")
    }

    @Test fun activitySummaryPreviewAndFilteredCsv() {
        val activity = snapshot().entries.firstOrNull {
            it.kind == Kind.ACTIVITY && it.title.startsWith("示例") && it["plan"].isNotBlank() && it["materials"].isNotBlank()
        } ?: error("Run the real activity business flow first: a synthetic activity with plan and materials is required")
        openListEntry("activities", activity)
        clickTag("export_summary", scroll = true)
        ui.onNodeWithTag("export_body").assertIsOff()
        ui.onNodeWithTag("export_sensitive").assertIsOff()
        val defaultPreview = summaryText()
        assertTrue(defaultPreview.contains(activity.title))
        assertFalse("Detailed plan is private until explicitly selected", defaultPreview.contains(activity["plan"]))
        assertFalse("Materials are not included by default", defaultPreview.contains(activity["materials"]))
        assertFalse(defaultPreview.contains("联系人："))
        assertFalse(defaultPreview.contains("服务对象："))
        screenshot("E10-activity-summary-default-private")
        clickTag("export_body", scroll = true)
        ui.onNodeWithTag("export_body").assertIsOn()
        ui.onNodeWithTag("export_sensitive").assertIsOff()
        val bodyPreview = summaryText()
        assertTrue(bodyPreview.contains("方案与议程：${activity["plan"]}"))
        assertTrue(bodyPreview.contains("物料清单：${activity["materials"]}"))
        ui.onNodeWithTag("summary_preview").performScrollTo()
        screenshot("E10-activity-summary-plan-and-materials")
        File(evidenceDir, "activity-summary-selected-$suffix.txt").writeText(bodyPreview)
        clickDialogText("关闭")

        // Real forms establish deterministic same-day visits for two distinct objects, plus an
        // excluded-date visit. Existing QA data is retained and included in the all-object count.
        val firstOrg = newOrganization("示例·导出范围甲·$suffix")
        val secondOrg = newOrganization("示例·导出范围乙·$suffix")
        val selectedDay = TimeRules.today().minusDays(6)
        val excludedDay = selectedDay.minusDays(1)
        val firstVisit = newVisit(firstOrg, "示例·范围内甲拜访·$suffix", selectedDay.toString(), "09:30", "范围验收甲记录：中文、逗号,和\"双引号\"。")
        val outside = newVisit(firstOrg, "示例·范围外旧拜访·$suffix", excludedDay.toString(), "10:00", "这条属于同一对象，但不在所选日期内。")
        val secondVisit = newVisit(secondOrg, "示例·范围内乙拜访·$suffix", selectedDay.toString(), "15:00", "这条日期相同，但属于另一个对象。")

        root("home")
        clickTag("settings")
        clickTag("open_export", scroll = true)
        val allCount = snapshot().entries.count { it.kind == Kind.INTERACTION }
        ui.onNodeWithTag("export_count").performScrollTo().assertTextEquals("当前范围 $allCount 条记录")
        ui.onNodeWithTag("export_from").performScrollTo().performTextReplacement(selectedDay.toString())
        ui.onNodeWithTag("export_to").performScrollTo().performTextReplacement(selectedDay.toString())
        closeSoftKeyboard()
        val dateCount = snapshot().entries.count { it.kind == Kind.INTERACTION && TimeRules.formatDate(it.occurredAt) == selectedDay.toString() }
        assertTrue(dateCount >= 2)
        ui.onNodeWithTag("export_count").performScrollTo().assertTextEquals("当前范围 $dateCount 条记录")
        clickText("全部对象", scroll = true)
        clickDialogText(firstOrg.title + " · " + firstOrg["area"], scroll = true)
        ui.onNodeWithTag("export_count").performScrollTo().assertTextEquals("当前范围 1 条记录")
        ui.onNodeWithTag("export_body").assertIsOff()
        ui.onNodeWithTag("export_sensitive").assertIsOff()
        ui.onNodeWithTag("export_from").performScrollTo()
        screenshot("E10-csv-date-and-object-range")
        clickTag("preview_csv", scroll = true)
        val csvNode = ui.onNode(hasText("记录名称", substring = true) and hasText("发生时间（北京时间）", substring = true))
        val firstCsv = textOf(csvNode)
        assertTrue(firstCsv.startsWith("\uFEFF"))
        assertTrue(firstCsv.contains(firstVisit.title))
        assertFalse(firstCsv.contains(outside.title))
        assertFalse(firstCsv.contains(secondVisit.title))
        assertFalse(firstCsv.contains("联系人电话"))
        assertFalse(firstCsv.contains("服务对象照护信息"))
        assertEquals("Header plus one selected row", 2, firstCsv.trimEnd().split("\r\n").size)
        csvNode.performScrollTo()
        screenshot("E10-csv-one-selected-record-preview")
        File(evidenceDir, "csv-selected-range-$suffix.txt").writeText(firstCsv)

        clickText(firstOrg.title, scroll = true)
        clickDialogText(secondOrg.title + " · " + secondOrg["area"], scroll = true)
        ui.onNodeWithTag("export_count").performScrollTo().assertTextEquals("当前范围 1 条记录")
        val secondCsv = textOf(ui.onNode(hasText("记录名称", substring = true) and hasText("发生时间（北京时间）", substring = true)))
        assertTrue(secondCsv.contains(secondVisit.title))
        assertFalse(secondCsv.contains(firstVisit.title))
        assertFalse(secondCsv.contains(outside.title))
        screenshot("E10-csv-second-object-range")
        clickText("重置为全部记录", scroll = true)
        ui.onNodeWithTag("export_count").performScrollTo().assertTextEquals("当前范围 $allCount 条记录")
        evidence("PASS activitySummaryPreviewAndFilteredCsv; activity=${activity.id}; default body/sensitive off; explicit body included plan and materials; date=$selectedDay; all=$allCount dateOnly=$dateCount firstObject=1 secondObject=1; first=${firstVisit.id} excludedDate=${outside.id} otherObject=${secondVisit.id}; reset restored all=$allCount; preview only, no external save/share sent")
    }

    private fun snapshot(): Snapshot = runBlocking { Repository.get(context).snapshot() }

    private fun root(tab: String) {
        closeSoftKeyboard()
        repeat(60) {
            if (ui.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()) {
                clickTag("nav_$tab")
                return
            }
            ui.onNodeWithContentDescription("返回").performClick()
            ui.waitForIdle()
        }
        error("Cannot return to the actual bottom navigation")
    }

    private fun openListEntry(tab: String, entry: Entry) {
        root(tab)
        clickTag("filter_类型", scroll = true)
        clickDialogText("全部")
        clickTag("filter_阶段", scroll = true)
        clickDialogText("全部")
        if (tab == "activities") ui.onNodeWithTag("activity_date_filter").performScrollTo().performTextReplacement("")
        ui.onNodeWithTag("list_search").performScrollTo().performTextReplacement(entry.title)
        closeSoftKeyboard()
        clickTag("entry_${entry.id}", scroll = true)
    }

    private fun newOrganization(title: String): Entry {
        root("orgs")
        clickText("新增单位", scroll = true)
        fill("title", title)
        return save()
    }

    private fun newVisit(parent: Entry, title: String, date: String, time: String, content: String): Entry {
        openListEntry("orgs", parent)
        clickTag("add_interaction", scroll = true)
        fill("occurredDate", date)
        fill("occurredTime", time)
        fill("content", content)
        clickTag("more_fields", scroll = true)
        fill("title", title)
        return save()
    }

    private fun fill(key: String, value: String) {
        ui.onNodeWithTag("field_$key").performScrollTo().performTextReplacement(value)
        ui.waitForIdle()
    }

    private fun searchDateNode(label: String) = ui.onNode(hasText(label) and hasSetTextAction())
    private fun fillSearchDate(label: String, date: String) {
        searchDateNode(label).performScrollTo().performTextReplacement(date)
        ui.waitForIdle()
    }

    private fun clickTag(tag: String, scroll: Boolean = false) {
        val node = ui.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
    }

    private fun clickText(text: String, scroll: Boolean = false) {
        val node = ui.onNodeWithText(text, substring = false)
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
    }

    private fun clickDialogText(text: String, scroll: Boolean = false) {
        val node = ui.onNode(hasText(text) and hasAnyAncestor(isDialog()))
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitForIdle()
    }

    private fun save(): Entry {
        val id = ui.runOnIdle { ui.activity.model.editor!!.entry.id }
        ui.onNodeWithTag("save_entry").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitUntil(15_000) { ui.activity.model.route == "detail:$id" && ui.activity.model.editor == null }
        ui.waitUntil(10_000) { snapshot().find(id) != null }
        ui.waitForIdle()
        return snapshot().find(id)!!
    }

    private fun textOf(node: SemanticsNodeInteraction): String = node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
    private fun summaryText() = textOf(ui.onNodeWithTag("summary_preview"))

    private fun screenshot(name: String) {
        ui.waitForIdle()
        check(device.takeScreenshot(File(evidenceDir, "$name-$suffix.png"))) { "Screenshot failed: $name" }
    }

    private fun evidence(message: String) {
        File(evidenceDir, "supplemental-results.txt").appendText("${System.currentTimeMillis()} $message\n", Charsets.UTF_8)
    }
}
