@file:OptIn(ExperimentalMaterial3Api::class)
package cn.kangyang.workbook.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import cn.kangyang.workbook.BuildConfig
import cn.kangyang.workbook.WorkbookViewModel
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.reminders.ReminderScheduler
import cn.kangyang.workbook.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

val Context.uiPreferences by preferencesDataStore(name="ui_preferences")
private val ShowTips=booleanPreferencesKey("show_tips")

@Composable fun SettingsScreen(vm:WorkbookViewModel,s:Snapshot,onNotifications:()->Unit) {
    val context=LocalContext.current
    val tipsFlow=remember(context) { context.uiPreferences.data.map { it[ShowTips] ?: true } }
    val tips by tipsFlow.collectAsState(initial=true)
    var notificationsAllowed by remember { mutableStateOf(ReminderScheduler(context).notificationsAllowed()) }
    LifecycleResumeEffect(Unit) { notificationsAllowed=ReminderScheduler(context).notificationsAllowed(); onPauseOrDispose {} }
    Column(Modifier.fillMaxSize()) { BackTitle("本人资料与设置",vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings_scroll"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Surface(color=Mint,shape=RoundedCornerShape(16.dp),onClick={vm.edit(vm.profile)}) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.AccountCircle,null,tint=Green,modifier=Modifier.size(36.dp))
                    Text(vm.profile.title,style=MaterialTheme.typography.headlineMedium)
                    Text("${vm.profile["role"]} · ${vm.profile["company"]}",color=Muted)
                    Text("编辑资料、业务标签与应用名称 →",color=Green)
                }
            }
            SectionTitle("工作资料")
            MenuAction("导出与分享","选择记录范围，预览后保存 CSV",Icons.Outlined.IosShare,"open_export"){vm.navigate("export")}
            MenuAction("完整备份与恢复","使用自己的口令保护资料和附件",Icons.Outlined.Lock,"open_backup"){vm.navigate("backup")}
            SectionTitle("本地提醒")
            Text(if(notificationsAllowed) "系统通知已允许" else "系统通知未开启，仍可查看全部待办",color=Muted)
            OutlinedButton(onClick=onNotifications,modifier=Modifier.fillMaxWidth()){Text("允许通知提醒")}
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))},modifier=Modifier.fillMaxWidth()){Text("打开系统通知设置")}
            Text("使用普通本地提醒，系统可能延后；不保证分钟级准点。强制停止应用后，需要重新打开才能重建提醒。日期型待办默认在当天09:00提醒。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(tips,{v->vm.action {context.uiPreferences.edit {it[ShowTips]=v}}});Spacer(Modifier.width(8.dp));Text("显示使用提示")}
            if(tips) Text("先建单位或客户 → 记一次沟通 → 填下一步。也可以先速记，之后再补充关联对象。外出前后定期制作完整备份。",color=Muted)
            SectionTitle("关于")
            Text("康养工作簿 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${if(BuildConfig.FLAVOR=="qa") "独立验收空间 · 仅使用虚构资料" else "个人离线工作空间"}\nAndroid 8.0 及以上 · 北京时间 · 24小时制",style=MaterialTheme.typography.bodyMedium)
            Text("资料仅存本机，不需要登录。没有广告、远程分析或外部 AI 请求。系统云备份和标准设备迁移已配置为排除本应用资料；不同厂商迁移工具的行为不能保证。换机请使用口令加密完整备份。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("当前为测试签名安装包。以后更新须沿用同一包名与签名；不要卸载来解决更新问题。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}
@Composable private fun MenuAction(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,tag:String,onClick:()->Unit) {
    Surface(onClick=onClick,color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=Green);Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)};Icon(Icons.Outlined.ChevronRight,null)
        }
    }
}

private suspend fun writeDocument(context:Context,uri:android.net.Uri?,vm:WorkbookViewModel) {
    if(uri==null){vm.pendingExport=null;vm.message="已取消保存，资料仍保留在本机";return}
    val bytes=vm.pendingExport ?: run { vm.message="导出内容已失效，请重新生成";return }
    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(bytes);it.flush()} ?: error("无法写入所选文件，请换一个位置") }
    vm.pendingExport=null
    vm.message="文件已保存"
}
private fun share(context:Context,vm:WorkbookViewModel,bytes:ByteArray,name:String,mime:String) {
    vm.action {
        val file=withContext(Dispatchers.IO) {File(context.cacheDir,"share").apply{mkdirs()}.resolve(name).apply{writeBytes(bytes)}}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        val intent=Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent,"选择分享方式"))
    }
}
@Composable private fun PrivacyChoices(body:Boolean,onBody:(Boolean)->Unit,sensitive:Boolean,onSensitive:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(body,onBody,modifier=Modifier.testTag("export_body"));Text("包含详细正文（可能含个人信息，请检查预览）",modifier=Modifier.weight(1f))}
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(sensitive,onSensitive,modifier=Modifier.testTag("export_sensitive"));Text("包含联系人电话与照护资料",modifier=Modifier.weight(1f))}
}

