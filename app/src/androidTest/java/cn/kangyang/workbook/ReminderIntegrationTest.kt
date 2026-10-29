package cn.kangyang.workbook

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Repository
import cn.kangyang.workbook.data.Snapshot
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.reminders.ReminderReceiver
import cn.kangyang.workbook.reminders.ReminderScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks real AlarmManager registrations, not notification posting. Run after the UI has granted
 * the QA package notification permission and dismissed its notifications. This test never changes
 * permission/user-fixed flags, device time, network, or the main repository.
 */
@RunWith(AndroidJUnit4::class)
class ReminderIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realAlarmRegistrationRescheduleDedupCompletionAndCancellation() = runBlocking {
        val target = instrumentation.targetContext.applicationContext
        check(target.packageName.endsWith(".qa")) { "Reminder integration tests are restricted to the .qa package" }
        val manager = target.getSystemService(NotificationManager::class.java)
        val permissionBefore = permissionSnapshot(target)
        val report = File(target.filesDir, "verification/reminder-scheduling.txt").apply { parentFile?.mkdirs() }
        if (!permissionBefore.allowed || permissionBefore.channelImportance == null ||
            permissionBefore.channelImportance == NotificationManager.IMPORTANCE_NONE) {
            report.writeText("NOT_RUN: 请先通过 QA 界面允许通知；本测试不改变系统授权状态。\n$permissionBefore\n")
        }
        assumeTrue("QA notification permission/channel must already be allowed by the UI", permissionBefore.allowed &&
            permissionBefore.channelImportance != null && permissionBefore.channelImportance != NotificationManager.IMPORTANCE_NONE)
        val visibleBefore = manager.activeNotifications.filter { it.notification.channelId == ReminderScheduler.CHANNEL }
        if (visibleBefore.isNotEmpty()) report.writeText("NOT_RUN: 请先查看或关闭 QA 已有提醒，本测试不清除其他提醒。\n")
        assumeTrue("No existing workbook notifications may be removed by an isolated snapshot", visibleBefore.isEmpty())
        val now = TimeRules.now()
        val nearRealReminder = Repository.get(target).snapshot().entries.any {
            val at = TimeRules.reminderAt(it)
            at != null && at <= now + 15 * 60_000L && TimeRules.isReminderActionable(it, now)
        }
        if (nearRealReminder) report.writeText("NOT_RUN: QA 存在临近提醒，请完成普通后台通知验收后再运行独立调度测试。\n")
        assumeTrue("No existing QA reminder may arrive during this isolated scheduling test", !nearRealReminder)

