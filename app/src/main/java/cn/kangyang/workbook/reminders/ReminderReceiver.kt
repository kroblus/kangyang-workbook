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

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_ENTRY_ID) ?: return
        val at = intent.getLongExtra(ReminderScheduler.EXTRA_PLANNED_AT, Long.MIN_VALUE)
        if (at == Long.MIN_VALUE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = Repository.get(context.applicationContext).snapshot()
                ReminderScheduler(context).deliver(id, at, snapshot)
            } catch (error: Exception) {
                Log.e(ReminderScheduler.TAG, "Reminder delivery failed; data remains available", error)
            } finally {
                pending.finish()
            }
        }
    }
}
