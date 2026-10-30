package cn.kangyang.workbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.DataValidation

/** All business values are controlled by the ViewModel; only expansion/dialog state lives here. */
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
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var scheduleExpanded by rememberSaveable(entry.id) { mutableStateOf(entry["start"].isNotBlank() || entry["end"].isNotBlank() || entry["status"] == "待开展") }
    var reviewExpanded by rememberSaveable(entry.id) { mutableStateOf(entry["status"] == "已完成") }
    var taskDetailsExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    fun update(key: String, value: String) {
        error = null
        onChange(entry.copy(fields = entry.fields + (key to value)))
    }
    fun changeParent(id: String) {
        error = null
        onChange(entry.copy(parentId = id.ifEmpty { null }))
    }
    fun get(key: String) = entry.fields[key].orEmpty()

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, enabled = !saving)
                    Text(editorHeading(entry.kind), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Text(
                    when (draftStatus) {
                        "已保存", "写下内容后自动保存", "开始填写后自动保存", "" -> "填写后点保存，进度会自动暂存"
                        "草稿已保存" -> "填写已暂存，点保存后生效"
                        else -> draftStatus
                    },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).testTag("draft_status").clickable(enabled = draftStatus.contains("重试")) { onRetryDraft() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draftStatus.contains("未保存") || draftStatus.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                attachmentStatus?.let { status ->
                    Text(status,modifier=Modifier.padding(start=20.dp,end=20.dp,bottom=8.dp).testTag("attachment_status"),
                        style=MaterialTheme.typography.bodySmall,
                        color=if(status.contains("未导入"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("editor_scroll").padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entry.kind != Kind.INTERACTION) {
                EditorText(
                    label = when (entry.kind) {
                        Kind.ORGANIZATION -> "单位名称 *"
                        Kind.CUSTOMER -> "客户 / 家庭称呼 *"
                        Kind.CONTACT -> "联系人称呼 *"
                        Kind.RECIPIENT -> "服务对象称呼 *"
                        Kind.ACTIVITY -> "活动名称 *"
                        Kind.TASK -> "要做什么 *"
                        Kind.PROFILE -> "姓名 *"
                        else -> "名称"
                    },
                    value = entry.title, key = "title", multiline = true, minLines = 1,
                    onValue = { error = null; onChange(entry.copy(title = it)) },
                )
            }
            when (entry.kind) {
                Kind.ORGANIZATION -> {
                    EditorSelect("单位类型 *", get("type"), "type", OrganizationTypes, onValue = { update("type", it) })
                    EditorText("区域", get("area"), "area", onValue = { update("area", it) })
                    EditorSelect("合作阶段", get("stage").ifEmpty { "待联系" }, "stage", OrganizationStages, onValue = { update("stage", it) })
                    MoreButton(expanded) { expanded = !expanded }
                    ExpandingSection(expanded) {
                        EditorSelect("主体性质（选填）", get("nature").ifEmpty { "暂不清楚" }, "nature", OrganizationNatures, onValue = { update("nature", it) })
                        EditorHint("主体性质按实际了解填写，可保留暂不清楚。")
                        EditorText("地址", get("address"), "address", multiline = true, onValue = { update("address", it) })
                        EditorTags(get("tags"), tags, onValue = { update("tags", it) })
                        EditorText("备注", get("notes"), "notes", multiline = true, onValue = { update("notes", it) })
                    }
                }
                Kind.CUSTOMER -> {
                    EditorTags(get("tags"), tags, onValue = { update("tags", it) })
                    EditorText("需求说明", get("needs"), "needs", multiline = true, onValue = { update("needs", it) })
                    EditorSelect("进展阶段", get("stage").ifEmpty { "新咨询" }, "stage", CustomerStages, onValue = { update("stage", it) })
                    MoreButton(expanded) { expanded = !expanded }
                    ExpandingSection(expanded) {
                        EditorSelect("紧急程度", get("urgency").ifEmpty { "普通" }, "urgency", listOf("普通", "近期", "紧急"), onValue = { update("urgency", it) })
                        EditorText("来源", get("source"), "source", onValue = { update("source", it) })
                        EditorText("服务区域", get("area"), "area", onValue = { update("area", it) })
                        EditorDate("意向日期（选填）", get("intentDate"), "intentDate", onValue = { update("intentDate", it) })
                        EditorText("预算（按需填写）", get("budget"), "budget", onValue = { update("budget", it) })
                        EntryRelation("主动关联的单位", get("linkedOrganization"), "linkedOrganization", entries.filter { it.kind == Kind.ORGANIZATION }, "不关联") { update("linkedOrganization", it) }
                        EntryRelation("来源活动", get("linkedActivity"), "linkedActivity", entries.filter { it.kind == Kind.ACTIVITY }, "不关联") { update("linkedActivity", it) }
                        EditorText("备注", get("notes"), "notes", multiline = true, onValue = { update("notes", it) })
                    }
                }
                Kind.CONTACT -> {
                    EntryRelation("所属档案", entry.parentId.orEmpty(), "parentId", entries.filter { it.kind == Kind.ORGANIZATION || it.kind == Kind.CUSTOMER }, "请选择") { changeParent(it) }
                    EditorText("角色 / 职务", get("role"), "role", supporting = "例如：女儿、决策联系人、付款联系人；按实际了解填写", onValue = { update("role", it) })
                    EditorText("电话（选填）", get("phone"), "phone", keyboardType = KeyboardType.Phone, supporting = "支持手机、办公座机及分机，不会自动拨打", onValue = { update("phone", it) })
                    EditorText("微信备注", get("wechat"), "wechat", onValue = { update("wechat", it) })
                    EditorSwitch("设为主要联系人", get("primary") == "true", "primary") { update("primary", it.toString()) }
                }
                Kind.RECIPIENT -> {
                    EntryRelation("所属客户 / 家庭", entry.parentId.orEmpty(), "parentId", entries.filter { it.kind == Kind.CUSTOMER }, "请选择") { changeParent(it) }
                    EditorText("与联系人关系", get("relationship"), "relationship", supporting = "例如：联系人李女士的母亲", onValue = { update("relationship", it) })
                    EditorText("年龄段（选填）", get("ageBand"), "ageBand", onValue = { update("ageBand", it) })
                    EditorText("照护情况（按需填写）", get("care"), "care", multiline = true, supporting = "仅记录使用者提供的信息，不作医疗诊断；不要填写身份证或详细病历", onValue = { update("care", it) })
                    EditorText("备注", get("notes"), "notes", multiline = true, onValue = { update("notes", it) })
                }
                // INTERACTION is routed to the dedicated NoteEditorScreen.
                Kind.INTERACTION -> Unit
                Kind.ACTIVITY -> {
                    EditorText("方案与议程", get("plan"), "plan", multiline = true, minLines = 4, onValue = { update("plan", it) })
                    EditorSelect("活动状态", get("status").ifEmpty { "筹备中" }, "status", ActivityStatuses, onValue = {
                        update("status", it)
                        if (it == "待开展") scheduleExpanded = true
                        if (it == "已完成") reviewExpanded = true
                    })
                    if (get("start").isBlank() && get("end").isBlank()) EditorHint("时间待定也可以保存，之后再安排。")
                    SecondaryAction(if (scheduleExpanded) "收起时间与地点" else "安排时间与地点", { scheduleExpanded = !scheduleExpanded }, Modifier.fillMaxWidth().testTag("activity_schedule"))
                    ExpandingSection(scheduleExpanded) {
                        EditorDateTime("开始时间", get("start"), "start", onValue = { update("start", it) })
                        EditorDateTime("结束时间", get("end"), "end", onValue = { update("end", it) })
                        if (get("status").ifBlank { "筹备中" } == "筹备中" && (get("start").isNotBlank() || get("end").isNotBlank())) {
                            TextButton(onClick = {
                                error = null
                                onChange(entry.copy(fields = entry.fields + mapOf("start" to "", "end" to "", "status" to "筹备中", "remindMinutes" to "")))
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("activity_pending")) { Text("改为时间待定") }
                        }
                        EditorText("地点", get("location"), "location", onValue = { update("location", it) })
                        EntryRelation("关联单位", get("organizationId"), "organizationId", entries.filter { it.kind == Kind.ORGANIZATION }, "不关联") { update("organizationId", it) }
                        if (get("start").isNotBlank() && get("end").isNotBlank() && get("status") !in setOf("已完成", "已取消")) {
                            EditorChoice("活动提醒", get("remindMinutes"), "remindMinutes", listOf(
                                "" to "关闭", "10" to "提前10分钟", "30" to "提前30分钟", "60" to "提前1小时", "1440" to "提前1天"
                            )) { update("remindMinutes", it); if (it.isNotEmpty()) onEnableNotifications() }
                            if (get("remindMinutes").isNotEmpty()) EditorHint("系统可能延后提醒；独立筹备事项的日期保持不变。")
                        }
                    }
                    EditorText("物料清单", get("materials"), "materials", multiline = true, supporting = "保存后可逐项添加筹备跟进", onValue = { update("materials", it) })
                    MoreButton(expanded) { expanded = !expanded }
                    ExpandingSection(expanded) {
                        EditorSection("筹备资料")
                        EditorSelect("活动类型", get("type").ifEmpty { "线下交流" }, "type", listOf("线下交流", "健康宣教", "机构参访", "器具体验", "照护培训", "其他"), onValue = { update("type", it) })
                        EditorText("活动目标", get("goal"), "goal", multiline = true, onValue = { update("goal", it) })
                        EditorText("面向人群", get("audience"), "audience", onValue = { update("audience", it) })
                        EditorText("预计人数", get("expectedCount"), "expectedCount", keyboardType = KeyboardType.Number, onValue = { update("expectedCount", it) })
                        EditorText("人员分工", get("division"), "division", multiline = true, onValue = { update("division", it) })
                        EditorText("注意事项", get("precautions"), "precautions", multiline = true, onValue = { update("precautions", it) })
                    }
                    SecondaryAction(if (reviewExpanded) "收起执行与复盘" else "执行与复盘", { reviewExpanded = !reviewExpanded }, Modifier.fillMaxWidth().testTag("activity_review"))
                    if (reviewExpanded) {
                        EditorText("实际人数", get("actualCount"), "actualCount", keyboardType = KeyboardType.Number, onValue = { update("actualCount", it) })
                        EditorText("执行情况", get("execution"), "execution", multiline = true, onValue = { update("execution", it) })
                        EditorText("活动复盘", get("review"), "review", multiline = true, onValue = { update("review", it) })
                        EditorText("后续跟进", get("followup"), "followup", multiline = true, onValue = { update("followup", it) })
                    }
                    if (get("status") == "已取消") EditorHint("保存时会确认是否取消，并可选择保留未完成筹备事项。")
                }
                Kind.TASK -> {
                    EditorDate("截止日期", get("date"), "date", onValue = {
                        error = null
                        onChange(entry.copy(fields = entry.fields + if (it.isBlank()) mapOf("date" to "", "time" to "", "reminder" to "false") else mapOf("date" to it)))
                    })
                    if (get("date").isNotBlank()) {
                        EditorTime("具体时刻（选填）", get("time"), "time", onValue = { update("time", it) })
                        EditorSwitch("开启本地提醒", get("reminder") == "true", "reminder") {
                            update("reminder", it.toString()); if (it) onEnableNotifications()
                        }
                        if (get("reminder") == "true") EditorHint("未设具体时刻时在当天 09:00 提醒，系统可能延后。")
                    }
                    if (entry.sourceId == null) {
                        EntryRelation("关联对象（选填）", entry.parentId.orEmpty(), "parentId", entries.filter { it.kind in listOf(Kind.ORGANIZATION, Kind.CUSTOMER, Kind.ACTIVITY) }, "不关联") { changeParent(it) }
                    } else {
                        OutlinedCard(Modifier.fillMaxWidth(), shape = AppShapes.card) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("来自沟通记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                entries.firstOrNull { it.id == entry.parentId }?.title?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                EditorHint("关联对象可在原沟通记录中调整。")
                            }
                        }
                    }
                    MoreButton(taskDetailsExpanded) { taskDetailsExpanded = !taskDetailsExpanded }
                    if (taskDetailsExpanded) {
                        EditorSelect("优先级", get("priority").ifEmpty { "普通" }, "priority", TaskPriorities, onValue = { update("priority", it) })
                        EditorSelect("状态", get("status").ifEmpty { "未完成" }, "status", TaskStatuses, onValue = { update("status", it) })
                        EditorText("完成备注", get("completionNote"), "completionNote", multiline = true, onValue = { update("completionNote", it) })
                    }
                }
                Kind.PROFILE -> {
                    EditorText("职务", get("role"), "role", onValue = { update("role", it) })
                    EditorText("公司", get("company"), "company", onValue = { update("company", it) })
                    EditorText("本人电话（选填）", get("phone"), "phone", keyboardType = KeyboardType.Phone, onValue = { update("phone", it) })
                    EditorText("本人微信备注（选填）", get("wechat"), "wechat", onValue = { update("wechat", it) })
                    EditorText("应用显示名称", get("appName"), "appName", supporting = "修改应用内工作台的名称；桌面安装名称为康养工作簿", onValue = { update("appName", it) })
                    EditorText("业务标签（每行一项）", get("tags"), "tags", multiline = true, minLines = 5, supporting = "可修改或增加业务，已保存档案的标签不会被覆盖", onValue = { update("tags", it) })
                    EditorHint("可在下方添加本人微信二维码图片。")
                }
            }
            if (entry.kind !in listOf(Kind.CONTACT, Kind.RECIPIENT)) {
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                EditorSection(if (entry.kind == Kind.PROFILE) "本人二维码与资料" else "照片与文件")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryAction("照片", onAddPhoto, Modifier.weight(1f).testTag("add_photo"), !saving)
                    SecondaryAction("文件", onAddFile, Modifier.weight(1f).testTag("add_file"), !saving)
                }
                attachments.filter { it.ownerId == entry.id }.forEach { attachment ->
                    OutlinedCard(Modifier.fillMaxWidth(), shape = AppShapes.card) {
                        Column(Modifier.padding(12.dp)) {
                            Text(attachment.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${(attachment.size + 1023) / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onOpenAttachment(attachment) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("打开") }
                                TextButton(onClick = { onRemoveAttachment(attachment) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("移除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp).testTag("form_error"), style = MaterialTheme.typography.bodyMedium) }
                PrimaryAction(
                    text = if (saving) "正在保存…" else "保存",
                    onClick = {
                        error = validateEditor(entry)
                        if (error == null) onSave()
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("save_entry"),
                )
            }
        }
    }
}

@Composable
private fun EditorSection(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun EditorHint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun MoreButton(expanded: Boolean, onClick: () -> Unit) {
    SecondaryAction(if (expanded) "收起补充信息" else "补充更多信息", onClick, Modifier.fillMaxWidth().testTag("more_fields"))
}

@Composable
private fun EditorText(
    label: String,
    value: String,
    key: String,
    multiline: Boolean = false,
    minLines: Int = 3,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    onValue: (String) -> Unit,
) {
    AppTextField(
        value = value, onValueChange = onValue,
        label = label,
        modifier = Modifier.fillMaxWidth().testTag("field_$key"),
        singleLine = !multiline,
        minLines = if (multiline) minLines else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting,
        trailingIcon = trailing,
    )
}

@Composable
private fun EditorSwitch(label: String, checked: Boolean, key: String, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onValue, Modifier.testTag("field_$key"))
    }
}

@Composable
private fun EditorSelect(label: String, value: String, key: String, choices: List<String>, onValue: (String) -> Unit) =
    EditorChoice(label, value, key, choices.map { it to it }, onValue)

@Composable
private fun EditorChoice(label: String, value: String, key: String, choices: List<Pair<String, String>>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("field_$key"), shape = AppShapes.control, contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(choices.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "请选择" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(24.dp))
    }
    if (open) AppBottomSheet(label, { open = false }) {
        choices.forEach { (optionValue, optionLabel) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("option_$optionLabel").clickable { onValue(optionValue); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == optionValue, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(optionLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EntryRelation(label: String, value: String, key: String, entries: List<Entry>, emptyText: String, onValue: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = entries.firstOrNull { it.id == value }
    SecondaryAction("$label：${current?.title ?: emptyText}", { open = true }, Modifier.fillMaxWidth().testTag("field_$key"))
    if (open) ObjectPickerPanel(entries, value.ifBlank { null }, { onValue(it.orEmpty()) }, { open = false }, title = label, allowClear = emptyText != "请选择")
}

@Composable
private fun EditorTags(value: String, available: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = value.lines().filter(String::isNotBlank)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("field_tags"), shape = AppShapes.control, contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("业务需求标签（选填）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selected.joinToString("、").ifEmpty { "选择业务需求" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(Icons.Outlined.ExpandMore, null)
    }
    if (open) AppBottomSheet("业务需求标签", { open = false }) {
        (available + selected).filter(String::isNotBlank).distinct().forEach { tag ->
            val checked = tag in selected
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("option_$tag").clickable {
                onValue((if (checked) selected - tag else selected + tag).joinToString("\n"))
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(tag, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        PrimaryAction("完成", { open = false }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun EditorDate(label: String, value: String, key: String, onValue: (String) -> Unit) =
    DateField(label, value, onValue, Modifier.testTag("field_$key"))

@Composable
private fun EditorTime(label: String, value: String, key: String, onValue: (String) -> Unit) =
    TimeField(label, value, onValue, Modifier.testTag("field_$key"))

@Composable
private fun EditorDateTime(label: String, value: String, key: String, onValue: (String) -> Unit) =
    DateTimeField(label, value, onValue, Modifier.testTag("field_$key"))

private fun validateEditor(entry: Entry): String? {
    if (entry.kind == Kind.ACTIVITY) {
        listOf("expectedCount", "actualCount").forEach {
            if (entry[it].isNotBlank() && entry[it].toIntOrNull()?.let { count -> count >= 0 } != true)
                return "${fieldLabel(it)}应为不小于零的整数"
        }
    }
    // One parser and validator is shared with repository transactions and backup validation.
    return runCatching { DataValidation.validateFields(entry) }.exceptionOrNull()?.message
}
