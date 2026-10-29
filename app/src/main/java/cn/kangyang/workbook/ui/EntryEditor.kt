package cn.kangyang.workbook.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val editorZone: ZoneId = ZoneId.of("Asia/Shanghai")
private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private val shortTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
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
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Text(editorHeading(entry.kind), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Text(
                    draftStatus.ifEmpty { "填写后自动保存草稿" },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp).testTag("draft_status").clickable(enabled = draftStatus.contains("重试")) { onRetryDraft() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draftStatus.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("editor_scroll").padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    value = entry.title, key = "title", multiline = false,
                    onValue = { error = null; onChange(entry.copy(title = it)) },
                )
            }
            when (entry.kind) {
                Kind.ORGANIZATION -> {
                    EditorSelect("单位类型 *", get("type"), "type", OrganizationTypes, onValue = { update("type", it) })
                    EditorSelect("主体性质（选填）", get("nature").ifEmpty { "暂不清楚" }, "nature", OrganizationNatures, onValue = { update("nature", it) })
                    EditorHint("主体性质由你确认；不会根据单位名称或医院类型自动判断。")
                    EditorText("区域", get("area"), "area", onValue = { update("area", it) })
                    EditorText("地址", get("address"), "address", multiline = true, onValue = { update("address", it) })
                    EditorTags(get("tags"), tags, onValue = { update("tags", it) })
                    EditorSelect("合作阶段", get("stage").ifEmpty { "待联系" }, "stage", OrganizationStages, onValue = { update("stage", it) })
                    EditorText("备注", get("notes"), "notes", multiline = true, onValue = { update("notes", it) })
                    EditorHint("保存后可添加多名联系人、记拜访或安排跟进。同名单位可以保存，请用区域和联系人区分。")
                }
                Kind.CUSTOMER -> {
                    EditorTags(get("tags"), tags, onValue = { update("tags", it) })
                    EditorText("需求说明", get("needs"), "needs", multiline = true, onValue = { update("needs", it) })
                    EditorSelect("进展阶段", get("stage").ifEmpty { "新咨询" }, "stage", CustomerStages, onValue = { update("stage", it) })
                    EditorSelect("紧急程度", get("urgency").ifEmpty { "普通" }, "urgency", listOf("普通", "近期", "紧急"), onValue = { update("urgency", it) })
                    EditorHint("保存后分别添加联系人和服务对象。器具、耗材或培训咨询不需要填写老人入住资料。")
                    MoreButton(expanded) { expanded = !expanded }
                    if (expanded) {
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
                Kind.INTERACTION -> {
                    EntryRelation("对象", entry.parentId.orEmpty(), "parentId", entries.filter { it.kind == Kind.ORGANIZATION || it.kind == Kind.CUSTOMER }, "临时未关联") { changeParent(it) }
                    if (entry.parentId == null) EditorHint("可以先速记，之后在这条记录中补充关联对象。")
                    val instant = Instant.ofEpochMilli(entry.occurredAt).atZone(editorZone)
                    val occurredDate = get("occurredDate").ifEmpty { instant.toLocalDate().toString() }
                    val occurredTime = get("occurredTime").ifEmpty { instant.format(shortTimeFormat) }
                    fun updateOccurrence(date: String, time: String) {
                        error = null
                        val parsed = parseDateTime("$date $time")
                        onChange(entry.copy(occurredAt = parsed ?: entry.occurredAt, fields = entry.fields + mapOf("occurredDate" to date, "occurredTime" to time)))
                    }
                    EditorDate("发生日期", occurredDate, "occurredDate", onValue = { updateOccurrence(it, occurredTime) })
                    EditorTime("发生时刻（24小时）", occurredTime, "occurredTime", onValue = { updateOccurrence(occurredDate, it) })
                    EditorText("重要内容 *", get("content"), "content", multiline = true, minLines = 5, onValue = { update("content", it) })
                    EditorText("下一步（选填）", get("nextStep"), "nextStep", multiline = true, supporting = "填写后生成一条关联待办，可暂不设置日期", onValue = { update("nextStep", it) })
                    if (get("nextStep").isNotBlank()) {
                        EditorDate("跟进日期（可不设）", get("nextDate"), "nextDate", onValue = { update("nextDate", it) })
                        EditorTime("跟进时刻（可不设）", get("nextTime"), "nextTime", onValue = { update("nextTime", it) })
                        EditorSwitch("开启跟进提醒", get("nextReminder") == "true", "nextReminder") {
                            update("nextReminder", it.toString()); if (it) onEnableNotifications()
                        }
                        if (get("nextReminder") == "true") EditorHint("只填日期时默认当天 09:00 提醒。系统可能延后提醒，不保证分钟级准点。")
                    }
                    MoreButton(expanded) { expanded = !expanded }
                    if (expanded) {
                        EditorText("记录标题（选填）", entry.title, "title", onValue = { onChange(entry.copy(title = it)) })
                        EditorSelect("沟通方式", get("mode").ifEmpty { "面谈" }, "mode", listOf("面谈", "电话", "微信", "视频", "其他"), onValue = { update("mode", it) })
                        EditorText("参与人", get("participants"), "participants", onValue = { update("participants", it) })
                        EditorText("目的", get("purpose"), "purpose", multiline = true, onValue = { update("purpose", it) })
                        EditorText("对方需求 / 关注点", get("needs"), "needs", multiline = true, onValue = { update("needs", it) })
                        EditorText("拟议价格与合作条件", get("terms"), "terms", multiline = true, onValue = { update("terms", it) })
                        EditorText("双方承诺", get("promises"), "promises", multiline = true, onValue = { update("promises", it) })
                        EditorSelect("事项确认程度", get("certainty").ifEmpty { "讨论过" }, "certainty", listOf("讨论过", "待确认", "已明确"), onValue = { update("certainty", it) })
                        EditorText("障碍与待解决问题", get("obstacles"), "obstacles", multiline = true, onValue = { update("obstacles", it) })
                        EditorText("结论", get("conclusion"), "conclusion", multiline = true, onValue = { update("conclusion", it) })
                        EditorSelect("跟进优先级", get("nextPriority").ifEmpty { "普通" }, "nextPriority", TaskPriorities, onValue = { update("nextPriority", it) })
                        EditorHint("谈判记录不等同于合同；保存记录不会自动改变合作阶段。已有跟进事项可在待办中独立管理。")
                    }
                }
                Kind.ACTIVITY -> {
                    EditorSelect("活动类型", get("type").ifEmpty { "线下交流" }, "type", listOf("线下交流", "健康宣教", "机构参访", "器具体验", "照护培训", "其他"), onValue = { update("type", it) })
                    EditorDateTime("开始时间 *", get("start"), "start", onValue = { update("start", it) })
                    EditorDateTime("结束时间 *", get("end"), "end", onValue = { update("end", it) })
                    EditorHint("时间未定可返回保留草稿；正式保存需要开始和结束时间。业务时区为北京时间。")
                    EditorText("地点", get("location"), "location", onValue = { update("location", it) })
                    EntryRelation("关联单位", get("organizationId"), "organizationId", entries.filter { it.kind == Kind.ORGANIZATION }, "不关联") { update("organizationId", it) }
                    EditorText("方案与议程", get("plan"), "plan", multiline = true, minLines = 4, onValue = { update("plan", it) })
                    EditorText("物料清单", get("materials"), "materials", multiline = true, supporting = "可每行填写一项，筹备待办可在保存后逐项添加", onValue = { update("materials", it) })
                    EditorSelect("活动状态", get("status").ifEmpty { "筹备中" }, "status", ActivityStatuses, onValue = { update("status", it) })
                    EditorChoice("活动提醒", get("remindMinutes"), "remindMinutes", listOf(
                        "" to "关闭", "10" to "提前10分钟", "30" to "提前30分钟", "60" to "提前1小时", "1440" to "提前1天"
                    )) { update("remindMinutes", it); if (it.isNotEmpty()) onEnableNotifications() }
                    if (get("remindMinutes").isNotEmpty()) EditorHint("系统采用非精确提醒，可能延后。活动改期会更新提醒，请同时检查独立设置日期的筹备事项。")
                    if (get("status") == "已取消") EditorHint("取消会撤销活动提醒并保留历史。保存时会让你选择保留还是取消未完成的筹备待办。")
                    MoreButton(expanded) { expanded = !expanded }
                    if (expanded) {
                        EditorText("活动目标", get("goal"), "goal", multiline = true, onValue = { update("goal", it) })
                        EditorText("面向人群", get("audience"), "audience", onValue = { update("audience", it) })
                        EditorText("预计人数", get("expectedCount"), "expectedCount", keyboardType = KeyboardType.Number, onValue = { update("expectedCount", it) })
                        EditorText("人员分工", get("division"), "division", multiline = true, onValue = { update("division", it) })
                        EditorText("注意事项", get("precautions"), "precautions", multiline = true, onValue = { update("precautions", it) })
                        EditorSection("执行与复盘")
                        EditorText("实际人数", get("actualCount"), "actualCount", keyboardType = KeyboardType.Number, onValue = { update("actualCount", it) })
                        EditorText("执行情况", get("execution"), "execution", multiline = true, onValue = { update("execution", it) })
                        EditorText("活动复盘", get("review"), "review", multiline = true, onValue = { update("review", it) })
                        EditorText("后续跟进", get("followup"), "followup", multiline = true, onValue = { update("followup", it) })
                    }
                }
                Kind.TASK -> {
                    if (entry.sourceId == null) {
                        EntryRelation("关联对象（选填）", entry.parentId.orEmpty(), "parentId", entries.filter { it.kind in listOf(Kind.ORGANIZATION, Kind.CUSTOMER, Kind.ACTIVITY) }, "不关联") { changeParent(it) }
                    } else {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("来自沟通记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(entries.firstOrNull { it.id == entry.parentId }?.title ?: "临时未关联", style = MaterialTheme.typography.bodyLarge)
                                EditorHint("关联对象随来源记录保持一致。如需调整对象，请编辑原沟通记录。")
                            }
                        }
                    }
                    EditorDate("截止日期（可不设）", get("date"), "date", onValue = { update("date", it) })
                    EditorTime("截止时刻（可不设）", get("time"), "time", onValue = { update("time", it) })
                    EditorHint("未设日期的事项单独展示。仅日期事项在当天结束前不会算逾期。")
                    EditorSelect("优先级", get("priority").ifEmpty { "普通" }, "priority", TaskPriorities, onValue = { update("priority", it) })
                    EditorSwitch("开启本地提醒", get("reminder") == "true", "reminder") {
                        update("reminder", it.toString()); if (it) onEnableNotifications()
                    }
                    if (get("reminder") == "true") EditorHint("只填日期时默认当天 09:00 提醒。通知需要系统允许，系统可能延后；关闭通知后仍可在工作台查看。")
                    EditorSelect("状态", get("status").ifEmpty { "未完成" }, "status", TaskStatuses, onValue = { update("status", it) })
                    EditorText("完成备注", get("completionNote"), "completionNote", multiline = true, onValue = { update("completionNote", it) })
                }
                Kind.PROFILE -> {
                    EditorText("职务", get("role"), "role", onValue = { update("role", it) })
                    EditorText("公司", get("company"), "company", onValue = { update("company", it) })
                    EditorText("本人电话（选填）", get("phone"), "phone", keyboardType = KeyboardType.Phone, onValue = { update("phone", it) })
                    EditorText("本人微信备注（选填）", get("wechat"), "wechat", onValue = { update("wechat", it) })
                    EditorText("应用显示名称", get("appName"), "appName", supporting = "修改应用内工作台的名称；桌面安装名称为康养工作簿", onValue = { update("appName", it) })
                    EditorText("业务标签（每行一项）", get("tags"), "tags", multiline = true, minLines = 5, supporting = "可修改或增加业务，已保存档案的标签不会被覆盖", onValue = { update("tags", it) })
                    EditorHint("可在下方添加本人微信二维码图片。电话和微信不会用于测试联系人。")
                }
            }
            if (entry.kind !in listOf(Kind.CONTACT, Kind.RECIPIENT)) {
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                EditorSection(if (entry.kind == Kind.PROFILE) "本人二维码与资料" else "照片与文件")
                EditorHint("照片和常见文档会复制到本机。单个文件不超过20 MB，支持图片、PDF、文本、Word、Excel和PPT。")
                OutlinedButton(onClick = onAddPhoto, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_photo")) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("添加照片")
                }
                OutlinedButton(onClick = onAddFile, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_file")) {
                    Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("添加文件")
                }
                attachments.filter { it.ownerId == entry.id }.forEach { attachment ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
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
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp).testTag("form_error"), style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = {
                        error = validateEditor(entry)
                        if (error == null) onSave()
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("save_entry"),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (saving) "正在保存…" else "保存", fontSize = 16.sp) }
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
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("more_fields")) {
        Text(if (expanded) "收起补充信息" else "补充更多信息")
        Spacer(Modifier.width(8.dp))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
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
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag("field_$key"),
        singleLine = !multiline,
        minLines = if (multiline) minLines else 1,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting?.let { { Text(it) } },
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
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("field_$key"), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(choices.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "请选择" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(24.dp))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(label) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { (optionValue, optionLabel) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("option_$optionLabel").clickable { onValue(optionValue); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == optionValue, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(optionLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
}

@Composable
private fun EntryRelation(label: String, value: String, key: String, entries: List<Entry>, emptyText: String, onValue: (String) -> Unit) {
    val options = listOf("" to emptyText) + entries.filter { !it.archived || it.id == value }.map {
        it.id to buildString {
            append(it.title)
            it.fields["area"]?.takeIf(String::isNotBlank)?.let { area -> append(" · ").append(area) }
            if (it.archived) append("（已归档）")
        }
    }
    EditorChoice(label, value, key, options, onValue)
}

@Composable
private fun EditorTags(value: String, available: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = value.lines().filter(String::isNotBlank)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("field_tags"), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("业务需求标签（选填）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selected.joinToString("、").ifEmpty { "选择业务需求" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(Icons.Outlined.ExpandMore, null)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text("业务需求标签") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
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
                EditorHint("业务标签可在本人资料中增加或修改。")
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } },
    )
}

@Composable
private fun EditorDate(label: String, value: String, key: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    EditorText(label, value, key, supporting = "格式：2026-10-29；可清空", keyboardType = KeyboardType.Ascii, trailing = {
        IconButton(onClick = {
            val initial = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now(editorZone) }
            DatePickerDialog(context, { _, year, month, day -> onValue(LocalDate.of(year, month + 1, day).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }) { Icon(Icons.Outlined.CalendarMonth, "选择日期") }
    }, onValue = onValue)
}

@Composable
private fun EditorTime(label: String, value: String, key: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    EditorText(label, value, key, supporting = "格式：09:00；24小时制，可清空", keyboardType = KeyboardType.Ascii, trailing = {
        IconButton(onClick = {
            val initial = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.of(9, 0) }
            TimePickerDialog(context, { _, hour, minute -> onValue(LocalTime.of(hour, minute).format(shortTimeFormat)) }, initial.hour, initial.minute, true).show()
        }) { Icon(Icons.Outlined.Schedule, "选择时刻") }
    }, onValue = onValue)
}

@Composable
private fun EditorDateTime(label: String, value: String, key: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    EditorText(label, value, key, supporting = "格式：2026-10-29 09:00（北京时间）", keyboardType = KeyboardType.Ascii, trailing = {
        IconButton(onClick = {
            val initial = runCatching { LocalDateTime.parse(value, dateTimeFormat) }.getOrElse { LocalDateTime.now(editorZone).withSecond(0).withNano(0) }
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    onValue(LocalDateTime.of(year, month + 1, day, hour, minute).format(dateTimeFormat))
                }, initial.hour, initial.minute, true).show()
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }) { Icon(Icons.Outlined.CalendarMonth, "选择日期和时刻") }
    }, onValue = onValue)
}