        val suffix = UUID.randomUUID().toString().replace("-", "")
        val prefix = "reminder_test_${suffix}_"
        val testContext = IsolatedReminderContext(target, prefix)
        val repository = Repository(testContext, "${prefix}store.db", "${prefix}files")
        val scheduler = ReminderScheduler(testContext)
        val plannedIds = mutableListOf<String>()
        val evidence = StringBuilder("Real AlarmManager integration; package=${target.packageName}\nPermission unchanged: $permissionBefore\n")
        var result = "FAIL"
        try {
            val date = TimeRules.today().plusDays(14)
            val task = repository.save(Entry(
                id = "${prefix}followup", kind = Kind.TASK, title = "示例·独立调度验收",
                fields = mapOf("date" to date.toString(), "time" to "14:00", "reminder" to "true", "status" to "未完成"),
            ))
            plannedIds += task.id
            scheduler.rebuild(repository.snapshot(), force = true)
            val firstAt = requireNotNull(TimeRules.reminderAt(task))
            assertEquals(mapOf(task.id to firstAt), scheduler.scheduledPlans())
            val taskToken = pendingToken(target.packageName, task.id)
            assertAlarm(taskToken, firstAt, 1, "initial task", evidence)

            // Re-emitting the same persistent data must retain one system alarm, not add another.
            repeat(3) { scheduler.rebuild(repository.snapshot()) }
            assertEquals(mapOf(task.id to firstAt), scheduler.scheduledPlans())
            assertAlarm(taskToken, firstAt, 1, "same snapshot x3", evidence)

            val moved = repository.save(task.copy(fields = task.fields + ("time" to "16:30")))
            val movedAt = requireNotNull(TimeRules.reminderAt(moved))
            scheduler.rebuild(repository.snapshot())
            assertEquals(movedAt, scheduler.scheduledPlans()[task.id])
            assertEquals("Rescheduling must preserve the same PendingIntent identity", taskToken, pendingToken(target.packageName, task.id))
            assertAlarm(taskToken, movedAt, 1, "task rescheduled", evidence)

            val activity = repository.save(Entry(
                id = "${prefix}activity", kind = Kind.ACTIVITY, title = "示例·活动提醒验收",
                fields = mapOf("start" to "$date 10:00", "end" to "$date 11:00", "status" to "待开展", "remindMinutes" to "30"),
            ))
            plannedIds += activity.id
            val independent = repository.save(Entry(
                id = "${prefix}preparation", kind = Kind.TASK, parentId = activity.id, title = "示例·独立日期筹备",
                fields = mapOf("date" to date.minusDays(1).toString(), "time" to "11:00", "reminder" to "true", "status" to "未完成"),
            ))
            plannedIds += independent.id
            scheduler.rebuild(repository.snapshot())
            val activityToken = pendingToken(target.packageName, activity.id)
            val prepToken = pendingToken(target.packageName, independent.id)
            val independentAt = requireNotNull(TimeRules.reminderAt(independent))
            assertAlarm(activityToken, requireNotNull(TimeRules.reminderAt(activity)), 1, "activity opted in", evidence)
            assertAlarm(prepToken, independentAt, 1, "independent preparation", evidence)

            val movedActivity = repository.save(activity.copy(fields = activity.fields + mapOf("start" to "$date 15:00", "end" to "$date 16:00")))
            scheduler.rebuild(repository.snapshot())
            assertAlarm(activityToken, requireNotNull(TimeRules.reminderAt(movedActivity)), 1, "activity rescheduled", evidence)
            assertEquals(independentAt, scheduler.scheduledPlans()[independent.id])
            assertAlarm(prepToken, independentAt, 1, "preparation date retained", evidence)

            repository.setTaskStatus(task.id, "已完成", "合成验收完成")
            repository.save(movedActivity.copy(fields = movedActivity.fields + ("status" to "已取消")))
            scheduler.rebuild(repository.snapshot())
            assertFalse(scheduler.scheduledPlans().containsKey(task.id))
            assertFalse(scheduler.scheduledPlans().containsKey(activity.id))
            assertAlarm(taskToken, null, 0, "completed task cancelled", evidence)
            assertAlarm(activityToken, null, 0, "cancelled activity removed", evidence)
            assertAlarm(prepToken, independentAt, 1, "independent task kept after activity cancellation", evidence)

            repository.setTaskStatus(independent.id, "已取消")
            scheduler.rebuild(repository.snapshot())
            assertTrue(scheduler.scheduledPlans().isEmpty())
            assertAlarm(prepToken, null, 0, "preparation explicitly cancelled", evidence)

            repository.setTaskStatus(task.id, "未完成")
            scheduler.rebuild(repository.snapshot())
            assertAlarm(taskToken, movedAt, 1, "reopened task rescheduled", evidence)
            repository.save(repository.snapshot().find(task.id)!!.let { it.copy(fields = it.fields + ("reminder" to "false")) })
            scheduler.rebuild(repository.snapshot())
            assertTrue(scheduler.scheduledPlans().isEmpty())
            assertAlarm(taskToken, null, 0, "reminder opt-out cancelled", evidence)
            evidence.append("Notification popup intentionally NOT tested here; no receiver/deliver invocation.\n")
            result = "PASS"
        } catch (error: Throwable) {
            evidence.append("FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            throw error
        } finally {
            // All identifiers and paths below are unique to this test's .qa namespace.
            val cleanup = runCatching {
                scheduler.rebuild(Snapshot())
                plannedIds.forEach { cancelTestPendingIntent(target, it) }
                repository.close()
                target.deleteDatabase("${prefix}store.db")
                File(target.filesDir, "${prefix}files").delete() // Empty test folder only; no recursion.
                target.deleteSharedPreferences("${prefix}local_reminder_delivery")
            }
            val permissionAfter = permissionSnapshot(target)
            if (cleanup.isFailure || permissionAfter != permissionBefore) result = "FAIL"
            evidence.append("Cleanup=${if (cleanup.isSuccess) "PASS" else cleanup.exceptionOrNull()?.message}\n")
            evidence.append("Permission preserved=${permissionBefore == permissionAfter}\n")
            report.writeText("$result\n$evidence")
            assertTrue("Isolated test cleanup failed: ${cleanup.exceptionOrNull()}", cleanup.isSuccess)
            assertEquals("The test must preserve notification grant, flags, app toggle and channel state", permissionBefore, permissionAfter)
        }
    }

