package cn.kangyang.workbook.domain

import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Business dates always mean Shanghai dates, independently of the device's zone. */
object TimeRules {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun now(): Long = System.currentTimeMillis()
    fun today(at: Long = now()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    fun formatDateTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(zone).format(dateTimeFormat)
    fun formatDate(epoch: Long): String = today(epoch).format(dateFormat)
    fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), dateFormat) }.getOrNull()
    fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim(), timeFormat) }.getOrNull()
    fun parseDateTime(text: String): Long? = runCatching {
        LocalDateTime.parse(text.trim(), dateTimeFormat).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** A date-only deadline remains current until the last millisecond of that business day. */
    fun taskDue(entry: Entry): Long? {
        if (entry.kind != Kind.TASK) return null
        val date = parseDate(entry.fields["date"].orEmpty()) ?: return null
        val timeText = entry.fields["time"].orEmpty().trim()
        return if (timeText.isEmpty()) {
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        } else {
            val time = parseTime(timeText) ?: return null
            date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
    }

    fun taskBucket(entry: Entry, now: Long = TimeRules.now()): String {
        when (entry.fields["status"]) {
            "已完成" -> return "已完成"
            "已取消" -> return "已取消"
        }
        val deadline = taskDue(entry) ?: return "未设日期"
        if (deadline < now) return "逾期"
        return if (today(deadline) == today(now)) "今日" else "未来"
    }

    /** Date-only tasks use the explicitly documented default 09:00 local reminder. */
    fun reminderAt(entry: Entry): Long? {
        if (entry.archived) return null
        if (entry.fields["status"] in listOf("已完成", "已取消")) return null
        return when (entry.kind) {
            Kind.TASK -> {
                if (entry.fields["reminder"] != "true") return null
                val date = parseDate(entry.fields["date"].orEmpty()) ?: return null
                val timeText = entry.fields["time"].orEmpty().trim()
                val time = if (timeText.isEmpty()) LocalTime.of(9, 0) else parseTime(timeText) ?: return null
                date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            }
            Kind.ACTIVITY -> {
                val lead = entry.fields["remindMinutes"]?.toLongOrNull() ?: return null
                if (lead !in 0..525_600) return null
                val start = parseDateTime(entry.fields["start"].orEmpty()) ?: return null
                start - lead * 60_000L
            }
            else -> null
        }
    }

    fun isReminderActionable(entry: Entry, now: Long = TimeRules.now()): Boolean {
        val at = reminderAt(entry) ?: return false
        if (at >= now) return true
        // A short missed reminder is recovered on reopen/reboot. Old history stays on the desk.
        if (now - at > 24 * 60 * 60_000L) return false
        if (entry.kind == Kind.ACTIVITY) {
            val start = parseDateTime(entry.fields["start"].orEmpty()) ?: return false
            return start >= now
        }
        return true
    }
}