private fun parseDateTime(text: String): Long? = runCatching {
    LocalDateTime.parse(text, dateTimeFormat).atZone(editorZone).toInstant().toEpochMilli()
}.getOrNull()

private fun validateEditor(entry: Entry): String? {
    fun get(key: String) = entry.fields[key].orEmpty()
    fun dateValid(key: String): Boolean = get(key).isBlank() || runCatching { LocalDate.parse(get(key)) }.isSuccess
    fun timeValid(key: String): Boolean = get(key).isBlank() || runCatching { LocalTime.parse(get(key)) }.isSuccess
    if (entry.kind != Kind.INTERACTION && entry.title.isBlank()) return "请填写${if (entry.kind == Kind.TASK) "要做的事项" else "名称或称呼"}"
    if (entry.kind == Kind.ORGANIZATION && get("type").isBlank()) return "请选择单位类型"
    if (entry.kind in listOf(Kind.CONTACT, Kind.RECIPIENT) && entry.parentId == null) return "请选择所属档案"
    if (entry.kind == Kind.CUSTOMER && !dateValid("intentDate")) return "意向日期格式应为 2026-10-29"
    if (entry.kind == Kind.INTERACTION) {
        if (get("content").isBlank()) return "请填写重要内容"
        if (!dateValid("occurredDate") || !timeValid("occurredTime")) return "请检查发生日期和时刻"
        if (get("nextStep").isNotBlank()) {
            if (!dateValid("nextDate") || !timeValid("nextTime")) return "请检查跟进日期和时刻"
            if (get("nextTime").isNotBlank() && get("nextDate").isBlank()) return "填写跟进时刻时，请同时填写日期"
            if (get("nextReminder") == "true" && get("nextDate").isBlank()) return "开启跟进提醒前，请填写跟进日期"
        }
    }
    if (entry.kind == Kind.ACTIVITY) {
        val start = parseDateTime(get("start")) ?: return "请填写有效的活动开始时间；时间未定可返回保留草稿"
        val end = parseDateTime(get("end")) ?: return "请填写有效的活动结束时间"
        if (end < start) return "结束时间不能早于开始时间"
        listOf("expectedCount", "actualCount").forEach { if (get(it).isNotBlank() && (get(it).toIntOrNull()?.let { count -> count >= 0 } != true)) return "${fieldLabel(it)}应为不小于零的整数" }
    }
    if (entry.kind == Kind.TASK) {
        if (!dateValid("date") || !timeValid("time")) return "请检查截止日期和时刻"
        if (get("time").isNotBlank() && get("date").isBlank()) return "填写截止时刻时，请同时填写日期"
        if (get("reminder") == "true" && get("date").isBlank()) return "开启提醒前，请先填写截止日期"
    }
    return null
}
