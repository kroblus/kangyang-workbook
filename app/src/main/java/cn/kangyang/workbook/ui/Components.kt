package cn.kangyang.workbook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.domain.TimeRules
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = AppShapes.control,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = Ink.copy(alpha = .12f), disabledContentColor = Ink.copy(alpha = .38f))) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = AppShapes.control,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = Ink.copy(alpha = .38f))) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    singleLine: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = 56.dp), enabled = enabled,
        label = label?.let { { Text(it) } }, singleLine = singleLine, minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge, shape = AppShapes.control,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions, isError = isError, trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            errorContainerColor = MaterialTheme.colorScheme.surface,
        ))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppShapes.sheet, containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconAction(Icons.Outlined.Close, "关闭面板", onDismiss)
            }
            content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
                  confirmLabel: String = "确认", dismissLabel: String = "返回", destructive: Boolean = false) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Text(message) }, containerColor = MaterialTheme.colorScheme.surface, shape = AppShapes.dialog,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } })
}

@Composable
fun AppFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(selected, onClick, label = { Text(label) }, modifier = modifier.heightIn(min = 48.dp), shape = AppShapes.badge,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Brand))
}

/** Search works on every candidate; recent ordering is a convenience, never a separate identity. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObjectPickerPanel(
    entries: List<Entry>, selectedId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit,
    title: String = "关联对象", allowClear: Boolean = true, recentIds: List<String> = emptyList(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    val candidates = entries.filter { !it.archived || it.id == selectedId }
    val choices = candidates.filter { entry ->
        (kind == "全部" || (kind == "单位" && entry.kind == Kind.ORGANIZATION) || (kind == "客户" && entry.kind == Kind.CUSTOMER)) &&
            (query.isBlank() || (entry.title + " " + entry["area"] + " " + entry["phone"] + " " + entry["needs"]).contains(query.trim(), ignoreCase = true))
    }.sortedWith(compareBy<Entry> { if (query.isNotBlank()) 0 else recentIds.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt })
    AppBottomSheet(title, onDismiss) {
        AppTextField(query, { query = it }, "搜索名称、区域或电话", Modifier.fillMaxWidth().testTag("object_search"), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val kinds = listOf("全部") + listOfNotNull("单位".takeIf { candidates.any { it.kind == Kind.ORGANIZATION } }, "客户".takeIf { candidates.any { it.kind == Kind.CUSTOMER } })
            kinds.forEach { label -> AppFilterChip(label, kind == label, { kind = label }) }
        }
        if (allowClear) SecondaryAction("暂不关联", { onSelect(null); onDismiss() }, Modifier.fillMaxWidth().testTag("object_clear"))
        Text(if (query.isBlank()) "${if (recentIds.isNotEmpty()) "最近使用" else "最近更新"} · ${choices.size} 个对象" else "找到 ${choices.size} 个对象", style = MaterialTheme.typography.bodySmall, color = Muted)
        choices.forEach { entry ->
            Surface(onClick = { onSelect(entry.id); onDismiss() }, shape = AppShapes.card,
                color = if (entry.id == selectedId) Mint else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("object_${entry.id}")) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(listOf(when (entry.kind) { Kind.ORGANIZATION -> entry["type"].ifBlank { "单位" }; Kind.CUSTOMER -> "客户"; Kind.ACTIVITY -> "活动"; else -> "档案" }, entry["area"], if (entry.archived) "已归档" else "").filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    if (entry.id == selectedId) Icon(Icons.Outlined.Check, "当前关联", tint = Green)
                }
            }
        }
        if (choices.isEmpty()) Text("没有找到对象，可以换个词或稍后再关联。", color = Muted)
    }
}

fun friendlyDate(value: String): String = TimeRules.parseDate(value)?.let { date ->
    when (date) { TimeRules.today() -> "今天"; TimeRules.today().plusDays(1) -> "明天"; TimeRules.today().minusDays(1) -> "昨天"; else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }
} ?: value.ifBlank { "暂不设日期" }

@Composable
private fun SelectionField(label: String, text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 64.dp), shape = AppShapes.control, contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Ink)
        }
        Spacer(Modifier.width(12.dp)); Icon(icon, null, Modifier.size(24.dp))
    }
}

@Composable
fun DateField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectionField(label, friendlyDate(value), Icons.Outlined.CalendarMonth, { open = true }, modifier.semantics { contentDescription = "选中日期 $value" })
    if (open) AppDatePicker(value, { onValue(it); open = false }, { open = false }, label, allowClear)
}

@Composable
fun TimeField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectionField(label, value.ifBlank { "不设具体时刻" }, Icons.Outlined.Schedule, { open = true }, modifier.semantics { contentDescription = "选中时刻 $value" })
    if (open) AppTimePicker(value, { onValue(it); open = false }, { open = false }, label, allowClear)
}

@Composable
fun DateTimeField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var stage by rememberSaveable { mutableStateOf(0) }
    var pickedDate by rememberSaveable { mutableStateOf("") }
    val parsed = TimeRules.parseDateTime(value)?.let { Instant.ofEpochMilli(it).atZone(TimeRules.zone) }
    val date = parsed?.toLocalDate()?.toString().orEmpty()
    val time = parsed?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
    SelectionField(label, if (value.isBlank()) "时间待定" else if (parsed != null) "${friendlyDate(date)} $time" else value, Icons.Outlined.CalendarMonth, { stage = 1 }, modifier.semantics { contentDescription = "选中时间 $value" })
    if (stage == 1) AppDatePicker(date, { if (it.isBlank()) { onValue(""); stage = 0 } else { pickedDate = it; stage = 2 } }, { stage = 0 }, label, allowClear)
    if (stage == 2) AppTimePicker(time, { onValue("$pickedDate $it"); stage = 0 }, { stage = 0 }, "选择时刻", false)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDatePicker(value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, title: String = "选择日期", allowClear: Boolean = true) {
    val date = TimeRules.parseDate(value) ?: TimeRules.today()
    // Material's input mode also fits large text and narrow phones; it keeps localized date guidance.
    val configuration = LocalConfiguration.current
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = if (configuration.screenWidthDp < 360 || configuration.fontScale > 1.2f) DisplayMode.Input else DisplayMode.Picker,
    )
    AppBottomSheet(title, onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction("今天", { onValue(TimeRules.today().toString()) }, Modifier.testTag("date_today"))
            SecondaryAction("明天", { onValue(TimeRules.today().plusDays(1).toString()) }, Modifier.testTag("date_tomorrow"))
            SecondaryAction("昨天", { onValue(TimeRules.today().minusDays(1).toString()) }, Modifier.testTag("date_yesterday"))
        }
        DatePicker(state, modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp), title = null, headline = null,
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface), showModeToggle = true)
        PrimaryAction("确定日期", { state.selectedDateMillis?.let { onValue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }, Modifier.fillMaxWidth().testTag("date_confirm"), state.selectedDateMillis != null)
        if (allowClear) TextButton(onClick = { onValue("") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("date_clear")) { Text("暂不设日期") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePicker(value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, title: String = "选择时刻", allowClear: Boolean = true) {
    val time = TimeRules.parseTime(value) ?: LocalTime.of(9, 0)
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    AppBottomSheet(title, onDismiss) {
        Text("北京时间 · 24 小时制", style = MaterialTheme.typography.bodySmall, color = Muted)
        Box(Modifier.fillMaxWidth().testTag("time_input"), contentAlignment = Alignment.Center) { TimeInput(state) }
        PrimaryAction("确定时刻", { onValue(LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern("HH:mm"))) }, Modifier.fillMaxWidth().testTag("time_confirm"))
        if (allowClear) TextButton(onClick = { onValue("") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("time_clear")) { Text("不设具体时刻") }
    }
}
