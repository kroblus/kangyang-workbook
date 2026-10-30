@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package cn.kangyang.workbook.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kangyang.workbook.*
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

private val occurrenceDateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val occurrenceTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

private val FollowUpEntrySaver = Saver<Entry, String>(
    save = { dataJson.encodeToString(Entry.serializer(), it) },
    restore = { dataJson.decodeFromString(Entry.serializer(), it) },
)

fun noteTitle(e:Entry,s:Snapshot):String = e.title.takeIf{it.isNotBlank()}
    ?: e["content"].lineSequence().firstOrNull{it.isNotBlank()}?.trim()?.take(60)
    ?: s.attachmentsFor(e.id).firstOrNull()?.name ?: "空白记录"
fun occurrenceLabel(e:Entry,now:Long=TimeRules.now()):String {
    val d=Instant.ofEpochMilli(e.occurredAt).atZone(TimeRules.zone)
    val date=when(d.toLocalDate()) {TimeRules.today(now)->"今天";TimeRules.today(now).minusDays(1)->"昨天";else->d.format(occurrenceDateFormat)}
    return "$date ${d.format(occurrenceTimeFormat)}"
}

private val noteContextKeys = listOf("participants", "mode", "purpose")
private val notePointKeys = listOf("needs", "conclusion", "promises", "terms", "obstacles", "certainty")
private fun noteFieldTitle(key:String) = when(key) {
    "title"->"给这段记录起个标题"; "tags"->"加几个标签"
    "participants"->"一起聊的人"; "mode"->"沟通方式"; "purpose"->"这次想聊的事"
    "needs"->"对方的需要"; "conclusion"->"这次的结论"; "promises"->"彼此的约定"
    "terms"->"谈到的条件"; "obstacles"->"还要确认的事"; "certainty"->"确认情况"
    else->fieldLabel(key)
}
private fun noteFieldIcon(key:String) = when(key) {
    "title"->Icons.Outlined.Title; "tags"->Icons.Outlined.Label
    "participants"->Icons.Outlined.People; "mode"->Icons.Outlined.Forum
    "purpose"->Icons.Outlined.ChatBubbleOutline; "needs"->Icons.Outlined.FavoriteBorder
    "promises"->Icons.Outlined.Handshake; "conclusion"->Icons.Outlined.Notes
    else->Icons.Outlined.BookmarkBorder
}
private fun shortNoteValue(value:String) = value.lineSequence().firstOrNull{it.isNotBlank()}?.trim()?.take(48).orEmpty()

