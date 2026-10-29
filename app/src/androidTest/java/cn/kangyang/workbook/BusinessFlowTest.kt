package cn.kangyang.workbook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

/** Business rows are created and edited only through the installed app's real Compose forms. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BusinessFlowTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val suffix = UUID.randomUUID().toString().take(7)
    private val evidenceDir get() = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    // The watcher is inside the Compose/activity rule, so evidence is captured before teardown.
    @get:Rule(order = 1) val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val name = "FAIL-${description.methodName}-$suffix"
            runCatching { device.takeScreenshot(File(evidenceDir, "$name.png")) }
            runCatching { device.dumpWindowHierarchy(File(evidenceDir, "$name.window.xml")) }
            val tree = runCatching {
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 100)
            }.getOrElse { "Compose tree unavailable: ${it.stackTraceToString()}" }
            runCatching { File(evidenceDir, "$name.compose.txt").writeText(tree, Charsets.UTF_8) }
            runCatching {
                val model = compose.activity.model
                File(evidenceDir, "$name.state.txt").writeText(
                    "route=${model.route}\nmessage=${model.message}\neditor=${model.editor}\n",
                    Charsets.UTF_8,
                )
            }
            runCatching { File(evidenceDir, "$name.error.txt").writeText(error.stackTraceToString(), Charsets.UTF_8) }
            runCatching { evidence("FAIL ${description.methodName} run=$suffix; ${error.message}; see $name artifacts") }
        }
    }

    @Before fun guardQaSpace() {
        check(context.packageName.endsWith(".qa")) { "BusinessFlowTest only runs in the independent .qa package" }
        compose.waitForIdle()
        evidence("START package=${context.packageName}; run=$suffix; no data cleared")
    }

    @Test fun unitsVisitsFutureFollowupClassificationAndArchive() {
        val org = newOrganization("示例·青禾养老中心·$suffix", "养老机构", "武汉·验收区域")
        addContact(org, "示例·李主任·$suffix", "业务联系", "010-00000000 转123", primary = true)
        openListEntry("orgs", org)
        addContact(org, "示例·陈老师·$suffix", "活动对接", "")
        assertEquals(2, snapshot().entries.count { it.kind == Kind.CONTACT && it.parentId == org.id })

        openListEntry("orgs", org)
        clickTag("add_interaction", true)
        val firstText = "讨论康养服务方案，尚待确认。\n对方提出：先看方案，再谈价格。\n说明含逗号,双引号\"与换行。\n=SUM(1,2) 是原始备注示例。"
        replace("content", firstText)
        replace("nextStep", "示例·三天后回访·$suffix")
        val futureDate = TimeRules.today().plusDays(3).toString()
        replace("nextDate", futureDate)
        val first = save(doubleTap = true)
        assertEquals(firstText, first["content"])
        assertEquals(1, snapshot().entries.count { it.id == first.id })
        val followup = snapshot().entries.single { it.kind == Kind.TASK && it.sourceId == first.id }
        assertEquals("未来", TimeRules.taskBucket(followup))
        assertEquals(futureDate, followup["date"])
        assertEquals(org.id, followup.parentId)

        clickTag("edit_entry")
        replace("content", "$firstText\n补充：下一步仍为同一次回访。")
        val editedFirst = save()
        assertEquals(first.id, editedFirst.id)
        assertEquals(first.createdAt, editedFirst.createdAt)
        assertEquals(1, snapshot().entries.count { it.kind == Kind.TASK && it.sourceId == first.id })
        assertEquals(followup.id, snapshot().entries.single { it.kind == Kind.TASK && it.sourceId == first.id }.id)

        openListEntry("orgs", org)
        clickTag("add_interaction", true)
        replace("content", "第二次独立沟通·$suffix：确认方案接收人，第一条历史不覆盖。")
        replace("occurredDate", TimeRules.today().minusDays(2).toString())
        replace("occurredTime", "10:15")
        val second = save()
        assertNotEquals(first.id, second.id)
        assertTrue(second.occurredAt < first.occurredAt)
        assertEquals(editedFirst["content"], snapshot().find(first.id)!!["content"])
        assertEquals(2, snapshot().entries.count { it.kind == Kind.INTERACTION && it.parentId == org.id })
        assertEquals("待联系", snapshot().find(org.id)!!["stage"])
        openListEntry("orgs", org)
        compose.onNodeWithText("拜访时间线").performScrollTo()
        screenshot("E02-unit-timeline")

        root("home")
        val todayCount = snapshot().entries.count { it.kind == Kind.TASK && TimeRules.taskBucket(it) == "今日" }
        compose.onNodeWithTag("today_count").assertTextEquals(todayCount.toString())
        clickText("全部待办", scroll = true)
        clickTag("bucket_未来", true)
        compose.onNodeWithTag("entry_${followup.id}").performScrollTo().assertIsDisplayed()
        screenshot("E02-future-followup")

        val sameName = newOrganization(org.title, "养老机构", "武汉·另一示例区域")
        assertNotEquals(org.id, sameName.id)
        assertEquals(2, snapshot().entries.count { it.kind == Kind.ORGANIZATION && it.title == org.title })
        assertEquals("武汉·验收区域", snapshot().find(org.id)!!["area"])
        assertEquals("武汉·另一示例区域", snapshot().find(sameName.id)!!["area"])
        root("orgs")
        compose.onNodeWithTag("list_search").performScrollTo().performTextReplacement(org.title)
        compose.onNodeWithTag("entry_${org.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("entry_${sameName.id}").performScrollTo().assertIsDisplayed()
        screenshot("E09-same-name-organizations")

        val street = newOrganization("示例·幸福街道·$suffix", "街道办", "示例一区")
        val community = newOrganization("示例·青禾社区·$suffix", "社区/居委会", "示例二区")
        val hospital = newOrganization("示例·青禾康复医院·$suffix", "康复医院", "示例三区")
        listOf(street, community, hospital).forEach { item ->
            root("orgs")
            compose.onNodeWithTag("list_search").performScrollTo().performTextReplacement(suffix)
            clickTag("filter_类型", true)
            clickDialogText(item["type"])
            compose.onNodeWithTag("entry_${item.id}").performScrollTo().assertIsDisplayed()
            listOf(street, community, hospital).filter { it.id != item.id }.forEach {
                compose.onNodeWithTag("entry_${it.id}").assertDoesNotExist()
            }
        }
        assertEquals("暂不清楚", snapshot().find(hospital.id)!!["nature"])
        screenshot("E03-rehabilitation-category")

        // Archiving is a visible UI action. Historical rows and unfinished followups remain intact.
        openListEntry("orgs", org)
        clickText("归档单位", scroll = true)
        clickDialogText("确认")
        compose.waitUntil(10_000) { snapshot().find(org.id)?.archived == true }
        assertEquals(2, snapshot().entries.count { it.kind == Kind.INTERACTION && it.parentId == org.id })
        assertEquals("未完成", snapshot().find(followup.id)!!["status"])
        root("orgs")
        compose.onNodeWithTag("list_search").performScrollTo().performTextReplacement(org.title)
        compose.onNodeWithTag("entry_${org.id}").assertDoesNotExist()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("entry_${org.id}").performScrollTo().assertIsDisplayed()
        screenshot("E13-archive-retains-history")
        evidence("PASS E02/E03/E09/E13 run=$suffix organization=${org.id} visits=${first.id},${second.id} task=${followup.id} due=$futureDate; two consecutive UI save actions/edit preserved IDs; sameName=${sameName.id} saved separately with area; nature=${hospital["nature"]}; archive kept history and task")
    }

    @Test fun householdRolesDifferentNeedsLongChineseAndSearch() {
        val family = newCustomer("示例·王阿姨家庭·$suffix", "医院及居家护理")
        addContact(family, "示例·李女士·$suffix", "女儿 / 决策联系人", "010-00000001")
        openListEntry("customers", family)
        clickTag("add_recipient", true)
        replace("title", "示例·王阿姨·$suffix")
        replace("relationship", "联系人李女士的母亲")
        replace("ageBand", "70—79岁")
        replace("care", "家属提供的示例信息：日常起居需要协助。")
        val recipient = save()
        val contact = snapshot().entries.single { it.kind == Kind.CONTACT && it.parentId == family.id }
        assertNotEquals(contact.id, recipient.id)
        assertEquals(family.id, recipient.parentId)
        assertEquals("联系人李女士的母亲", recipient["relationship"])

        val longText = buildString {
            append("长中文验收检索词$suffix\n")
            repeat(110) { index -> append("第${index + 1}段：这是用于验收的家庭服务沟通示例，记录需要确认的服务时间、方式和后续安排。\n") }
        }
        assertTrue(longText.length >= 2_000)
        openListEntry("customers", family)
        clickTag("add_interaction", true)
        replace("content", longText)
        replace("nextStep", "示例·整理家庭方案·$suffix")
        val first = save()
        assertEquals(longText, snapshot().find(first.id)!!["content"])
        screenshot("E09-long-Chinese-saved")

        openListEntry("customers", family)
        clickTag("add_interaction", true)
        replace("content", "第二次客户沟通·$suffix：已收到方案，约定后续联系。")
        val second = save()
        assertEquals(2, snapshot().entries.count { it.kind == Kind.INTERACTION && it.parentId == family.id })
        assertEquals(longText, snapshot().find(first.id)!!["content"])

        val equipment = newCustomer("示例·器具咨询·$suffix", "康复器具与耗材")
        val training = newCustomer("示例·培训咨询·$suffix", "长期照护师培训")
        listOf(equipment, training).forEach { client ->
            openListEntry("customers", client)
            clickTag("add_interaction", true)
            replace("content", "${client.title}：仅了解产品或课程，无需填写老人入住资料。")
            save()
            assertTrue(snapshot().entries.none { it.kind == Kind.RECIPIENT && it.parentId == client.id })
            assertTrue(snapshot().find(client.id)!!["budget"].isBlank())
        }

        root("home")
        clickTag("search_all")
        compose.onNodeWithTag("global_search").performTextReplacement("010-00000001")
        compose.onNodeWithTag("entry_${family.id}").performScrollTo().assertIsDisplayed()
        screenshot("E09-contact-phone-search")
        compose.onNodeWithTag("global_search").performScrollTo().performTextReplacement("长中文验收检索词$suffix")
        compose.onNodeWithTag("entry_${first.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("global_search").performScrollTo().performTextReplacement(suffix)
        clickTag("filter_业务标签", true)
        clickDialogText("康复器具与耗材")
        compose.onNodeWithTag("entry_${equipment.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("entry_${family.id}").assertDoesNotExist()
        compose.onNodeWithTag("entry_${training.id}").assertDoesNotExist()
        screenshot("E09-global-tag-search")
        clickTag("filter_业务标签", true)
        clickDialogText("全部")
        compose.onNodeWithTag("global_search").performScrollTo().performTextReplacement("不存在的示例关键词-$suffix")
        compose.onNodeWithText("没有找到匹配内容").performScrollTo().assertIsDisplayed()
        screenshot("E09-global-no-results")

        val task = snapshot().entries.single { it.kind == Kind.TASK && it.sourceId == first.id }
        assertEquals("未设日期", TimeRules.taskBucket(task))
        root("home")
        clickText("全部待办", scroll = true)
        clickTag("bucket_未设日期", true)
        clickTag("entry_${task.id}", true)
        clickTag("complete_task", true)
        compose.waitUntil(10_000) { snapshot().find(task.id)?.get("status") == "已完成" }
        assertEquals("已完成", TimeRules.taskBucket(snapshot().find(task.id)!!))
        openListEntry("customers", family)
        compose.onNodeWithText("服务对象").performScrollTo()
        screenshot("E04-family-roles-and-history")
        evidence("PASS E04/E09 run=$suffix family=${family.id} contact=${contact.id} recipient=${recipient.id} interactions=${first.id},${second.id} longTextChars=${longText.length} equipment=${equipment.id} training=${training.id}; phone/content/tag/no-result UI search; no-date task completed")
    }

    @Test fun activityPlanRescheduleExecutionAndCancellationChoices() {
        val day = TimeRules.today().plusDays(4).toString()
        val activity = newActivity("示例·照护交流活动·$suffix", day, withReminder = true)
        assertEquals("30", activity["remindMinutes"])
        val oldReminder = TimeRules.reminderAt(activity)
        clickTag("add_task", true)
        replace("title", "示例·准备物料·$suffix")
        val independentDate = TimeRules.today().plusDays(2).toString()
        replace("date", independentDate)
        val preparation = save()

        openListEntry("activities", activity)
        clickTag("edit_entry")
        val shiftedDay = TimeRules.today().plusDays(5).toString()
        replace("start", "$shiftedDay 10:00")
        replace("end", "$shiftedDay 11:30")
        val shifted = save()
        assertNotEquals(oldReminder, TimeRules.reminderAt(shifted))
        assertEquals(TimeRules.parseDateTime("$shiftedDay 10:00")!! - 30 * 60_000L, TimeRules.reminderAt(shifted))
        assertEquals(independentDate, snapshot().find(preparation.id)!!["date"])
        screenshot("E05-rescheduled-plan")

        clickTag("edit_entry")
        choose("status", "已完成")
        clickTag("more_fields", true)
        replace("actualCount", "18")
        replace("execution", "示例执行：完成签到、经验交流及器具演示。")
        replace("review", "示例复盘：下次增加现场提问时间。")
        val completed = save()
        assertEquals("已完成", completed["status"])
        assertEquals("18", completed["actualCount"])
        assertNull(TimeRules.reminderAt(completed))
        screenshot("E05-completed-activity")
        clickText("新建来源客户", scroll = true)
        replace("title", "示例·活动后主动咨询·$suffix")
        val sourced = save()
        assertEquals(activity.id, sourced["linkedActivity"])

        val keepActivity = newActivity("示例·取消保留待办·$suffix", day)
        clickTag("add_task", true)
        replace("title", "示例·取消后仍需联系·$suffix")
        val kept = save()
        openListEntry("activities", keepActivity)
        clickText("取消活动", scroll = true)
        clickDialogText("保留待办")
        compose.waitUntil(10_000) { snapshot().find(keepActivity.id)?.get("status") == "已取消" }
        assertEquals("未完成", snapshot().find(kept.id)!!["status"])
        assertNull(TimeRules.reminderAt(snapshot().find(keepActivity.id)!!))

        val cancelActivity = newActivity("示例·取消关联待办·$suffix", day)
        clickTag("add_task", true)
        replace("title", "示例·取消筹备事项·$suffix")
        val cancelledTask = save()
        openListEntry("activities", cancelActivity)
        clickText("取消活动", scroll = true)
        clickDialogText("同时取消待办")
        compose.waitUntil(10_000) { snapshot().find(cancelledTask.id)?.get("status") == "已取消" }
        assertEquals("已取消", snapshot().find(cancelActivity.id)!!["status"])
        assertNotNull(snapshot().find(cancelActivity.id))
        screenshot("E05-cancelled-history")

        root("activities")
        compose.onNodeWithTag("activity_date_filter").performScrollTo().performTextReplacement(shiftedDay)
        compose.onNodeWithTag("entry_${activity.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("entry_${keepActivity.id}").assertDoesNotExist()
        evidence("PASS E05 run=$suffix activity=${activity.id} start=$day->$shiftedDay reminder=$oldReminder->${TimeRules.reminderAt(shifted)} independentTaskDate=$independentDate retained; completed with result; cancellations preserve=${kept.id} cancel=${cancelledTask.id}; sourceClient=${sourced.id}. System notification delivery is separately verified, not claimed here.")
    }

    private fun snapshot(): Snapshot = runBlocking { Repository.get(context).snapshot() }

    private fun root(tab: String) {
        closeSoftKeyboard()
        repeat(60) {
            if (compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("nav_$tab").performClick()
                compose.waitForIdle()
                return
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitForIdle()
        }
        error("Unable to return to the main navigation")
    }

    private fun openListEntry(tab: String, entry: Entry) {
        root(tab)
        clickTag(if (tab == "customers") "filter_需求标签" else "filter_类型", true)
        clickDialogText("全部")
        clickTag("filter_阶段", true)
        clickDialogText("全部")
        if (tab == "activities") compose.onNodeWithTag("activity_date_filter").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("list_search").performScrollTo().performTextReplacement(entry.title)
        closeSoftKeyboard()
        clickTag("entry_${entry.id}", true)
    }

    private fun replace(key: String, value: String) {
        compose.onNodeWithTag("field_$key").performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun choose(key: String, option: String) {
        clickTag("field_$key", true)
        clickTag("option_$option", true)
    }

    private fun chooseTag(tag: String) {
        clickTag("field_tags", true)
        clickTag("option_$tag", true)
        clickDialogText("完成")
    }

    private fun clickTag(tag: String, scroll: Boolean = false) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickText(text: String, scroll: Boolean = false) {
        val node = compose.onNodeWithText(text, substring = false)
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickDialogText(text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
    }

    private fun save(doubleTap: Boolean = false): Entry {
        val id = compose.runOnIdle { compose.activity.model.editor!!.entry.id }
        // Invoke the actual form button action, preserving its validation and normal ViewModel path.
        // Native IME-inset animations and transient snackbars must not redirect a stale screen tap.
        compose.onNodeWithTag("save_entry").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
            action()
            // Reuse the same UI callback within this event turn; the node may leave composition next.
            if (doubleTap) action()
        }
        compose.waitUntil(15_000) { compose.activity.model.route == "detail:$id" && compose.activity.model.editor == null }
        compose.waitUntil(10_000) { snapshot().find(id) != null }
        compose.waitForIdle()
        return snapshot().find(id)!!
    }

    private fun newOrganization(title: String, type: String, area: String): Entry {
        root("orgs")
        clickText("新增单位", true)
        replace("title", title)
        if (type != "养老机构") choose("type", type)
        replace("area", area)
        return save()
    }

    private fun newCustomer(title: String, tag: String): Entry {
        root("customers")
        clickText("新增客户", true)
        replace("title", title)
        chooseTag(tag)
        return save()
    }

    private fun addContact(parent: Entry, title: String, role: String, phone: String, primary: Boolean = false): Entry {
        clickTag("add_contact", true)
        replace("title", title)
        replace("role", role)
        if (phone.isNotEmpty()) replace("phone", phone)
        if (primary) clickTag("field_primary", true)
        val result = save()
        assertEquals(parent.id, result.parentId)
        return result
    }

    private fun newActivity(title: String, day: String, withReminder: Boolean = false): Entry {
        root("activities")
        clickText("新增活动", true)
        replace("title", title)
        replace("start", "$day 10:00")
        replace("end", "$day 11:30")
        replace("location", "示例社区活动室")
        replace("plan", "10:00 签到\n10:15 照护经验交流\n11:00 器具演示\n11:30 结束")
        replace("materials", "资料册20份\n示例展示器具1套\n签到表1份")
        if (withReminder) {
            choose("remindMinutes", "提前30分钟")
            listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller").forEach { pkg ->
                device.wait(Until.findObject(By.res(pkg, "permission_allow_button")), 1_000)?.click()
            }
            compose.waitForIdle()
        }
        return save()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        check(device.takeScreenshot(File(evidenceDir, "$name-$suffix.png"))) { "Screenshot failed: $name" }
    }

    private fun evidence(text: String) {
        File(evidenceDir, "business-flow-results.txt").appendText("${System.currentTimeMillis()} $text\n", Charsets.UTF_8)
    }
}