    private fun pendingToken(packageName: String, id: String): String {
        val dump = shell("dumpsys activity intents $packageName")
        val headers = Regex("PendingIntentRecord\\{([0-9a-f]+) [^}]+\\}").findAll(dump).toList()
        val expectedUri = "kangyang://reminder/$id"
        for ((index, header) in headers.withIndex()) {
            val block = dump.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: dump.length)
            if (block.contains(expectedUri)) return header.groupValues[1]
        }
        throw AssertionError("System PendingIntent identity could not be resolved for test ID $id; dumpsys format or registration differs")
    }

    private fun assertAlarm(token: String, expectedAt: Long?, count: Int, stage: String, evidence: StringBuilder) {
        val lines = shell("dumpsys alarm").lines()
        val found = lines.indices.filter { index ->
            lines[index].trimStart().startsWith("operation=") && lines[index].contains("PendingIntentRecord{$token ")
        }
        evidence.append("$stage: systemAlarms=${found.size}, expected=$count, planned=$expectedAt\n")
        assertEquals("$stage must match the actual AlarmManager registration count", count, found.size)
        if (expectedAt != null) {
            assertEquals(1, found.size)
            val operationLine = found.single()
            val alarmStart = (operationLine downTo maxOf(0, operationLine - 12)).firstOrNull {
                Regex("^\\s*(?:RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED_REALTIME_WAKEUP|ELAPSED_REALTIME|ELAPSED) #\\d+:.*").matches(lines[it])
            } ?: maxOf(0, operationLine - 7)
            val block = lines.subList(alarmStart, operationLine + 1).joinToString("\n")
            evidence.append(block).append('\n')
            assertTrue("$stage must carry the updated real wall-clock trigger", block.contains("origWhen $expectedAt ") || block.contains("origWhen=$expectedAt"))
            val window = Regex("\\bwindow=([^\\s]+)").find(block)?.groupValues?.get(1)
            assertTrue("This test must never use an exact alarm: $window", window != null && window !in setOf("0", "+0", "0ms", "+0ms"))
        }
    }

    private fun cancelTestPendingIntent(context: Context, id: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "${context.packageName}.LOCAL_REMINDER"
            data = Uri.parse("kangyang://reminder/${Uri.encode(id)}")
        }
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun permissionSnapshot(context: Context): PermissionState {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val flags = Regex("(?m)^\\s*android\\.permission\\.POST_NOTIFICATIONS: granted=.*$")
            .findAll(shell("dumpsys package ${context.packageName}")).map { it.value.trim() }.lastOrNull().orEmpty()
        return PermissionState(granted, notifications.areNotificationsEnabled(), notifications.getNotificationChannel(ReminderScheduler.CHANNEL)?.importance, flags)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private data class PermissionState(val granted: Boolean, val appEnabled: Boolean, val channelImportance: Int?, val runtimeFlags: String) {
        val allowed get() = granted && appEnabled
    }

    private class IsolatedReminderContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }
}