@Composable fun NoteEditorScreen(vm:WorkbookViewModel,session:EditorSession,s:Snapshot,onPhoto:()->Unit,onFile:()->Unit,onAttachment:(Attachment)->Unit,onRemove:(Attachment)->Unit,onNotifications:()->Unit) {
    val e=session.entry
    val focus=remember(session.draftId){FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    val focusManager=LocalFocusManager.current
    val density=LocalDensity.current
    val ime=WindowInsets.ime
    val body=requireNotNull(session.noteBody)
    LaunchedEffect(body) { snapshotFlow {body.text.toString()}.collect {vm.syncNoteBody()} }
    var panel by rememberSaveable(session.draftId){mutableStateOf("")}
    var pendingPanel by rememberSaveable(session.draftId){mutableStateOf("")}
    var afterPanel by rememberSaveable(session.draftId){mutableStateOf("")}
    var selectedTaskId by rememberSaveable(session.draftId){mutableStateOf<String?>(null)}
    var focusedOnce by rememberSaveable(session.draftId){mutableStateOf(false)}
    LaunchedEffect(session.draftId) { if(!focusedOnce) {focus.requestFocus();keyboard?.show();focusedOnce=true} }
    val linked=s.find(e.parentId)
    val tasks=remember(s.entries,e.id) { s.sourcedFrom(e.id).filter{it.kind==Kind.TASK} }
    val files=s.attachmentsFor(e.id)
    fun finishEditing(action:()->Unit){focusManager.clearFocus(force=true);keyboard?.hide();action()}
    fun show(value:String){focusManager.clearFocus(force=true);keyboard?.hide();pendingPanel=value}
    fun finishPanel(){panel=afterPanel;afterPanel=""}
    LaunchedEffect(pendingPanel) {
        val requested=pendingPanel
        if(requested.isNotBlank()) {
            // Wait for the old keyboard's actual inset, not an arbitrary delay. A new
            // request or leaving this editor cancels this effect automatically.
            snapshotFlow { ime.getBottom(density) }.first { it==0 }
            panel=requested
            pendingPanel=""
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().background(MaterialTheme.colorScheme.surface)) {
        val compact=maxHeight<260.dp
        Column(Modifier.fillMaxSize()) {
        if(compact) Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={finishEditing(vm::back)},enabled=!session.saving,modifier=Modifier.testTag("note_back")){Icon(Icons.Outlined.ArrowBack,"返回")}
            Text(session.status,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                color=if(session.status.contains("未保存")) MaterialTheme.colorScheme.error else Muted,
                modifier=Modifier.weight(1f).testTag("note_save_status").clickable(enabled=session.status.contains("重试")){vm.flushDraft()})
            IconButton(onClick={show("attachment")},modifier=Modifier.testTag("note_attach")){Icon(Icons.Outlined.AttachFile,"附件")}
            IconButton(onClick={show("link")},modifier=Modifier.testTag("note_link")){Icon(Icons.Outlined.Link,"关联")}
            IconButton(onClick={selectedTaskId=tasks.firstOrNull()?.id;show("followup")},modifier=Modifier.testTag("note_followup")){Icon(Icons.Outlined.AddTask,if(tasks.isEmpty())"加跟进" else "跟进")}
            IconButton(onClick={show("more")},modifier=Modifier.testTag("note_more")){Icon(Icons.Outlined.MoreHoriz,"更多")}
            TextButton(onClick={finishEditing(vm::completeNote)},enabled=!session.saving,modifier=Modifier.heightIn(min=52.dp).testTag("note_done")){Text("完成",fontSize=16.sp)}
        } else {
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={finishEditing(vm::back)},enabled=!session.saving,modifier=Modifier.testTag("note_back")){Icon(Icons.Outlined.ArrowBack,"返回")}
            Text("记一笔",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            TextButton(onClick={finishEditing(vm::completeNote)},enabled=!session.saving,modifier=Modifier.heightIn(min=52.dp).testTag("note_done")){Text("完成",fontSize=16.sp)}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(session.status,style=MaterialTheme.typography.bodySmall,
                color=if(session.status.contains("未保存")) MaterialTheme.colorScheme.error else Muted,
                modifier=Modifier.weight(1f).testTag("note_save_status").clickable(enabled=session.status.contains("重试")){vm.flushDraft()})
        }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).testTag("note_scroll")) {
            if(e.title.isNotBlank()) Text(e.title,style=MaterialTheme.typography.headlineMedium,
                modifier=Modifier.fillMaxWidth().padding(top=16.dp,bottom=4.dp).clickable{show("field:title")})
            BasicTextField(state=body,
                enabled=!session.saving,textStyle=MaterialTheme.typography.bodyLarge.copy(color=Ink,fontSize=17.sp,lineHeight=28.sp),
                cursorBrush=SolidColor(Green),modifier=Modifier.fillMaxWidth().heightIn(min=if(compact)40.dp else 170.dp).padding(top=if(compact)0.dp else 16.dp,bottom=if(compact)0.dp else 20.dp).focusRequester(focus).testTag("note_body"),
                decorator={inner->Box {if(body.text.isEmpty()) Text("这次聊到了什么？\n先记下重要的几句话…",color=Muted,style=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=28.sp));inner()}})
            files.forEach {AttachmentRow(it,onAttachment,onRemove)}
            session.attachmentStatus?.let {Text(it,color=if(it.contains("未导入")) MaterialTheme.colorScheme.error else Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("note_attachment_status"))}
            MetadataShelf {
                MetadataChip("时间",occurrenceLabel(e,vm.clockNow),Icons.Outlined.Schedule,{show("date")},Modifier.testTag("note_date"))
                if(linked!=null) MetadataChip("对象",linked.title,kindIcon(linked.kind),{show("link")})
                if(e["tags"].isNotBlank()) MetadataChip("标签",e["tags"].replace('\n','、'),Icons.Outlined.Label,{show("field:tags")})
                listOf("participants","mode").filter{e[it].isNotBlank()}.forEach { key->
                    MetadataChip(noteFieldTitle(key),shortNoteValue(e[key]),noteFieldIcon(key),{show("field:$key")})
                }
            }
            (listOf("purpose")+notePointKeys).filter{e[it].isNotBlank()}.forEach { key->
                Column(Modifier.fillMaxWidth().clickable{show("field:$key")}.padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(noteFieldTitle(key),style=MaterialTheme.typography.labelMedium,color=Muted)
                    Text(e[key],style=MaterialTheme.typography.bodyLarge)
                }
            }
            tasks.forEach{task->TextButton(onClick={selectedTaskId=task.id;show("followup")}){Icon(Icons.Outlined.CheckCircleOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("${task.title} · ${task["date"].ifBlank{"未设日期"}}")}}
            Spacer(Modifier.height(8.dp))
        }
        if(!compact) {
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={show("attachment")},modifier=Modifier.testTag("note_attach")){Icon(Icons.Outlined.AttachFile,null,Modifier.size(20.dp));Text("附件")}
            TextButton(onClick={show("link")},modifier=Modifier.testTag("note_link")){Icon(Icons.Outlined.Link,null,Modifier.size(20.dp));Text("关联")}
            TextButton(onClick={selectedTaskId=tasks.firstOrNull()?.id;show("followup")},modifier=Modifier.testTag("note_followup")){Icon(Icons.Outlined.AddTask,null,Modifier.size(20.dp));Text(if(tasks.isEmpty())"加跟进" else "跟进")}
            TextButton(onClick={show("more")},modifier=Modifier.testTag("note_more")){Icon(Icons.Outlined.MoreHoriz,null,Modifier.size(20.dp));Text("补充")}
        }
        }
        }
    }
    when(panel) {
        "attachment"->AppBottomSheet("添加附件",{panel=""}) { close->
            Text("照片或文件会保存在本机，每个最多 20 MB。",color=Muted)
            PrimaryAction("选择照片",{close();onPhoto()},Modifier.fillMaxWidth().testTag("note_photo"))
            SecondaryAction("选择文件",{close();onFile()},Modifier.fillMaxWidth().testTag("note_file"))
        }
        "link"->{
            val objects=remember(s.entries) { s.entriesOf(Kind.ORGANIZATION)+s.entriesOf(Kind.CUSTOMER) }
            val recent=remember(s.entries) { s.entriesOf(Kind.INTERACTION).sortedByDescending{it.updatedAt}.mapNotNull{it.parentId}.distinct() }
            ObjectPickerPanel(objects,e.parentId,{id->vm.change(e.copy(parentId=id))},{panel=""},recentIds=recent,contacts=s.entriesOf(Kind.CONTACT))
        }
        "date"->OccurrencePanel(e,vm::change,{panel=""})
        "followup"->FollowUpPanel(vm,s,tasks.find{it.id==selectedTaskId} ?: tasks.firstOrNull(),e,{panel=""},onNotifications)
        "more"->AppBottomSheet("补充信息",::finishPanel) {close->
            fun next(value:String){afterPanel=value;close()}
            Text("想到哪一项，就补哪一项。",color=Muted,style=MaterialTheme.typography.bodyMedium)
            DetailAction(if(e.title.isBlank())"加个标题" else "标题",e.title.ifBlank{"也可以直接用正文开头"},Icons.Outlined.Title,{next("field:title")},Modifier.testTag("note_edit_title"))
            DetailAction("标签",e["tags"].replace('\n','、').ifBlank{"方便以后找回这段记录"},Icons.Outlined.Label,{next("field:tags")},Modifier.testTag("note_edit_tags"))
            DetailAction("这次沟通",noteContextKeys.map{shortNoteValue(e[it])}.filter{it.isNotBlank()}.joinToString(" · ").ifBlank{"一起聊的人、沟通方式"},Icons.Outlined.Forum,{next("context")},Modifier.testTag("note_context"))
            DetailAction("单独记下的要点",notePointKeys.count{e[it].isNotBlank()}.let{if(it==0)"需要、约定或还要确认的事" else "已记下 $it 项"},Icons.Outlined.Notes,{next("points")},Modifier.testTag("note_points"))
            if(e["nextStep"].isNotBlank()) {
                Text("旧记录的下一步建议",style=MaterialTheme.typography.titleMedium)
                Text(e["nextStep"])
                Text("仅作原记录保留。跟进事项以当前待办为准。",color=Muted)
                SecondaryAction(if(tasks.isEmpty())"确认并添加跟进" else "查看当前跟进",{next("followup")},Modifier.fillMaxWidth())
            }
        }
        "context","points"->AppBottomSheet(if(panel=="context")"这次沟通" else "单独记下的要点",::finishPanel) {close->
            fun next(value:String){afterPanel=value;close()}
            TextButton(onClick={next("more")}) { Icon(Icons.Outlined.ArrowBack,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("返回补充信息") }
            MetadataShelf {
                (if(panel=="context")noteContextKeys else notePointKeys).forEach { key->
                    MetadataChip(noteFieldTitle(key),shortNoteValue(e[key]),noteFieldIcon(key),{next("field:$key")},Modifier.testTag("note_edit_$key"))
                }
            }
        }
        else->if(panel.startsWith("field:")) {
            val key=panel.removePrefix("field:")
            TextDetailSheet(noteFieldTitle(key),if(key=="title")e.title else e[key],
                onConfirm={value->if(key=="title")vm.change(e.copy(title=value)) else vm.change(e.copy(fields=e.fields+(key to value)))},
                onDismiss={panel=""},placeholder=if(key=="tags")"例如：居家护理、下周联系" else "记下你想保留的内容…",
                singleLine=key in setOf("title","participants","mode","certainty"),
                testTag="note_$key",supportingText=if(key=="tags")"用逗号或换行分开。" else null)
        }
    }
}