@Composable fun SummaryDialog(vm:WorkbookViewModel,entry:Entry,s:Snapshot,onDismiss:()->Unit) {
    val context=LocalContext.current
    var body by rememberSaveable {mutableStateOf(false)}
    var sensitive by rememberSaveable {mutableStateOf(false)}
    val content=Exports.summary(entry,s,sensitive,body)
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->vm.action{writeDocument(context,uri,vm)}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("摘要预览")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        PrivacyChoices(body,{body=it},sensitive,{sensitive=it})
        Text(content,modifier=Modifier.testTag("summary_preview"))
        Text("分享由你选择接收方。请检查正文是否包含不宜对外提供的信息。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }},confirmButton={Column{
        TextButton(onClick={vm.pendingExport=content.toByteArray(Charsets.UTF_8);save.launch("工作摘要.txt")},modifier=Modifier.testTag("save_summary")){Text("保存文本")}
        TextButton(onClick={share(context,vm,content.toByteArray(Charsets.UTF_8),"工作摘要.txt","text/plain")},modifier=Modifier.testTag("share_summary")){Text("系统分享")}
    }},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
}

@Composable fun ExportScreen(vm:WorkbookViewModel,s:Snapshot) {
    val context=LocalContext.current
    var from by rememberSaveable{mutableStateOf("")};var to by rememberSaveable{mutableStateOf("")}
    var objectId by rememberSaveable{mutableStateOf("")};var objectDialog by remember{mutableStateOf(false)}
    var body by rememberSaveable{mutableStateOf(false)};var sensitive by rememberSaveable{mutableStateOf(false)}
    var preview by remember{mutableStateOf(false)}
    val interactions=s.entries.filter{it.kind==Kind.INTERACTION}.filter{
        val date=Instant.ofEpochMilli(it.occurredAt).atZone(TimeRules.zone).toLocalDate().toString()
        (from.isBlank()||date>=from)&&(to.isBlank()||date<=to)&&(objectId.isBlank()||it.parentId==objectId)
    }.sortedBy{it.occurredAt}
    val valid=(from.isBlank()||TimeRules.parseDate(from)!=null)&&(to.isBlank()||TimeRules.parseDate(to)!=null)&&(from.isBlank()||to.isBlank()||from<=to)
    val bytes=remember(s,interactions,body,sensitive){Exports.csv(s,interactions,sensitive,body)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->vm.action{writeDocument(context,uri,vm)}}
    Column(Modifier.fillMaxSize()){BackTitle("导出与分享",vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("export_scroll"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("拜访与沟通 CSV",style=MaterialTheme.typography.headlineMedium)
            Text(if(from.isBlank()&&to.isBlank()&&objectId.isBlank())"范围：全部正式沟通记录" else "范围：当前选择的日期和对象",color=Green)
            OutlinedTextField(from,{from=it},label={Text("开始日期 yyyy-MM-dd（可空）")},modifier=Modifier.fillMaxWidth().testTag("export_from"))
            OutlinedTextField(to,{to=it},label={Text("结束日期 yyyy-MM-dd（可空）")},modifier=Modifier.fillMaxWidth().testTag("export_to"))
            OutlinedButton(onClick={objectDialog=true},modifier=Modifier.fillMaxWidth()){Text(s.find(objectId)?.title ?: "全部对象")}
            TextButton(onClick={from="";to="";objectId=""}){Text("重置为全部记录")}
            PrivacyChoices(body,{body=it},sensitive,{sensitive=it})
            if(!valid) Text("请填写有效日期，结束日期不能早于开始日期",color=MaterialTheme.colorScheme.error)
            Text("当前范围 ${interactions.size} 条记录",modifier=Modifier.testTag("export_count"),style=MaterialTheme.typography.titleMedium)
            Button(onClick={preview=!preview},enabled=valid,modifier=Modifier.fillMaxWidth().testTag("preview_csv")){Text(if(preview)"收起预览" else "预览 CSV 内容")}
            if(preview) Surface(color=Mint,shape=RoundedCornerShape(12.dp)){Text(bytes.toString(Charsets.UTF_8),Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall)}
            Button(onClick={vm.pendingExport=bytes;save.launch("康养沟通记录-${TimeRules.today()}.csv")},enabled=valid,modifier=Modifier.fillMaxWidth().testTag("save_csv")){Text("选择位置保存 CSV")}
            OutlinedButton(onClick={share(context,vm,bytes,"康养沟通记录.csv","text/csv")},enabled=valid,modifier=Modifier.fillMaxWidth().testTag("share_csv")){Text("打开系统分享")}
            Text("CSV 是文本表格，不是 XLSX。采用 UTF-8 BOM；电话和编号导入表格软件时请选择“文本”列，避免前导零丢失。可能被识别为公式的单元格会添加安全前缀。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("活动方案请进入活动详情，选择“预览 / 导出摘要”。业务导出不包含全部关系及附件；换机或留存完整资料请使用完整备份。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    if(objectDialog) AlertDialog(onDismissRequest={objectDialog=false},title={Text("选择导出对象")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        TextButton(onClick={objectId="";objectDialog=false}){Text("全部对象")}
        s.entries.filter{it.kind==Kind.ORGANIZATION||it.kind==Kind.CUSTOMER}.forEach{e->TextButton(onClick={objectId=e.id;objectDialog=false},modifier=Modifier.fillMaxWidth()){Text(e.title+" · "+e["area"])}}
    }},confirmButton={TextButton(onClick={objectDialog=false}){Text("关闭")}})
}

@Composable fun BackupScreen(vm:WorkbookViewModel,s:Snapshot) {
    val context=LocalContext.current
    val manager=remember{BackupManager(vm.repository,context)}
    var password by remember{mutableStateOf("")}
    var repeated by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var prepared by remember{mutableStateOf<PreparedRestore?>(null)}
    var rollback by remember{mutableStateOf(false)}
    var canRollback by remember{mutableStateOf(false)}
    LaunchedEffect(s){canRollback=vm.repository.hasRollback()}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->vm.action{writeDocument(context,uri,vm)}}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null) vm.action {
        busy=true
        try {
            val bytes=withContext(Dispatchers.IO){ context.contentResolver.openInputStream(uri)?.use{stream->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var n:Int
                while(stream.read(buffer).also{n=it}!=-1){if(out.size()+n>96*1024*1024) error("备份文件过大，请选择本应用生成的备份");out.write(buffer,0,n)};out.toByteArray()
            } ?: error("无法读取备份文件") }
            prepared=manager.inspect(bytes,password)
        } finally {busy=false}
    }}
    Column(Modifier.fillMaxSize()){BackTitle("完整备份与恢复",vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).imePadding().testTag("backup_scroll"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("把工作资料，\n妥善留一份。",style=MaterialTheme.typography.headlineMedium,color=Brand)
            Text("包括档案、联系人、服务对象、沟通、活动、待办、草稿、本人资料及附件。",color=Muted)
            Surface(color=Mint,shape=RoundedCornerShape(14.dp)){Text("当前：${s.entries.count{it.kind==Kind.ORGANIZATION}} 个单位 · ${s.entries.count{it.kind==Kind.CUSTOMER}} 个客户\n${s.entries.count{it.kind==Kind.INTERACTION}} 条沟通 · ${s.drafts.size} 份草稿 · ${s.attachments.size} 个附件",Modifier.fillMaxWidth().padding(16.dp))}
            OutlinedTextField(password,{password=it},label={Text("备份口令（至少8个字符）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("backup_password"),singleLine=true)
            OutlinedTextField(repeated,{repeated=it},label={Text("再次输入口令（新建备份时）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("backup_repeat"),singleLine=true)
            Text("口令无法找回。请记在可靠的位置；恢复时输入原口令，不依赖原手机。备份采用 AES-GCM 认证加密。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Button(onClick={
                if(password.length<8||password!=repeated) vm.message="口令至少8个字符，两次输入需一致"
                else vm.action{busy=true;try{vm.pendingExport=manager.export(password);save.launch("康养完整备份-${TimeRules.today()}.kywb")}finally{busy=false}}
            },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("create_backup")){Text(if(busy)"正在处理…" else "制作加密备份")}
            OutlinedButton(onClick={if(password.isBlank())vm.message="先输入备份原口令" else open.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("inspect_backup")){Text("选择备份并校验")}
            Text("先校验，再确认恢复。确认前不改变当前资料。恢复会完整替换本机内容，并保留一次本机回滚快照；请先备份当前资料。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            if(canRollback) OutlinedButton(onClick={rollback=true},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("rollback_restore")){Text("回到上一次恢复前的资料")}
            Text("当前限制：单个附件20 MB，备份附件总量64 MB。完整备份与附件都包含个人资料，建议选择自己掌握的保存位置。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    prepared?.let{p->AlertDialog(onDismissRequest={if(!busy) prepared=null},title={Text("确认完整替换恢复")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("备份时间：${TimeRules.formatDateTime(p.createdAt)}\n应用版本：${p.appVersion}\n\n${p.summary}")}},confirmButton={TextButton(onClick={vm.action{busy=true;try{manager.restore(p);ReminderScheduler(context).rebuild(vm.repository.snapshot(),force=true);prepared=null;password="";repeated="";vm.message="完整恢复成功"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("confirm_restore")){Text("确认替换并恢复")}},dismissButton={TextButton(onClick={prepared=null},enabled=!busy){Text("取消")}})}
    if(rollback) AlertDialog(onDismissRequest={rollback=false},title={Text("回到恢复前的资料")},text={Text("当前内容将被上一次恢复前的完整快照替换。现有内容会成为新的回滚快照。")},confirmButton={TextButton(onClick={rollback=false;vm.action{vm.repository.rollbackLastRestore();ReminderScheduler(context).rebuild(vm.repository.snapshot(),force=true);vm.message="已回到恢复前的资料"}}){Text("确认回滚")}},dismissButton={TextButton(onClick={rollback=false}){Text("取消")}})
}
