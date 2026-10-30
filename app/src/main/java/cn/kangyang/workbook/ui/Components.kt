package cn.kangyang.workbook.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

object AppMotion {
    const val Quick = 120
    const val Change = 180
}

/** Fade a disclosure once; don't interpolate the height of a long form on every frame. */
@Composable
fun ExpandingSection(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AppMotion.Quick)),
        exit = fadeOut(tween(AppMotion.Quick)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

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
fun AppBottomSheet(title: String, onDismiss: () -> Unit, dismissEnabled: Boolean = true,
                   scrollable: Boolean = true,
                   bottomBar: (@Composable (close: () -> Unit) -> Unit)? = null,
                   content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || canDismiss })
    val dismiss by rememberUpdatedState(onDismiss)
    var closeRequested by remember { mutableStateOf(false) }
    LaunchedEffect(closeRequested, dismissEnabled) {
        if (closeRequested && dismissEnabled) {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) dismiss()
            } finally {
                closeRequested = false
            }
        }
    }
    val scroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = { if (canDismiss) dismiss() }, sheetState = sheetState,
        shape = AppShapes.sheet, containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) }) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val close: () -> Unit = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            closeRequested = true
        }
        // ModalBottomSheet owns IME/inset handling. A lazy picker must receive bounded
        // height here instead of being nested inside a second vertical scroll container.
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconAction(Icons.Outlined.Close, "关闭面板", close, enabled = dismissEnabled)
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 20.dp).padding(bottom = if (bottomBar == null) 24.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content(close)
            }
            bottomBar?.let { actions ->
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { actions(close) }
            }
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
    contacts: List<Entry> = emptyList(), clearLabel: String = "暂不关联",
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    val candidates = remember(entries, selectedId) { entries.filter { !it.archived || it.id == selectedId } }
    val recentOrder = remember(recentIds) { recentIds.withIndex().associate { it.value to it.index } }
    val q = query.trim()
    val contactMatches = remember(contacts, q) {
        if (q.isBlank()) emptySet() else contacts.asSequence().filter { contact ->
            (contact.title + " " + contact["phone"]).contains(q, true)
        }.mapNotNull { it.parentId }.toSet()
    }
    val choices = remember(candidates, kind, q, contactMatches, recentOrder) {
        candidates.filter { entry ->
            (kind == "全部" || (kind == "单位" && entry.kind == Kind.ORGANIZATION) || (kind == "客户" && entry.kind == Kind.CUSTOMER)) &&
                (q.isBlank() || entry.id in contactMatches ||
                    (entry.title + " " + entry["area"] + " " + entry["phone"] + " " + entry["needs"]).contains(q, true))
        }.sortedWith(compareBy<Entry> { if (q.isNotBlank()) 0 else recentOrder[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt })
    }
    val kinds = remember(candidates) {
        listOf("全部") + listOfNotNull("单位".takeIf { candidates.any { it.kind == Kind.ORGANIZATION } }, "客户".takeIf { candidates.any { it.kind == Kind.CUSTOMER } })
    }
    AppBottomSheet(title, onDismiss, scrollable = false) { close ->
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("object_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "search", contentType = "search") {
                AppTextField(query, { query = it }, if (contacts.isEmpty()) "搜索名称或区域" else "搜索名称、联系人或电话", Modifier.fillMaxWidth().testTag("object_search"), singleLine = true)
            }
            item(key = "kinds", contentType = "filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { label -> AppFilterChip(label, kind == label, { kind = label }) }
                }
            }
            if (allowClear) item(key = "clear", contentType = "action") {
                SecondaryAction(clearLabel, { onSelect(null); close() }, Modifier.fillMaxWidth().testTag("object_clear"))
            }
            item(key = "count", contentType = "label") {
                Text(if (q.isBlank()) "${if (recentIds.isNotEmpty()) "最近使用" else "最近更新"} · ${choices.size} 个对象" else "找到 ${choices.size} 个对象", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            items(choices, key = { "object-${it.id}" }, contentType = { "object" }) { entry ->
            Surface(onClick = { onSelect(entry.id); close() }, shape = AppShapes.card,
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
            if (choices.isEmpty()) item(key = "empty", contentType = "label") { Text("没有找到对象，可以换个词或稍后再关联。", color = Muted) }
        }
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
    if (open) AppDatePicker(value, onValue, { open = false }, label, allowClear)
}

@Composable
fun TimeField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectionField(label, value.ifBlank { "不设具体时刻" }, Icons.Outlined.Schedule, { open = true }, modifier.semantics { contentDescription = "选中时刻 $value" })
    if (open) AppTimePicker(value, onValue, { open = false }, label, allowClear)
}

@Composable
fun DateTimeField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var stage by rememberSaveable { mutableStateOf(0) }
    var pickedDate by rememberSaveable { mutableStateOf("") }
    var continueToTime by rememberSaveable { mutableStateOf(false) }
    val parsed = TimeRules.parseDateTime(value)?.let { Instant.ofEpochMilli(it).atZone(TimeRules.zone) }
    val date = parsed?.toLocalDate()?.toString().orEmpty()
    val time = parsed?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
    SelectionField(label, if (value.isBlank()) "时间待定" else if (parsed != null) "${friendlyDate(date)} $time" else value, Icons.Outlined.CalendarMonth, { stage = 1 }, modifier.semantics { contentDescription = "选中时间 $value" })
    if (stage == 1) AppDatePicker(date, {
        if (it.isBlank()) { onValue(""); continueToTime = false }
        else { pickedDate = it; continueToTime = true }
    }, {
        stage = if (continueToTime) 2 else 0
        continueToTime = false
    }, label, allowClear)
    if (stage == 2) AppTimePicker(time, { onValue("$pickedDate $it") }, { stage = 0 }, "选择时刻", false)
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
    AppBottomSheet(title, onDismiss) { close ->
        fun choose(value: String) { onValue(value); close() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction("今天", { choose(TimeRules.today().toString()) }, Modifier.testTag("date_today"))
            SecondaryAction("明天", { choose(TimeRules.today().plusDays(1).toString()) }, Modifier.testTag("date_tomorrow"))
            SecondaryAction("昨天", { choose(TimeRules.today().minusDays(1).toString()) }, Modifier.testTag("date_yesterday"))
        }
        DatePicker(state, modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp), title = null, headline = null,
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface), showModeToggle = true)
        PrimaryAction("确定日期", { state.selectedDateMillis?.let { choose(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }, Modifier.fillMaxWidth().testTag("date_confirm"), state.selectedDateMillis != null)
        if (allowClear) TextButton(onClick = { choose("") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("date_clear")) { Text("暂不设日期") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePicker(value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, title: String = "选择时刻", allowClear: Boolean = true) {
    val time = TimeRules.parseTime(value) ?: LocalTime.of(9, 0)
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    AppBottomSheet(title, onDismiss) { close ->
        Text("北京时间 · 24 小时制", style = MaterialTheme.typography.bodySmall, color = Muted)
        Box(Modifier.fillMaxWidth().testTag("time_input"), contentAlignment = Alignment.Center) { TimeInput(state) }
        PrimaryAction("确定时刻", { onValue(LocalTime.of(state.hour, state.minute).format(DateTimeFormatter.ofPattern("HH:mm"))); close() }, Modifier.fillMaxWidth().testTag("time_confirm"))
        if (allowClear) TextButton(onClick = { onValue(""); close() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("time_clear")) { Text("不设具体时刻") }
    }
}