@Composable fun AttachmentRow(a:Attachment,onOpen:(Attachment)->Unit,onRemove:((Attachment)->Unit)?=null) {
    Surface(color=Mint,shape=AppShapes.control,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onOpen(a)},modifier=Modifier.weight(1f).heightIn(min=52.dp)) {Icon(Icons.Outlined.AttachFile,null);Spacer(Modifier.width(8.dp));Text(a.name,Modifier.weight(1f))}
            if(onRemove!=null) IconButton(onClick={onRemove(a)}){Icon(Icons.Outlined.Close,"移除${a.name}")}
        }
    }
}

@Composable fun OccurrencePanel(e:Entry,onSave:(Entry)->Unit,onDismiss:()->Unit) {
    val d=Instant.ofEpochMilli(e.occurredAt).atZone(TimeRules.zone)
    var date by rememberSaveable(e.id){mutableStateOf(e["occurredDate"].ifBlank{e["date"].ifBlank{d.toLocalDate().toString()}})}
    var time by rememberSaveable(e.id){mutableStateOf(e["occurredTime"].ifBlank{e["time"].ifBlank{d.format(DateTimeFormatter.ofPattern("HH:mm"))}})}
    var error by remember{mutableStateOf<String?>(null)}
    var picker by rememberSaveable{mutableStateOf("")}
    AppBottomSheet("这件事发生在",onDismiss) { close->
        MetadataShelf {
            AppFilterChip("今天",date==TimeRules.today().toString(),{date=TimeRules.today().toString()},Modifier.testTag("occur_today"))
            AppFilterChip("昨天",date==TimeRules.today().minusDays(1).toString(),{date=TimeRules.today().minusDays(1).toString()},Modifier.testTag("occur_yesterday"))
        }
        MetadataShelf {
            MetadataChip("日期",friendlyDate(date),Icons.Outlined.CalendarToday,{picker="date"},Modifier.testTag("occur_date"))
            MetadataChip("时刻",time,Icons.Outlined.Schedule,{picker="time"},Modifier.testTag("occur_time"))
        }
        if(TimeRules.parseDateTime("$date $time")==null) Text("原输入无效，请重新选择日期和时刻；正文仍保留。",color=MaterialTheme.colorScheme.error)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        PrimaryAction("确定时间",{
            val stamp=TimeRules.parseDateTime("$date $time")
            if(stamp==null) error="请选择有效日期和时刻" else {
                onSave(e.copy(occurredAt=stamp,fields=e.fields+mapOf("occurredDate" to date,"occurredTime" to time)))
                close()
            }
        },Modifier.fillMaxWidth().testTag("occur_save"))
    }
    if(picker=="date") AppDatePicker(date,{date=it},{picker=""},"哪一天",allowClear=false)
    if(picker=="time") AppTimePicker(time,{time=it},{picker=""},"具体时刻",allowClear=false)
}

