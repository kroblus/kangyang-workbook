@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package cn.kangyang.workbook.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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

val Context.uiPreferences by preferencesDataStore(name = "ui_preferences")
private val ShowTips = booleanPreferencesKey("show_tips")

@Composable
fun SettingsScreen(vm: WorkbookViewModel, s: Snapshot, onNotifications: () -> Unit) {
    val context = LocalContext.current
    val tipsFlow = remember(context) { context.uiPreferences.data.map { it[ShowTips] ?: true } }
    val tips by tipsFlow.collectAsState(initial = true)
    var notificationsAllowed by remember { mutableStateOf(ReminderScheduler(context).notificationsAllowed()) }
    LifecycleResumeEffect(Unit) { notificationsAllowed = ReminderScheduler(context).notificationsAllowed(); onPauseOrDispose {} }
    LaunchedEffect(vm.message) { notificationsAllowed = ReminderScheduler(context).notificationsAllowed() }
    Column(Modifier.fillMaxSize()) {
        BackTitle("本人资料与设置", vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings_scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = Mint, shape = AppShapes.card, onClick = { vm.edit(vm.profile) }) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.profile.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${vm.profile["role"]} · ${vm.profile["company"]}", color = Muted)
                    Text("编辑资料与业务标签", color = Green)
                }
            }
            SectionTitle("工作资料")
            MenuAction("导出与分享", "选择记录范围，预览后保存 CSV", Icons.Outlined.IosShare, "open_export") { vm.navigate("export") }
            MenuAction("备份资料", "保存全部工作资料和附件", Icons.Outlined.Lock, "open_backup") { vm.navigate("backup") }
            MenuAction("从备份恢复", "先校验，再确认替换本机资料", Icons.Outlined.Restore, "open_restore") { vm.navigate("restore") }
            SectionTitle("本地提醒")
            Text(if (notificationsAllowed) "提醒通知已开启" else "提醒通知尚未开启，工作台中的跟进仍可使用", color = Muted, modifier = Modifier.testTag("notification_state"))
            if (!notificationsAllowed) SecondaryAction("启用通知提醒", onNotifications, Modifier.fillMaxWidth().testTag("enable_notifications"))
            SecondaryAction("系统通知设置", {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, Modifier.fillMaxWidth().testTag("notification_settings"))
            Text("系统可能延后提醒。只设日期时默认当天 09:00 提醒；强制停止应用后，请重新打开。", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(tips, { value -> vm.action { context.uiPreferences.edit { it[ShowTips] = value } } })
                Spacer(Modifier.width(8.dp)); Text("显示使用提示")
            }
            if (tips) Text("想到要点就点“记一笔”。显示“已保存”后，返回就能找回；对象和跟进可以随后再补。定期备份，换手机也有一份完整资料。", color = Muted)
            SectionTitle("关于")
            Text("康养工作簿 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${if (BuildConfig.FLAVOR == "qa") "独立验收空间 · 仅使用虚构资料" else "个人离线工作空间"}\nAndroid 8.0 及以上 · 北京时间", style = MaterialTheme.typography.bodyMedium)
            Text("资料仅存本机，无须登录。换机前请制作完整备份并保管好口令。", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("当前为测试签名安装包，更新时请覆盖安装，不要卸载。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun MenuAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = AppShapes.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Green)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted) }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

private suspend fun writeDocument(context: Context, uri: android.net.Uri?, vm: WorkbookViewModel) {
    if (uri == null) { vm.pendingExport = null; vm.message = "已取消保存，资料仍保留在本机"; return }
    val bytes = vm.pendingExport ?: run { vm.message = "待保存文件已失效，请重新预览或制作备份"; return }
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("无法写入所选文件，请换一个位置")
    }
    vm.pendingExport = null
    vm.message = "文件已保存"
}

private fun share(context: Context, vm: WorkbookViewModel, bytes: ByteArray, name: String, mime: String) {
    vm.action {
        val file = withContext(Dispatchers.IO) { File(context.cacheDir, "share").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "选择分享方式"))
    }
}

@Composable
private fun PrivacyChoices(body: Boolean, onBody: (Boolean) -> Unit, sensitive: Boolean, onSensitive: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(body, onBody, modifier = Modifier.testTag("export_body"))
        Text("包含详细正文", modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(sensitive, onSensitive, modifier = Modifier.testTag("export_sensitive"))
        Text("包含联系人电话与照护资料", modifier = Modifier.weight(1f))
    }
    Text("勾选后请检查预览，确认适合提供给接收方。", style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
fun SummaryDialog(vm: WorkbookViewModel, entry: Entry, s: Snapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var body by rememberSaveable { mutableStateOf(false) }
    var sensitive by rememberSaveable { mutableStateOf(false) }
    val content = Exports.summary(entry, s, sensitive, body)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> vm.action { writeDocument(context, uri, vm) } }
    AppBottomSheet("摘要预览", onDismiss) {
        PrivacyChoices(body, { body = it }, sensitive, { sensitive = it })
        Surface(shape = AppShapes.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(content, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("summary_preview"), style = MaterialTheme.typography.bodyMedium)
        }
        PrimaryAction("保存文本", { vm.pendingExport = content.toByteArray(Charsets.UTF_8); save.launch("工作摘要.txt") }, Modifier.fillMaxWidth().testTag("save_summary"))
        SecondaryAction("系统分享", { share(context, vm, content.toByteArray(Charsets.UTF_8), "工作摘要.txt", "text/plain") }, Modifier.fillMaxWidth().testTag("share_summary"))
    }
}

private data class ExportPreview(val snapshot: Snapshot, val records: List<Entry>, val body: Boolean, val sensitive: Boolean, val bytes: ByteArray, val scope: String)

@Composable
fun ExportScreen(vm: WorkbookViewModel, s: Snapshot) {
    val context = LocalContext.current
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var objectId by rememberSaveable { mutableStateOf("") }
    var objectDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<String?>(null) }
    var body by rememberSaveable { mutableStateOf(false) }
    var sensitive by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ExportPreview?>(null) }
    val interactions = s.entriesOf(Kind.INTERACTION)
    val records = remember(interactions, from, to, objectId) {
        interactions.asSequence().filter { objectId.isBlank() || it.parentId == objectId }.filter {
            if (from.isBlank() && to.isBlank()) true else {
                val date = TimeRules.formatDate(it.occurredAt)
                (from.isBlank() || date >= from) && (to.isBlank() || date <= to)
            }
        }.sortedBy { it.occurredAt }.toList()
    }
    val objects = remember(s.entries) {
        s.entries.filter { it.kind == Kind.ORGANIZATION || it.kind == Kind.CUSTOMER }
    }
    val valid = (from.isBlank() || TimeRules.parseDate(from) != null) && (to.isBlank() || TimeRules.parseDate(to) != null) && (from.isBlank() || to.isBlank() || from <= to)
    val scope = "${if (objectId.isBlank()) "全部对象" else s.find(objectId)?.title ?: "所选对象"} · ${if (from.isBlank()) "不限开始" else friendlyDate(from)} 至 ${if (to.isBlank()) "不限结束" else friendlyDate(to)}"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> vm.action { writeDocument(context, uri, vm) } }
    Column(Modifier.fillMaxSize()) {
        BackTitle("导出与分享", vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("export_scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = preview
            if (current == null) {
                Text("选择沟通记录", style = MaterialTheme.typography.titleLarge)
                MetadataShelf {
                    MetadataChip("开始日期", from.takeIf { it.isNotBlank() }?.let { "从 ${friendlyDate(it)}" }.orEmpty(),
                        Icons.Outlined.CalendarToday, { dateDialog = "from" }, Modifier.testTag("export_from"))
                    MetadataChip("结束日期", to.takeIf { it.isNotBlank() }?.let { "到 ${friendlyDate(it)}" }.orEmpty(),
                        Icons.Outlined.Event, { dateDialog = "to" }, Modifier.testTag("export_to"))
                    MetadataChip("全部对象", s.find(objectId)?.title.orEmpty(),
                        Icons.Outlined.People, { objectDialog = true }, Modifier.testTag("export_object"))
                }
                TextButton(onClick = { from = ""; to = ""; objectId = "" }) { Text("重置为全部记录") }
                PrivacyChoices(body, { body = it }, sensitive, { sensitive = it })
                if (!valid) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
                Text("当前范围 ${records.size} 条记录", modifier = Modifier.testTag("export_count"), style = MaterialTheme.typography.titleMedium)
                PrimaryAction("预览导出内容", {
                    preview = ExportPreview(s, records, body, sensitive, Exports.csv(s, records, sensitive, body), scope)
                }, Modifier.fillMaxWidth().testTag("preview_csv"), valid)
            } else {
                Text("导出预览 · ${current.records.size} 条", style = MaterialTheme.typography.titleLarge)
                Text(current.scope, color = Muted, modifier = Modifier.testTag("export_scope"))
                Text("${if (current.body) "包含正文" else "不含详细正文"} · ${if (current.sensitive) "包含联系人电话与照护资料" else "不含联系人电话与照护资料"}", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = { preview = null }, modifier = Modifier.testTag("change_export_range")) { Text("修改范围或字段") }
                current.records.forEach { record ->
                    Surface(shape = AppShapes.card, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Text(Exports.summary(record, current.snapshot, current.sensitive, current.body), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (current.records.isEmpty()) Text("这个范围没有沟通记录，可以返回修改范围。", color = Muted)
                PrimaryAction("选择位置保存 CSV", { vm.pendingExport = current.bytes; save.launch("康养沟通记录-${TimeRules.today()}.csv") }, Modifier.fillMaxWidth().testTag("save_csv"))
                SecondaryAction("打开系统分享", { share(context, vm, current.bytes, "康养沟通记录.csv", "text/csv") }, Modifier.fillMaxWidth().testTag("share_csv"))
            }
            Text("CSV 可用表格软件打开，电话列请按“文本”导入。活动方案可从活动详情导出摘要；包含全部资料和附件请使用备份。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    if (objectDialog) ObjectPickerPanel(objects, objectId.ifBlank { null }, { objectId = it.orEmpty() },
        { objectDialog = false }, title = "选择导出对象", contacts = s.entriesOf(Kind.CONTACT), clearLabel = "全部对象")
    when (dateDialog) {
        "from" -> AppDatePicker(from, { from = it }, { dateDialog = null }, "从哪天开始")
        "to" -> AppDatePicker(to, { to = it }, { dateDialog = null }, "到哪天为止")
    }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, label: String, tag: String, enabled: Boolean) {
    // Password masking is the only text-field specialization; geometry is the shared control token.
    OutlinedTextField(value, onValue, label = { Text(label) }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag), singleLine = true, enabled = enabled,
        shape = AppShapes.control, textStyle = MaterialTheme.typography.bodyLarge)
}

@Composable
fun BackupScreen(vm: WorkbookViewModel, s: Snapshot, restoring: Boolean = false) {
    val context = LocalContext.current
    val manager = remember { BackupManager(vm.repository, context) }
    var password by remember(restoring, vm.uiEpoch) { mutableStateOf("") }
    var repeated by remember(restoring, vm.uiEpoch) { mutableStateOf("") }
    var busy by remember(restoring, vm.uiEpoch) { mutableStateOf(false) }
    var prepared by remember(restoring, vm.uiEpoch) { mutableStateOf<PreparedRestore?>(null) }
    var rollback by remember(restoring, vm.uiEpoch) { mutableStateOf(false) }
    var canRollback by remember { mutableStateOf(false) }
    LaunchedEffect(s, vm.uiEpoch) { canRollback = vm.repository.hasRollback() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> vm.action { writeDocument(context, uri, vm) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.action {
            busy = true; prepared = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count: Int
                        while (stream.read(buffer).also { count = it } != -1) {
                            if (out.size() + count > 80 * 1024 * 1024) error("备份文件过大，请选择本应用生成的备份")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("无法读取备份文件")
                }
                prepared = manager.inspect(bytes, password)
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTitle(if (restoring) "从备份恢复" else "备份资料", vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).imePadding().testTag("backup_scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (restoring) "找回完整工作资料" else "留一份完整工作资料", style = MaterialTheme.typography.titleLarge, color = Brand)
            if (restoring) {
                Text("输入制作这份备份时的口令，选择备份文件。校验通过后会显示内容范围，再由你确认。", color = Muted)
                PasswordField(password, { password = it }, "备份原口令", "backup_password", !busy)
                PrimaryAction(if (busy) "正在校验…" else "选择备份并校验", {
                    if (password.isBlank()) vm.message = "请先输入备份原口令" else open.launch(arrayOf("*/*"))
                }, Modifier.fillMaxWidth().testTag("inspect_backup"), !busy)
                Text("恢复会替换本机全部单位、客户、联系人、服务对象、沟通记录、活动、跟进、草稿、本人资料及附件。建议先备份当前资料。确认前不会改动。", style = MaterialTheme.typography.bodyMedium, color = Muted)
                TextButton(onClick = { vm.navigate("backup") }, modifier = Modifier.fillMaxWidth()) { Text("先备份当前资料") }
                if (canRollback) SecondaryAction("回到上一次恢复前的资料", { rollback = true }, Modifier.fillMaxWidth().testTag("rollback_restore"), !busy)
                Text("口令无法找回；口令不正确或文件损坏时，请检查原口令或改用其他完整备份。", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                Text("包括单位、客户、联系人、服务对象、沟通、活动、跟进、草稿、本人资料及附件。", color = Muted)
                Surface(color = Mint, shape = AppShapes.card) {
                    Text("${s.entries.count { it.kind == Kind.ORGANIZATION }} 个单位 · ${s.entries.count { it.kind == Kind.CUSTOMER }} 个客户\n${s.entries.count { it.kind == Kind.INTERACTION }} 条沟通 · ${s.drafts.size} 份草稿 · ${s.attachments.size} 个附件", Modifier.fillMaxWidth().padding(16.dp))
                }
                PasswordField(password, { password = it }, "设置备份口令", "backup_password", !busy)
                PasswordField(repeated, { repeated = it }, "再次输入口令", "backup_repeat", !busy)
                Text("至少8个字符。口令无法找回，请记在可靠的位置。恢复时使用这个口令，不依赖原手机。", style = MaterialTheme.typography.bodyMedium, color = Muted)
                PrimaryAction(if (busy) "正在制作备份…" else "制作备份并保存", {
                    if (password.length < 8 || password != repeated) vm.message = "口令至少8个字符，两次输入需一致"
                    else vm.action {
                        busy = true
                        try { vm.pendingExport = manager.export(password); save.launch("康养完整备份-${TimeRules.today()}.kywb") }
                        finally { busy = false }
                    }
                }, Modifier.fillMaxWidth().testTag("create_backup"), !busy)
                Text("单个附件最多20 MB，备份附件总量最多64 MB。请保存到自己掌握的位置；分享备份时，口令应另行保管。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
    prepared?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) prepared = null }, title = { Text("确认替换本机资料") },
            shape = AppShapes.dialog, containerColor = MaterialTheme.colorScheme.surface,
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("备份时间：${TimeRules.formatDateTime(candidate.createdAt)}\n\n${candidate.summary}")
                Spacer(Modifier.height(12.dp))
                Text("本机所有档案、联系人、服务对象、记录、活动、跟进、草稿、本人资料和附件会被这份备份替换。", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                vm.action {
                    busy = true
                    try { vm.restoreData(manager, candidate); prepared = null; password = ""; repeated = "" }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("confirm_restore")) { Text("确认替换并恢复") } },
            dismissButton = { TextButton(onClick = { prepared = null }, enabled = !busy) { Text("暂不恢复") } })
    }
    if (rollback) ConfirmDialog("回到恢复前的资料", "本机全部资料将被上一次恢复前的完整快照替换。当前资料会成为新的回滚快照。", {
        rollback = false
        vm.action { busy = true; try { vm.restoreData(manager, rollback = true) } finally { busy = false } }
    }, { rollback = false }, confirmLabel = "确认回滚", dismissLabel = "取消")
}
