package cn.kangyang.workbook

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.reminders.ReminderScheduler
import cn.kangyang.workbook.ui.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    lateinit var model:WorkbookViewModel; private set
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(android.graphics.Color.WHITE,android.graphics.Color.BLACK),
        )
        model=ViewModelProvider(this)[WorkbookViewModel::class.java]
        intent.getStringExtra("entryId")?.let { model.navigate("detail:$it") }
        setContent {
            WorkbookTheme {
                val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { model.attachment(it) }
                val document=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.attachment(it) }
                val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    model.message=if(granted) "通知已允许；系统可能延后提醒" else "通知未允许，仍可在工作台查看全部跟进"
                    model.action { ReminderScheduler(this@MainActivity).rebuild(model.repository.reminderSnapshot(),force=true) }
                }
                var preview by remember { mutableStateOf<Attachment?>(null) }
                WorkbookApp(model,
                    onPhoto={model.expectAttachment();photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                    onFile={model.expectAttachment();document.launch(arrayOf("image/*","application/pdf","text/plain","text/csv","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.oasis.opendocument.text"))},
                    onAttachment={ attachment ->
                        if(attachment.mime.startsWith("image/")) preview=attachment
                        else lifecycleScope.launch {
                            try {
                                val file=withContext(Dispatchers.IO) { model.repository.attachmentFile(attachment) }
                                openFile(attachment,file)
                            } catch(cancelled:CancellationException) { throw cancelled }
                            catch(failure:Exception) { model.message=failure.message ?: "附件文件缺失或损坏，请从完整备份恢复" }
                        }
                    },
                    onNotifications={if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)})
                preview?.let { attachment ->
                    AttachmentPreview(attachment,model.repository) { preview=null }
                }
            }
        }
    }
    private fun openFile(attachment:Attachment,file:File) {
        runCatching {
            val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { model.message="没有可打开此文件的应用，附件已保留" }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra("entryId")?.let { model.navigate("detail:$it") } }
    override fun onStop() { if(::model.isInitialized) model.onBackground(); super.onStop() }
    override fun onResume() { super.onResume(); if(::model.isInitialized) {model.onForeground();model.action { ReminderScheduler(this@MainActivity).rebuild(model.repository.reminderSnapshot(),force=true) }} }
}