@Composable fun FollowUpPanel(vm:WorkbookViewModel,s:Snapshot,original:Entry?,source:Entry?,onDismiss:()->Unit,onNotifications:()->Unit) {
    // Load TASK once when opening. Only changed fields are submitted as a patch.
    val baseline=if(original!=null) rememberSaveable(original.id,saver=FollowUpEntrySaver){original} else null
    // A new form owns its own identity; retries and rotation reuse that identity.
    var task by rememberSaveable(original?.id,source?.id,stateSaver=FollowUpEntrySaver){mutableStateOf(original ?: Entry(
        id=java.util.UUID.randomUUID().toString(),kind=Kind.TASK,parentId=source?.parentId,sourceId=source?.id,
        fields=mapOf("status" to "未完成","priority" to "普通")))}
    var busy by remember{mutableStateOf(false)}
    var submitted by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var detail by rememberSaveable{mutableStateOf("")}
    var pendingDetail by rememberSaveable{mutableStateOf("")}
    fun field(key:String,value:String){task=task.copy(fields=task.fields+(key to value))}
    fun chooseDate(value:String) {
        task=task.copy(fields=task.fields+if(value.isBlank())mapOf("date" to "","time" to "","reminder" to "false") else mapOf("date" to value))
    }
    fun submit(close:()->Unit) {
        if(!busy&&!submitted) {busy=true;vm.action {
            try {vm.submitFollowUp(task,baseline){submitted=true;busy=false;close();vm.message="跟进已保存"}}
            catch(e:Exception){error=e.message ?: "跟进未保存，请重试"}
            finally{busy=false}
        }}
    }
    AppBottomSheet(if(original==null)"下一步想做什么" else "这件事的下一步",{if(!busy)onDismiss()},dismissEnabled=!busy,
        bottomBar={close->
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            PrimaryAction(if(busy)"正在保存…" else if(submitted)"已保存" else if(original==null)"加入跟进" else "保存跟进",{submit(close)},
                Modifier.fillMaxWidth().testTag("followup_submit"),enabled=!busy&&!submitted)
        }) {
        // A modal sheet owns a different focus tree from the page beneath it.
        // Wait for this sheet's keyboard to leave before opening its detail picker.
        val sheetFocus=LocalFocusManager.current
        val sheetKeyboard=LocalSoftwareKeyboardController.current
        val sheetDensity=LocalDensity.current
        val sheetIme=WindowInsets.ime
        fun showDetail(value:String){sheetFocus.clearFocus(force=true);sheetKeyboard?.hide();pendingDetail=value}
        LaunchedEffect(pendingDetail) {
            val requested=pendingDetail
            if(requested.isNotBlank()) {
                snapshotFlow {sheetIme.getBottom(sheetDensity)}.first {it==0}
                detail=requested
                pendingDetail=""
            }
        }
        BasicTextField(value=task.title,onValueChange={task=task.copy(title=it)},enabled=!busy&&!submitted,
            textStyle=MaterialTheme.typography.titleMedium.copy(color=Ink,fontWeight=FontWeight.Medium),cursorBrush=SolidColor(Green),
            modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).padding(vertical=10.dp).testTag("followup_title"),
            decorationBox={inner->Box{if(task.title.isEmpty())Text("比如，明天发一份服务介绍…",color=Muted,style=MaterialTheme.typography.titleMedium);inner()}})
        if(original==null && source?.get("nextStep")?.isNotBlank()==true) TextButton(onClick={task=task.copy(title=source["nextStep"])}){Text("采用旧记录的下一步建议")}
        MetadataShelf {
            AppFilterChip("今天",task["date"]==TimeRules.today().toString(),{chooseDate(TimeRules.today().toString())},Modifier.testTag("followup_today"))
            AppFilterChip("明天",task["date"]==TimeRules.today().plusDays(1).toString(),{chooseDate(TimeRules.today().plusDays(1).toString())},Modifier.testTag("followup_tomorrow"))
            MetadataChip("选日期",task["date"].takeIf{it.isNotBlank()}?.let(::friendlyDate).orEmpty(),Icons.Outlined.CalendarToday,{showDetail("date")},Modifier.testTag("followup_date"))
        }
        MetadataShelf {
            if(task["date"].isNotBlank()) MetadataChip("时刻",task["time"].ifBlank{"待定"},Icons.Outlined.Schedule,{showDetail("time")},Modifier.testTag("followup_time"))
            MetadataChip("优先级",task["priority"].ifBlank{"普通"},Icons.Outlined.Flag,{showDetail("priority")},Modifier.testTag("followup_priority"))
            MetadataChip("提醒",if(task["reminder"]=="true")"已开启" else "未开启",Icons.Outlined.NotificationsNone,{
                if(task["reminder"]=="true")field("reminder","false")
                else if(task["date"].isBlank())showDetail("reminder-date")
                else {field("reminder","true");onNotifications()}
            },Modifier.testTag("followup_reminder"))
        }
        DetailAction(if(task["status"]=="已完成")"完成后记下的" else "补一句",task["completionNote"].ifBlank{"需要时再写一点备注"},Icons.Outlined.Notes,{showDetail("note")},Modifier.testTag("followup_edit_note"))
        if(task["reminder"]=="true") Text(if(task["time"].isBlank())"当天 09:00 提醒，系统可能延后。" else "按选定的时刻提醒，系统可能延后。",color=Muted,style=MaterialTheme.typography.bodySmall)
        if(task["date"].isBlank())Text("时间还没想好，也可以先记下。",color=Muted,style=MaterialTheme.typography.bodySmall)
        if(original?.get("status") in listOf("已完成","已取消")) Text("保留${original?.get("status")}状态",color=Muted)
    }
    when(detail) {
        "date","reminder-date"->AppDatePicker(task["date"],{value->
            chooseDate(value)
            if(detail=="reminder-date"&&value.isNotBlank()){field("reminder","true");onNotifications()}
        },{detail=""},if(detail=="reminder-date")"哪天提醒你" else "什么时候再联系",allowClear=detail=="date")
        "time"->AppTimePicker(task["time"],{field("time",it)},{detail=""},"具体时刻")
        "priority"->AppBottomSheet("这件事有多紧要",{detail=""}) {close->
            MetadataShelf {TaskPriorities.forEach{value->AppFilterChip(value,task["priority"]==value,{field("priority",value);close()})}}
        }
        "note"->TextDetailSheet(if(task["status"]=="已完成")"完成后记下的" else "补一句",task["completionNote"],
            {field("completionNote",it)},{detail=""},placeholder="记下做这件事时要留意的内容…",testTag="followup_note")
    }
}

