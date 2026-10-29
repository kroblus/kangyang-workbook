package cn.kangyang.workbook.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.kangyang.workbook.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = Repository.get(context.applicationContext).snapshot()
                ReminderScheduler(context).rebuild(snapshot, force = true)
            } catch (error: Exception) {
                Log.e(ReminderScheduler.TAG, "Reminder reschedule failed; reopen app to retry", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
