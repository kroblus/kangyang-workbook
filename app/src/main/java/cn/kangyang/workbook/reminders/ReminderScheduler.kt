package cn.kangyang.workbook.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Snapshot
import cn.kangyang.workbook.domain.TimeRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Non-exact local alarms. No service, polling timer, Internet, or exact-alarm permission. */
class ReminderScheduler(context: Context) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val preferences = app.getSharedPreferences("local_reminder_delivery", Context.MODE_PRIVATE)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "工作跟进与活动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "使用系统普通提醒，可能延后；锁屏不显示客户细节。"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun notificationsAllowed(): Boolean =
        notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    /**
     * The first rebuild in a process reinstalls pending alarms, including after force-stop/reopen.
     * A delivery stamp survives process death and boot, so reinstalling cannot notify twice.
     */
    suspend fun rebuild(snapshot: Snapshot, force: Boolean = false) = mutex.withLock {
        val firstInProcess = initializedPackages.add(app.packageName)
        val forceInstall = force || firstInProcess
        val now = TimeRules.now()
        val active = if (notificationsAllowed()) snapshot.entries
            .filter { TimeRules.isReminderActionable(it, now) }
            .associateBy { it.id } else emptyMap()
        val desired = active.mapValues { (_, entry) -> requireNotNull(TimeRules.reminderAt(entry)) }
        val scheduled = scheduledPlans()
        val editor = preferences.edit()

        scheduled.forEach { (id, oldAt) ->
            if (desired[id] != oldAt) {
                alarms.cancel(pendingAlarm(id, oldAt))
                notifications.cancel(id, 0)
                editor.remove(SCHEDULED + id)
                Log.i(TAG, "CANCEL entry=$id planned=$oldAt")
            }
        }
        // A restore can remove an already-delivered record, and rescheduling can replace a
        // displayed plan. Those no longer have a "scheduled" preference, so inspect our notes.
        val entriesById = snapshot.entries.associateBy { it.id }
        notifications.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { shown ->
            val id = shown.tag ?: return@forEach
            val currentAt = entriesById[id]?.let(TimeRules::reminderAt)
            if (currentAt == null || currentAt != preferences.getLong(DELIVERED + id, Long.MIN_VALUE)) {
                notifications.cancel(id, 0)
            }
        }
        desired.forEach { (id, plannedAt) ->
            val alreadyDelivered = preferences.getLong(DELIVERED + id, Long.MIN_VALUE) == plannedAt
            if (alreadyDelivered) {
                if (scheduled.containsKey(id)) {
                    alarms.cancel(pendingAlarm(id, plannedAt))
                    editor.remove(SCHEDULED + id)
                }
            } else if (forceInstall || scheduled[id] != plannedAt) {
                // Never request an exact alarm. A recently missed reminder is recovered on reopen.
                val effectiveAt = maxOf(plannedAt, now + 1_000)
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveAt, pendingAlarm(id, plannedAt))
                editor.putLong(SCHEDULED + id, plannedAt)
                Log.i(TAG, "SCHEDULE entry=$id planned=$plannedAt effective=$effectiveAt nonExact=true")
            }
        }
        check(editor.commit()) { "提醒计划未能保存，请重新打开应用重试" }
    }

    /** Called only by the non-exported receiver after loading a fresh persistent snapshot. */
    @SuppressLint("MissingPermission") // Runtime permission and channel state are rechecked below.
    internal suspend fun deliver(id: String, plannedAt: Long, snapshot: Snapshot) = mutex.withLock {
        val now = TimeRules.now()
        val entry = snapshot.entries.firstOrNull { it.id == id }
        val stillValid = entry != null && TimeRules.reminderAt(entry) == plannedAt &&
            TimeRules.isReminderActionable(entry, now) && plannedAt <= now
        if (!stillValid || !notificationsAllowed()) {
            if (preferences.getLong(SCHEDULED + id, Long.MIN_VALUE) == plannedAt) {
                preferences.edit().remove(SCHEDULED + id).commit()
            }
            Log.i(TAG, "SKIP entry=$id planned=$plannedAt valid=$stillValid permission=${notificationsAllowed()}")
            return@withLock
        }
        if (preferences.getLong(DELIVERED + id, Long.MIN_VALUE) == plannedAt) {
            Log.i(TAG, "DEDUP entry=$id planned=$plannedAt")
            return@withLock
        }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return@withLock
        launch.apply {
            putExtra(EXTRA_ENTRY_ID, id)
            data = Uri.parse("kangyang://entry/${Uri.encode(id)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val open = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val isActivity = entry?.kind == Kind.ACTIVITY
        val publicView = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("康养工作簿")
            .setContentText("打开工作簿查看提醒")
            .build()
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isActivity) "活动安排提醒" else "工作跟进提醒")
            .setContentText(if (isActivity) "有一项活动即将开始，点开查看安排。" else "有一项跟进需要处理，点开查看。")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicView)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        // A stable notification tag replaces the same notification if the process dies between
        // notify and the durable delivery stamp; OnlyAlertOnce avoids a repeated sound.
        notifications.notify(id, 0, notification)
        check(preferences.edit().putLong(DELIVERED + id, plannedAt).remove(SCHEDULED + id).commit()) {
            "提醒状态未能保存"
        }
        Log.i(TAG, "DELIVER entry=$id planned=$plannedAt actual=$now kind=${entry?.kind}")
    }

    fun scheduledPlans(): Map<String, Long> = preferences.all.mapNotNull { (key, value) ->
        if (key.startsWith(SCHEDULED) && value is Long) key.removePrefix(SCHEDULED) to value else null
    }.toMap()

    private fun pendingAlarm(id: String, at: Long): PendingIntent {
        val intent = Intent(app, ReminderReceiver::class.java).apply {
            action = "${app.packageName}.LOCAL_REMINDER"
            data = Uri.parse("kangyang://reminder/${Uri.encode(id)}")
            putExtra(EXTRA_ENTRY_ID, id)
            putExtra(EXTRA_PLANNED_AT, at)
        }
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL = "workbook_followups"
        const val EXTRA_ENTRY_ID = "entryId"
        const val EXTRA_PLANNED_AT = "plannedAt"
        const val TAG = "WorkbookReminder"
        private const val SCHEDULED = "scheduled:"
        private const val DELIVERED = "delivered:"
        private val mutex = Mutex()
        private val initializedPackages = mutableSetOf<String>()
    }
}