@Composable fun NoteDetail(vm:WorkbookViewModel,e:Entry,s:Snapshot,onAttachment:(Attachment)->Unit,onNotifications:()->Unit) {
    var more by rememberSaveable(e.id){mutableStateOf(false)}
    var summary by remember{mutableStateOf(false)}
    var followup by rememberSaveable(e.id){mutableStateOf(false)}
    var selectedTaskId by rememberSaveable(e.id){mutableStateOf<String?>(null)}
    val files=s.attachmentsFor(e.id)
    val tasks=remember(s.entries,e.id) { s.sourcedFrom(e.id).filter{it.kind==Kind.TASK} }
    Column(Modifier.fillMaxSize()) {
        BackTitle("沟通记录",vm::back,actions={TextButton(onClick={vm.edit(e)},modifier=Modifier.testTag("edit_entry")){Text("编辑")}})
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("detail_scroll"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(e.title.isNotBlank()) item(key="title",contentType="title") { Text(e.title,style=MaterialTheme.typography.titleLarge,color=Brand) }
            item(key="body",contentType="body") { SelectionContainer {Text(e["content"].ifBlank{if(files.isNotEmpty())"附件记录" else "空白记录"},style=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=28.sp),modifier=Modifier.testTag("note_detail_body"))} }
            items(files,key={"attachment-${it.id}"},contentType={"attachment"}) { AttachmentRow(it,onAttachment) }
            item(key="time",contentType="meta") { Text(occurrenceLabel(e,vm.clockNow),color=Muted) }
            s.find(e.parentId)?.let { parent->item(key="parent",contentType="meta") { TextButton(onClick={vm.navigate("detail:${parent.id}")}){Icon(kindIcon(parent.kind),null);Spacer(Modifier.width(8.dp));Text(parent.title)} } }
            item(key="tasks-heading",contentType="heading") { Column { HorizontalDivider();SectionTitle("后续跟进",if(tasks.isEmpty())"加跟进" else "修改"){selectedTaskId=tasks.firstOrNull()?.id;followup=true} } }
            items(tasks,key={"task-${it.id}"},contentType={"task"}) { task->FollowUpRow(task,s,vm.clockNow,{vm.navigate("detail:${task.id}")},{vm.taskStatus(task,"已完成")},{selectedTaskId=task.id;followup=true}) }
            if(tasks.isEmpty()) item(key="tasks-empty",contentType="meta") { Text("想好下一步时，再加入跟进。",color=Muted) }
            item(key="more",contentType="details") { Column {
                TextButton(onClick={more=!more}){Text(if(more)"收起更多信息" else "更多信息")}
                ExpandingSection(more) {
                    e.fields.filterKeys{it !in listOf("content","occurredDate","occurredTime")&&!it.startsWith("_")}.filterValues{it.isNotBlank()}.forEach{(k,v)->Text("${fieldLabel(k)}\n$v")}
                    Text("创建于 ${TimeRules.formatDateTime(e.createdAt)}\n更新于 ${TimeRules.formatDateTime(e.updatedAt)}",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            } }
            item(key="summary",contentType="action") { SecondaryAction("预览 / 导出摘要",{summary=true},Modifier.fillMaxWidth().testTag("export_summary")) }
        }
    }
    if(followup) FollowUpPanel(vm,s,s.find(selectedTaskId),e,{followup=false},onNotifications)
    if(summary) SummaryDialog(vm,e,s){summary=false}
}
