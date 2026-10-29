package cn.kangyang.workbook

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.reminders.ReminderScheduler
import cn.kangyang.workbook.ui.*

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
                    model.action { ReminderScheduler(this@MainActivity).rebuild(model.repository.snapshot(),force=true) }
                }
                var preview by remember { mutableStateOf<Attachment?>(null) }
                WorkbookApp(model,
                    onPhoto={photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                    onFile={document.launch(arrayOf("image/*","application/pdf","text/plain","text/csv","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.oasis.opendocument.text"))},
                    onAttachment={ attachment ->
                        runCatching {
                            val f=model.repository.attachmentFile(attachment)
                            if(!f.exists()) model.message="附件文件缺失，请从完整备份恢复"
                            else if(attachment.mime.startsWith("image/")) preview=attachment else openFile(attachment)
                        }.onFailure { model.message="附件文件缺失或损坏，请从完整备份恢复" }
                    },
                    onNotifications={if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)})
                preview?.let { attachment ->
                    val bitmap=remember(attachment.id) {
                        runCatching {
                            val path=model.repository.attachmentFile(attachment).absolutePath
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            BitmapFactory.decodeFile(path,bounds)
                            var sample=1
                            while(bounds.outWidth/sample>1800 || bounds.outHeight/sample>1800) sample*=2
                            BitmapFactory.decodeFile(path,BitmapFactory.Options().apply { inSampleSize=sample })?.asImageBitmap()
                        }.getOrNull()
                    }
                    AlertDialog(onDismissRequest={preview=null},title={Text(attachment.name)},text={
                        if(bitmap==null) Text("图片无法读取，原记录和其他附件仍然保留")
                        else Image(bitmap,contentDescription=attachment.name,modifier=Modifier.fillMaxWidth().heightIn(max=380.dp))
                    },confirmButton={TextButton(onClick={preview=null}) { Text("关闭预览") }})
                }
            }
        }
    }
    private fun openFile(attachment:Attachment) {
        runCatching {
            val uri=FileProvider.getUriForFile(this,"$packageName.files",model.repository.attachmentFile(attachment))
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { model.message="没有可打开此文件的应用，附件已保留" }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra("entryId")?.let { model.navigate("detail:$it") } }
    override fun onStop() { if(::model.isInitialized) model.flushDraft(); super.onStop() }
    override fun onResume() { super.onResume(); if(::model.isInitialized) model.action { ReminderScheduler(this@MainActivity).rebuild(model.repository.snapshot(),force=true) } }
}
