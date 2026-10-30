package cn.kangyang.workbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.data.DataValidation
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.domain.TimeRules
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/** Main content stays on the page; optional details are edited one at a time. */
@Composable
fun EntryEditor(
    entry: Entry,
    entries: List<Entry>,
    attachments: List<Attachment>,
    tags: List<String>,
    draftStatus: String,
    saving: Boolean,
    onChange: (Entry) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onAddPhoto: () -> Unit,
    onAddFile: () -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onEnableNotifications: () -> Unit,
    onRetryDraft: () -> Unit = {},
    attachmentStatus: String? = null,
) {
    var panel by rememberSaveable(entry.id) { mutableStateOf("") }
    var pendingPanel by rememberSaveable(entry.id) { mutableStateOf("") }
    var afterMenu by rememberSaveable(entry.id) { mutableStateOf("") }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val byKind = remember(entries) { entries.groupBy { it.kind } }
    val byId = remember(entries) { entries.associateBy { it.id } }
    val organizations = byKind[Kind.ORGANIZATION].orEmpty()
    val customers = byKind[Kind.CUSTOMER].orEmpty()
    val activities = byKind[Kind.ACTIVITY].orEmpty()
    val dossiers = remember(organizations, customers) { organizations + customers }
    val taskObjects = remember(dossiers, activities) { dossiers + activities }
    val files = remember(attachments, entry.id) { attachments.filter { it.ownerId == entry.id } }
    val groups = remember(entry.kind) { detailGroups(entry.kind) }
    val bodyKey = primaryDetail(entry.kind)
    val commonKeys = when (entry.kind) {
        Kind.ORGANIZATION -> listOf("type", "stage", "area")
        Kind.CUSTOMER -> listOf("stage", "tags")
        Kind.CONTACT -> listOf("parentId", "role", "phone")
        Kind.RECIPIENT -> listOf("parentId", "relationship")
        Kind.ACTIVITY -> listOf("status", "start", "end")
        Kind.TASK -> listOf("date") + (if (entry["date"].isNotBlank()) listOf("time", "reminder") else emptyList()) +
            (if (entry.sourceId == null) listOf("parentId") else emptyList())
        Kind.PROFILE -> listOf("role", "company")
        Kind.INTERACTION -> emptyList()
    }
    fun value(key: String) = if (key == "parentId") entry.parentId.orEmpty() else entry[key]
    fun update(key: String, value: String) {
        error = null
        val fields = when {
            entry.kind == Kind.TASK && key == "date" && value.isBlank() -> entry.fields + mapOf("date" to "", "time" to "", "reminder" to "false")
            else -> entry.fields + (key to value)
        }
        onChange(if (key == "parentId") entry.copy(parentId = value.ifBlank { null }) else entry.copy(fields = fields))
        if ((key == "reminder" && value == "true") || (key == "remindMinutes" && value.isNotEmpty())) onEnableNotifications()
    }
    fun label(key: String) = when (key) {
        "parentId" -> if (entry.kind == Kind.CONTACT || entry.kind == Kind.RECIPIENT) "所属档案" else "关联对象"
        "remindMinutes" -> "活动提醒"
        "completionNote" -> if (entry["status"] == "已完成") "完成备注" else "随手记"
        else -> fieldLabel(key).removeSuffix("（选填）")
    }
    fun summary(key: String): String {
        val raw = value(key)
        return when (key) {
            "parentId", "organizationId", "linkedOrganization", "linkedActivity" -> byId[raw]?.title.orEmpty()
            "date", "intentDate" -> if (raw.isBlank()) "" else friendlyDate(raw)
            "start", "end" -> if (raw.isBlank()) "" else "${if (key == "start") "开始" else "结束"} ${raw.replace("-", "/")}"
            "expectedCount" -> if (raw.isBlank()) "" else "预计 $raw 人"
            "actualCount" -> if (raw.isBlank()) "" else "到场 $raw 人"
            "budget" -> if (raw.isBlank()) "" else "预算 $raw"
            "tags" -> raw.lines().filter(String::isNotBlank).joinToString(" · ")
            else -> detailOptions(entry.kind, key)?.firstOrNull { it.first == raw }?.second
                ?: if (key in paragraphDetails && raw.isNotBlank()) "${label(key)}：$raw" else raw
        }
    }
    fun destination(key: String): String = when {
        entry.kind == Kind.TASK && key in setOf("time", "reminder") && entry["date"].isBlank() -> "date"
        entry.kind == Kind.ACTIVITY && key == "remindMinutes" && entry["start"].isBlank() -> "start"
        entry.kind == Kind.ACTIVITY && key == "remindMinutes" && entry["end"].isBlank() -> "end"
        else -> key
    }
    fun show(key: String) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        pendingPanel = destination(key)
    }
    LaunchedEffect(pendingPanel) {
        val requested = pendingPanel
        if (requested.isNotBlank()) {
            snapshotFlow { ime.getBottom(density) }.first { it == 0 }
            panel = requested
            pendingPanel = ""
        }
    }
    fun finish(action: () -> Unit) { focus.clearFocus(force = true); keyboard?.hide(); action() }
    @Composable fun chip(key: String, inMenu: Boolean = false, open: (String) -> Unit = ::show) {
        MetadataChip(label(key), summary(key), detailIcon(key), { open(key) }, Modifier.testTag(if (inMenu) "detail_$key" else detailTag(entry.kind, key)))
    }

    Column(Modifier.fillMaxSize().imePadding().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", { finish(onBack) }, enabled = !saving)
            Text(editorHeading(entry.kind), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                error = validateEditor(entry)
                if (error == null && entry.kind in setOf(Kind.CONTACT, Kind.RECIPIENT) && entry.parentId == null) error = "先选一下所属档案，就能收好这份资料"
                if (error == null) finish(onSave)
            }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("save_entry")) {
                Text(if (saving) "正在保存…" else "完成")
            }
        }
        Text(
            when (draftStatus) {
                "已保存", "写下内容后自动保存", "开始填写后自动保存", "" -> "先记下要点，其他信息可以慢慢补"
                "草稿已保存" -> "已暂存 · 点完成收好这份记录"
                else -> draftStatus
            },
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp).testTag("draft_status")
                .clickable(enabled = draftStatus.contains("重试")) { onRetryDraft() },
            style = MaterialTheme.typography.bodySmall,
            color = if (draftStatus.contains("未保存") || draftStatus.contains("失败")) MaterialTheme.colorScheme.error else Muted,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("form_error"), style = MaterialTheme.typography.bodyMedium) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("editor_scroll").padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CanvasEntryText(entry.title, { error = null; onChange(entry.copy(title = it)) }, titlePrompt(entry.kind), "title", heading = true)
            if (bodyKey != null) {
                CanvasEntryText(entry[bodyKey], { update(bodyKey, it) }, bodyPrompt(entry.kind), bodyKey)
            }
            MetadataShelf { commonKeys.forEach { chip(it) } }
            if (entry.kind == Kind.ACTIVITY && entry["start"].isBlank() && entry["end"].isBlank()) {
                Text("时间还没定也没关系，先把想法留下来。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (entry.kind == Kind.TASK && entry.sourceId != null) {
                Text("来自沟通记录${byId[entry.parentId]?.title?.let { " · $it" }.orEmpty()}\n关联对象可在原记录中调整。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            val added = groups.flatMap { it.second }.filter { it !in commonKeys && it != bodyKey && value(it).isNotBlank() }
            if (added.isNotEmpty()) {
                MetadataShelf { added.take(5).forEach { chip(it) } }
            }
            TextButton(onClick = { show("more") }, modifier = Modifier.heightIn(min = 48.dp).testTag("more_fields"), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (added.size > 5) "再补一点 · 还有 ${added.size - 5} 项已记下" else "再补一点")
            }
            if (entry.kind !in listOf(Kind.CONTACT, Kind.RECIPIENT)) {
                if (files.isNotEmpty()) files.forEach { AttachmentRow(it, onOpenAttachment, onRemoveAttachment) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish(onAddPhoto) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("add_photo"), contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("照片")
                    }
                    TextButton(onClick = { finish(onAddFile) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("add_file"), contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("文件")
                    }
                }
                if (entry.kind == Kind.PROFILE) Text("也可以放一张自己的微信二维码。", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            attachmentStatus?.let { Text(it, modifier = Modifier.testTag("attachment_status"), style = MaterialTheme.typography.bodySmall, color = if (it.contains("未导入")) MaterialTheme.colorScheme.error else Muted) }
            Spacer(Modifier.height(16.dp))
        }
    }

    when {
        panel == "more" -> AppBottomSheet("想补充哪一点？", {
            panel = afterMenu
            afterMenu = ""
        }) { close ->
            Text("用到哪一项，再补哪一项。", style = MaterialTheme.typography.bodyMedium, color = Muted)
            groups.forEach { (heading, keys) ->
                Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                MetadataShelf {
                    keys.filter { !(it == "parentId" && entry.kind == Kind.TASK && entry.sourceId != null) }.forEach { key ->
                        chip(key, inMenu = true) { selected ->
                            afterMenu = destination(selected)
                            close()
                        }
                    }
                }
            }
            if (entry.kind == Kind.ACTIVITY && entry["status"].ifBlank { "筹备中" } == "筹备中" && (entry["start"].isNotBlank() || entry["end"].isNotBlank())) {
                TextButton(onClick = {
                    onChange(entry.copy(fields = entry.fields + mapOf("start" to "", "end" to "", "remindMinutes" to "")))
                    close()
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_pending")) { Text("时间还没定，稍后再安排") }
            }
        }
        panel in setOf("parentId", "organizationId", "linkedOrganization", "linkedActivity") -> {
            val choices = when (panel) {
                "linkedActivity" -> activities
                "parentId" -> when (entry.kind) { Kind.RECIPIENT -> customers; Kind.TASK -> taskObjects; else -> dossiers }
                else -> organizations
            }
            ObjectPickerPanel(choices, value(panel).ifBlank { null }, { update(panel, it.orEmpty()) }, { panel = "" },
                title = label(panel), allowClear = !(panel == "parentId" && entry.kind in setOf(Kind.CONTACT, Kind.RECIPIENT)))
        }
        panel == "tags" && entry.kind != Kind.PROFILE -> {
            val selected = entry["tags"].lines().filter(String::isNotBlank)
            AppBottomSheet("业务需求", { panel = "" }, bottomBar = { close -> PrimaryAction("完成", close, Modifier.fillMaxWidth().testTag("detail_confirm")) }) {
                MetadataShelf {
                    (tags + selected).distinct().forEach { tag ->
                        AppFilterChip(tag, tag in selected, {
                            update("tags", (if (tag in selected) selected - tag else selected + tag).joinToString("\n"))
                        }, Modifier.testTag("option_$tag"))
                    }
                }
            }
        }
        panel in setOf("date", "intentDate") -> AppDatePicker(value(panel), { update(panel, it) }, { panel = "" }, label(panel))
        panel == "time" -> AppTimePicker(value(panel), { update(panel, it) }, { panel = "" }, label(panel))
        panel in setOf("start", "end") -> EntryDateTimePicker(value(panel), { update(panel, it) }, { panel = "" }, label(panel))
        panel.isNotBlank() -> {
            val key = panel
            val choices = detailOptions(entry.kind, key)
            if (choices != null) {
                AppBottomSheet(label(key), { panel = "" }) { close ->
                    MetadataShelf {
                        choices.forEach { (option, title) ->
                            AppFilterChip(title, value(key) == option, { update(key, option); close() }, Modifier.testTag("option_$title"))
                        }
                    }
                    if (key == "reminder" && value(key) == "true") Text("没有具体时刻时，当天 09:00 提醒。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (key == "status" && entry.kind == Kind.ACTIVITY) Text("筹备中可以先不定时间；待开展或已完成时，再补齐起止时间。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            } else TextDetailSheet(
                title = label(key), value = value(key), onConfirm = { update(key, it) }, onDismiss = { panel = "" },
                placeholder = detailPrompt(key), singleLine = key !in paragraphDetails,
                keyboardOptions = KeyboardOptions(keyboardType = when (key) { "phone" -> KeyboardType.Phone; "expectedCount", "actualCount" -> KeyboardType.Number; else -> KeyboardType.Text }),
                testTag = "field_$key", supportingText = detailHint(key),
            )
        }
    }
}

@Composable
private fun CanvasEntryText(value: String, onValue: (String) -> Unit, placeholder: String, key: String, heading: Boolean = false) {
    val style = if (heading) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge
    BasicTextField(value, onValue, textStyle = style.copy(color = Ink), cursorBrush = SolidColor(Green),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (heading) 56.dp else 116.dp).padding(top = 12.dp, bottom = 4.dp).testTag("field_$key"),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = Muted.copy(alpha = .75f)); inner() } })
}

@Composable
private fun EntryDateTimePicker(value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, title: String) {
    var timeStep by rememberSaveable { mutableStateOf(false) }
    var next by rememberSaveable { mutableStateOf(false) }
    var pickedDate by rememberSaveable { mutableStateOf("") }
    val parsed = TimeRules.parseDateTime(value)?.let { Instant.ofEpochMilli(it).atZone(TimeRules.zone) }
    if (!timeStep) AppDatePicker(parsed?.toLocalDate()?.toString().orEmpty(), { pickedDate = it; next = true }, {
        if (next) { timeStep = true; next = false } else onDismiss()
    }, title, allowClear = false)
    else AppTimePicker(parsed?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty(), { onValue("$pickedDate $it") }, onDismiss, "选择时刻", allowClear = false)
}

private fun primaryDetail(kind: Kind): String? = when (kind) {
    Kind.ORGANIZATION -> "notes"
    Kind.CUSTOMER -> "needs"
    Kind.RECIPIENT -> "care"
    Kind.ACTIVITY -> "plan"
    Kind.TASK -> "completionNote"
    else -> null
}

private fun titlePrompt(kind: Kind) = when (kind) {
    Kind.ORGANIZATION -> "这家单位叫什么？"
    Kind.CUSTOMER -> "怎么称呼这位客户？"
    Kind.CONTACT -> "联系人称呼"
    Kind.RECIPIENT -> "服务对象称呼"
    Kind.ACTIVITY -> "想安排一场什么活动？"
    Kind.TASK -> "接下来，做点什么？"
    Kind.PROFILE -> "你的名字"
    Kind.INTERACTION -> "标题"
}

private fun bodyPrompt(kind: Kind) = when (kind) {
    Kind.ORGANIZATION -> "先写下对这家单位的了解…"
    Kind.CUSTOMER -> "聊到了什么？对方在意哪些事情？"
    Kind.RECIPIENT -> "写下对方提供的照护情况…"
    Kind.ACTIVITY -> "把活动的想法、议程先写下来…"
    Kind.TASK -> "有需要记住的细节，也写在这里…"
    else -> "写一点想记住的事…"
}

private fun detailGroups(kind: Kind): List<Pair<String, List<String>>> = when (kind) {
    Kind.ORGANIZATION -> listOf("认识这家单位" to listOf("type", "stage", "nature"), "位置与业务" to listOf("area", "address", "tags"))
    Kind.CUSTOMER -> listOf("需求与进展" to listOf("stage", "urgency", "tags", "budget", "intentDate"), "从哪里认识" to listOf("source", "area", "linkedOrganization", "linkedActivity"), "还想记住" to listOf("notes"))
    Kind.CONTACT -> listOf("联系这位朋友" to listOf("parentId", "role", "phone", "wechat", "primary"))
    Kind.RECIPIENT -> listOf("了解这位服务对象" to listOf("parentId", "relationship", "ageBand", "notes"))
    Kind.ACTIVITY -> listOf("时间与地点" to listOf("status", "start", "end", "location", "organizationId", "remindMinutes"), "准备这场活动" to listOf("type", "goal", "audience", "expectedCount", "division", "materials", "precautions"), "结束后再回顾" to listOf("actualCount", "execution", "review", "followup"))
    Kind.TASK -> listOf("安排一下" to listOf("date", "time", "reminder", "parentId", "priority", "status"))
    Kind.PROFILE -> listOf("关于你" to listOf("role", "company", "phone", "wechat"), "这本工作簿" to listOf("appName", "tags"))
    Kind.INTERACTION -> emptyList()
}

private fun detailOptions(kind: Kind, key: String): List<Pair<String, String>>? = when (key) {
    "type" -> (if (kind == Kind.ORGANIZATION) OrganizationTypes else listOf("线下交流", "健康宣教", "机构参访", "器具体验", "照护培训", "其他")).map { it to it }
    "nature" -> OrganizationNatures.map { it to it }
    "stage" -> (if (kind == Kind.ORGANIZATION) OrganizationStages else CustomerStages).map { it to it }
    "urgency" -> listOf("普通", "近期", "紧急").map { it to it }
    "priority" -> TaskPriorities.map { it to it }
    "status" -> (if (kind == Kind.ACTIVITY) ActivityStatuses else TaskStatuses).map { it to it }
    "primary" -> listOf("false" to "普通联系人", "true" to "主要联系人")
    "reminder" -> listOf("false" to "不提醒", "true" to "提醒我")
    "remindMinutes" -> listOf("" to "不提醒", "10" to "提前 10 分钟", "30" to "提前 30 分钟", "60" to "提前 1 小时", "1440" to "提前 1 天")
    else -> null
}

private fun detailTag(kind: Kind, key: String) = if (detailOptions(kind, key) != null || key in setOf("parentId", "organizationId", "linkedOrganization", "linkedActivity", "date", "time", "start", "end", "intentDate") || (key == "tags" && kind != Kind.PROFILE)) "field_$key" else "edit_field_$key"
private val paragraphDetails = setOf("notes", "care", "needs", "plan", "goal", "materials", "division", "precautions", "execution", "review", "followup", "completionNote", "tags", "address")
private fun detailPrompt(key: String) = when (key) {
    "role" -> "例如：业务联系人、女儿"
    "relationship" -> "例如：李女士的母亲"
    "tags" -> "每行写一项业务"
    "notes" -> "还有什么想记住的？"
    else -> "写下${fieldLabel(key).removeSuffix("（选填）")}…"
}
private fun detailHint(key: String): String? = when (key) {
    "phone" -> "手机、座机和分机都可以，不会自动拨打。"
    "care" -> "只记录对方提供的信息，不作医疗诊断。"
    "tags" -> "已保存资料的标签会继续保留。"
    "appName" -> "用于应用内工作台，桌面安装名称保持不变。"
    else -> null
}
private fun detailIcon(key: String): ImageVector = when (key) {
    "date", "intentDate", "start", "end" -> Icons.Outlined.CalendarMonth
    "time" -> Icons.Outlined.Schedule
    "reminder", "remindMinutes" -> Icons.Outlined.NotificationsNone
    "parentId", "linkedOrganization", "organizationId", "linkedActivity" -> Icons.Outlined.Link
    "phone" -> Icons.Outlined.Phone
    "wechat", "role", "relationship", "primary", "audience", "ageBand" -> Icons.Outlined.PersonOutline
    "area", "address", "location" -> Icons.Outlined.Place
    "tags", "type", "nature" -> Icons.Outlined.Label
    "status", "stage", "priority", "urgency" -> Icons.Outlined.Flag
    else -> Icons.Outlined.Notes
}

private fun validateEditor(entry: Entry): String? {
    if (entry.kind == Kind.ACTIVITY) listOf("expectedCount", "actualCount").forEach {
        if (entry[it].isNotBlank() && entry[it].toIntOrNull()?.let { count -> count >= 0 } != true) return "${fieldLabel(it)}应为不小于零的整数"
    }
    return runCatching { DataValidation.validateFields(entry) }.exceptionOrNull()?.message
}
