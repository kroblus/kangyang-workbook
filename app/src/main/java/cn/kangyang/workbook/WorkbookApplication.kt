package cn.kangyang.workbook

import android.app.Application
import cn.kangyang.workbook.data.Repository
import cn.kangyang.workbook.reminders.ReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class WorkbookApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val repository = Repository.get(this@WorkbookApplication)
            val scheduler = ReminderScheduler(this@WorkbookApplication)
            runCatching { repository.migrateLegacyDrafts() }
                .onFailure { android.util.Log.e("WorkbookMigration", "旧填写内容迁移未完成", it) }
            repository.reminderState.collectLatest {
                runCatching { scheduler.rebuild(it) }
                    .onFailure { android.util.Log.e("WorkbookReminder", "无法重新安排提醒", it) }
            }
        }
    }
}
