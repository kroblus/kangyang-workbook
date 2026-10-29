package cn.kangyang.workbook

import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.domain.TimeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TimeRulesTest {
    private fun at(value: String) = requireNotNull(TimeRules.parseDateTime(value))
    private fun task(date: String = "", time: String = "", status: String = "未完成", reminder: Boolean = false) =
        Entry(kind = Kind.TASK, title = "合成跟进", fields = mapOf("date" to date, "time" to time, "status" to status, "reminder" to reminder.toString()))

    @Test fun dateOnlyRemainsTodayUntilBusinessMidnight() {
        val task = task("2026-10-29")
        assertEquals("今日", TimeRules.taskBucket(task, at("2026-10-29 00:00")))
        assertEquals("今日", TimeRules.taskBucket(task, at("2026-10-30 00:00") - 1))
        assertEquals("逾期", TimeRules.taskBucket(task, at("2026-10-30 00:00")))
        assertEquals(at("2026-10-30 00:00") - 1, TimeRules.taskDue(task))
    }

    @Test fun aThreeDayFollowupNeverInflatesToday() {
        assertEquals("未来", TimeRules.taskBucket(task("2026-11-01"), at("2026-10-29 12:00")))
        assertEquals("未设日期", TimeRules.taskBucket(task(), at("2026-10-29 12:00")))
        assertEquals("逾期", TimeRules.taskBucket(task("2026-10-28"), at("2026-10-29 00:00")))
    }

    @Test fun timedTasksBecomeOverdueAtTheirSpecifiedTime() {
        val task = task("2026-10-29", "14:30")
        assertEquals("今日", TimeRules.taskBucket(task, at("2026-10-29 14:29")))
        assertEquals("逾期", TimeRules.taskBucket(task, at("2026-10-29 14:31")))
    }

    @Test fun finishedAndCancelledTasksDoNotBecomeOverdueOrSchedule() {
        val completed = task("2026-10-01", status = "已完成", reminder = true)
        val cancelled = task("2026-10-01", status = "已取消", reminder = true)
        assertEquals("已完成", TimeRules.taskBucket(completed, at("2026-10-29 12:00")))
        assertEquals("已取消", TimeRules.taskBucket(cancelled, at("2026-10-29 12:00")))
        assertNull(TimeRules.reminderAt(completed))
        assertNull(TimeRules.reminderAt(cancelled))
    }

    @Test fun reminderHasAnExplicitDateOnlyDefaultAndOptIn() {
        assertNull(TimeRules.reminderAt(task("2026-11-01")))
        assertNull(TimeRules.reminderAt(task(reminder = true)))
        assertEquals(at("2026-11-01 09:00"), TimeRules.reminderAt(task("2026-11-01", reminder = true)))
        assertEquals(at("2026-11-01 15:20"), TimeRules.reminderAt(task("2026-11-01", "15:20", reminder = true)))
    }

    @Test fun activityRescheduleChangesItsAlarmButNotIndependentTaskDate() {
        val activity = Entry(kind = Kind.ACTIVITY, fields = mapOf("start" to "2026-11-03 10:00", "remindMinutes" to "30", "status" to "待开展"))
        val independent = task("2026-11-01", "11:00", reminder = true).copy(parentId = activity.id)
        assertEquals(at("2026-11-03 09:30"), TimeRules.reminderAt(activity))
        val moved = activity.copy(fields = activity.fields + ("start" to "2026-11-05 14:00"))
        assertEquals(at("2026-11-05 13:30"), TimeRules.reminderAt(moved))
        assertEquals(at("2026-11-01 11:00"), TimeRules.reminderAt(independent))
        assertNull(TimeRules.reminderAt(moved.copy(fields = moved.fields + ("status" to "已取消"))))
    }

    @Test fun malformedDatesAreNotSilentlyNormalized() {
        assertNull(TimeRules.parseDateTime("2026-02-30 10:00"))
        assertNull(TimeRules.parseDateTime("2026-10-29 25:00"))
        assertNull(TimeRules.parseDate("2026-02-29"))
        assertNotNull(TimeRules.parseDate("2028-02-29"))
        assertNull(TimeRules.reminderAt(task("2026-10-29", "25:00", reminder = true)))
    }

    @Test fun businessZoneDoesNotFollowDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val shanghaiMidnight = at("2026-10-30 00:00")
            assertEquals("2026-10-30", TimeRules.today(shanghaiMidnight).toString())
            assertEquals("2026-10-30 00:00", TimeRules.formatDateTime(shanghaiMidnight))
            assertEquals("逾期", TimeRules.taskBucket(task("2026-10-29"), shanghaiMidnight))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun shortMissedFollowupsRecoverButHistoricalOrEndedActivityAlarmsDoNot() {
        val task = task("2026-10-29", "09:00", reminder = true)
        assertTrue(TimeRules.isReminderActionable(task, at("2026-10-29 09:10")))
        assertFalse(TimeRules.isReminderActionable(task, at("2026-10-30 09:01")))
        val activity = Entry(kind = Kind.ACTIVITY, fields = mapOf("start" to "2026-10-29 10:00", "remindMinutes" to "30"))
        assertTrue(TimeRules.isReminderActionable(activity, at("2026-10-29 09:40")))
        assertFalse(TimeRules.isReminderActionable(activity, at("2026-10-29 10:01")))
    }
}
