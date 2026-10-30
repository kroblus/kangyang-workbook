@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package cn.kangyang.workbook.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.kangyang.workbook.*
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import java.time.Instant
import java.time.format.DateTimeFormatter

private val homeDateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)

fun kindLabel(kind:Kind)=when(kind) {
    Kind.ORGANIZATION->"单位";Kind.CUSTOMER->"客户";Kind.INTERACTION->"沟通记录";Kind.ACTIVITY->"活动";
    Kind.TASK->"待办";Kind.CONTACT->"联系人";Kind.RECIPIENT->"服务对象";Kind.PROFILE->"本人资料"
}
fun kindIcon(kind:Kind):ImageVector=when(kind) {
    Kind.ORGANIZATION->Icons.Outlined.Business;Kind.CUSTOMER->Icons.Outlined.People;Kind.ACTIVITY->Icons.Outlined.EventNote
    Kind.INTERACTION->Icons.Outlined.Forum;Kind.TASK->Icons.Outlined.CheckCircleOutline;else->Icons.Outlined.PersonOutline
}

@Composable fun WorkbookApp(vm:WorkbookViewModel,onPhoto:()->Unit,onFile:()->Unit,onAttachment:(Attachment)->Unit,onNotifications:()->Unit) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val route=vm.route
    val snackbars=remember { SnackbarHostState() }
    val message=vm.message
    LaunchedEffect(message) {
        if(message!=null) {
            snackbars.showSnackbar(message,withDismissAction=true,duration=SnackbarDuration.Short)
            if(vm.message==message) vm.message=null
        }
    }
    var cancelEditorActivity by remember{mutableStateOf(false)}
    var remove by remember{mutableStateOf<Attachment?>(null)}
    BackHandler(enabled=route!="home"){vm.back()}
    val root=route in listOf("home","orgs","customers","activities")
    Scaffold(containerColor=Canvas,
        snackbarHost={SnackbarHost(snackbars,Modifier.imePadding())},
        topBar={if(root) TopAppBar(title={Text(if(route=="home")"今天" else vm.profile["appName"].ifBlank{"康养工作簿"},style=MaterialTheme.typography.titleLarge)},
            actions={IconButton(onClick={vm.navigate("search")},modifier=Modifier.testTag("search_all")){Icon(Icons.Outlined.Search,"搜索全部")};IconButton(onClick={vm.navigate("settings")},modifier=Modifier.testTag("settings")){Icon(Icons.Outlined.AccountCircle,"本人资料与设置")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Canvas))},
        bottomBar={if(root) NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
            listOf(Triple("home","手记",Icons.Outlined.EditNote),Triple("orgs","单位",Icons.Outlined.Business),Triple("customers","客户",Icons.Outlined.People),Triple("activities","活动",Icons.Outlined.EventNote)).forEach{(dest,label,icon)->
                NavigationBarItem(selected=route==dest,onClick={vm.navigate(dest,true)},icon={Icon(icon,null)},label={Text(label)},modifier=Modifier.testTag("nav_$dest"))
            }
        }}) {padding->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(vm.uiEpoch) {
                    val browseState=rememberSaveableStateHolder()
                    val page: @Composable () -> Unit = {
                        when {
                            route=="editor"->vm.editor?.let{e->
                                if(e.entry.kind==Kind.INTERACTION) NoteEditorScreen(vm,e,s,onPhoto,onFile,onAttachment,{remove=it},onNotifications)
                                else EntryEditor(e.entry,s.entries,s.attachmentsFor(e.entry.id),vm.tags,e.status,e.saving,vm::change,
                                    {if(e.entry.kind==Kind.ACTIVITY&&e.entry["status"]=="已取消"&&s.find(e.entry.id)?.get("status")!="已取消")cancelEditorActivity=true else vm.save()},
                                    vm::back,onPhoto,onFile,onAttachment,{remove=it},onNotifications,vm::flushDraft,e.attachmentStatus)
                            }
                            route.startsWith("detail:")->s.find(route.removePrefix("detail:"))?.let{e->
                                if(e.kind==Kind.INTERACTION) NoteDetail(vm,e,s,onAttachment,onNotifications) else DetailScreen(vm,e,s,onAttachment,onNotifications)
                            } ?: Column{BackTitle("查看记录",vm::back);EmptyCard("暂未找到记录","返回后重新查找，或检查恢复中的内容。")}
                            route=="home"->HomeScreen(vm,s)
                            route=="orgs"->EntryList(vm,s,Kind.ORGANIZATION)
                            route=="customers"->EntryList(vm,s,Kind.CUSTOMER)
                            route=="activities"->EntryList(vm,s,Kind.ACTIVITY)
                            route=="tasks"->TaskList(vm,s,onNotifications)
                            route=="drafts"->DraftList(vm,s)
                            route=="search"->SearchScreen(vm,s)
                            route=="settings"->SettingsScreen(vm,s,onNotifications)
                            route=="export"->ExportScreen(vm,s)
                            route in listOf("backup","restore")->BackupScreen(vm,s,restoring=route=="restore")
                        }
                    }
                    // The animation key never includes live text, save status or database changes.
                    // Retain browsing state without keeping an outgoing editor alive for animation.
                    val pageKey=if(route=="editor") "$route:${vm.editor?.draftId}" else route
                    LightPageTransition(pageKey,Modifier.fillMaxSize()) {
                        if(route in setOf("home","orgs","customers","activities","tasks","drafts","search")) {
                            browseState.SaveableStateProvider(route) { page() }
                        } else page()
                    }
                }
                if(vm.transitioning) Surface(color=Canvas.copy(alpha=.96f),modifier=Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){CircularProgressIndicator();Text("正在切换资料，请稍候",Modifier.padding(16.dp))}}
            }
        }
    }
    if(cancelEditorActivity) CancelActivityDialog({cancelEditorActivity=false},{cancelEditorActivity=false;vm.save(it)})
    remove?.let{a->ConfirmDialog("移除此附件","只移除当前记录中的“${a.name}”，正文和其他附件保留。",{remove=null;vm.removeAttachment(a)},{remove=null},"确认移除",destructive=true)}
}

@Composable fun BackTitle(title:String,onBack:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"返回")}},actions=actions,
        windowInsets=WindowInsets(0,0,0,0),colors=TopAppBarDefaults.topAppBarColors(containerColor=Canvas))
}
@Composable fun SectionTitle(title:String,action:String?=null,onAction:()->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));if(action!=null)TextButton(onClick=onAction){Text(action)}}
}
@Composable fun EmptyCard(title:String,description:String) {
    Surface(shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(description,color=Muted,style=MaterialTheme.typography.bodyMedium)}}
}
@Composable fun Badge(text:String){if(text.isNotBlank())Surface(color=Mint,shape=AppShapes.badge){Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),color=Brand,style=MaterialTheme.typography.labelMedium)}}
@Composable fun EntryCard(entry:Entry,snapshot:Snapshot,onClick:()->Unit) {
    if(entry.kind==Kind.INTERACTION){RecordRow(entry,snapshot,onClick);return}
    Surface(onClick=onClick,shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("entry_${entry.id}")) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(entry.title,style=MaterialTheme.typography.titleMedium)
            val subtitle=when(entry.kind){
                Kind.ORGANIZATION->listOf(entry["type"],entry["area"],entry["stage"]).filter{it.isNotBlank()}.joinToString(" · ")
                Kind.CUSTOMER->listOf(entry["stage"],entry["tags"].replace('\n','、')).filter{it.isNotBlank()}.joinToString(" · ")
                Kind.ACTIVITY->"${entry["start"].ifBlank{"时间待定"}}  ${entry["location"]}"
                Kind.TASK->"${entry["date"].ifBlank{"未设日期"}} ${entry["time"]} · ${entry["status"]}"
                else->listOf(entry["role"],entry["relationship"],entry["phone"]).filter{it.isNotBlank()}.joinToString(" · ")
            }
            if(subtitle.isNotBlank())Text(subtitle,color=Muted,style=MaterialTheme.typography.bodySmall)
            if(entry.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)) {
                val children=snapshot.childrenOf(entry.id)
                val summary=remember(children) {
                    Triple(
                        children.firstOrNull{it.kind==Kind.CONTACT&&it["primary"]=="true"} ?: children.firstOrNull{it.kind==Kind.CONTACT},
                        children.asSequence().filter{it.kind==Kind.INTERACTION}.maxByOrNull{it.occurredAt},
                        children.asSequence().filter{it.kind==Kind.TASK&&it["status"] !in listOf("已完成","已取消")}.minByOrNull{TimeRules.taskDue(it)?:Long.MAX_VALUE},
                    )
                }
                summary.first?.let{Text(listOf(it.title,it["phone"]).filter{v->v.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)}
                summary.second?.let{Text(it["content"].ifBlank{noteTitle(it,snapshot)},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)}
                summary.third?.let{Text("下一步：${it.title}",color=Green,style=MaterialTheme.typography.bodySmall)}
            }
            if(entry.archived)Badge("已归档") else if(entry.kind==Kind.ACTIVITY)Badge(entry["status"])
        }
    }
}
@Composable fun RecordRow(e:Entry,s:Snapshot,onClick:()->Unit) {
    Surface(onClick=onClick,shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("entry_${e.id}")) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(noteTitle(e,s),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            val body=if(e.title.isBlank())e["content"].substringAfter('\n',"") else e["content"]
            if(body.isNotBlank())Text(body,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text(listOf(occurrenceLabel(e),s.find(e.parentId)?.title.orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),color=Muted,style=MaterialTheme.typography.bodySmall)
            val files=s.attachmentsFor(e.id).size;if(files>0)Text("$files 个附件",color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun FollowUpRow(e:Entry,s:Snapshot,now:Long,onOpen:()->Unit,onComplete:()->Unit,onReschedule:()->Unit,modifier:Modifier=Modifier) {
    val ended=e["status"] in listOf("已完成","已取消")
    val bucket=TimeRules.taskBucket(e,now)
    val date=listOf(e["date"],e["time"]).filter(String::isNotBlank).joinToString(" ")
    val timing=listOf(bucket,date).filter(String::isNotBlank).distinct().joinToString(" · ")
    Surface(onClick=onOpen,shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,modifier=modifier.fillMaxWidth().testTag("entry_${e.id}")) {
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp).testTag("task_${e.id}")) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(e["status"] !in listOf("已完成","已取消"))IconButton(onClick=onComplete,modifier=Modifier.testTag("complete_${e.id}")){Icon(Icons.Outlined.RadioButtonUnchecked,"完成${e.title}")}
                else Icon(kindIcon(Kind.TASK),null,Modifier.padding(12.dp))
                Column(Modifier.weight(1f).clickable(onClick=onOpen).padding(vertical=8.dp)) {Text(e.title,style=MaterialTheme.typography.titleMedium);s.find(e.parentId)?.let{Text(it.title,color=Muted,style=MaterialTheme.typography.bodySmall)}}
            }
            if(ended) Text(timing,color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp))
            else TextButton(onClick=onReschedule,modifier=Modifier.heightIn(min=48.dp)){Text("$timing · ${if(e["date"].isBlank())"设置日期" else "改期"}",color=if(bucket=="逾期")MaterialTheme.colorScheme.error else Muted)}
        }
    }
}

@Composable private fun HomeScreen(vm:WorkbookViewModel,s:Snapshot) {
    val now=vm.clockNow
    val visits=remember(s.entries) { s.entriesOf(Kind.INTERACTION).sortedByDescending{it.updatedAt}.take(5) }
    val tasks=remember(s.entries,now) { s.entriesOf(Kind.TASK).filter{TimeRules.taskBucket(it,now) in listOf("逾期","今日")}.sortedBy{TimeRules.taskDue(it)}.take(2) }
    val drafts=remember(s.drafts,s.attachments) { s.drafts.filter{!it.isNoteBuffer && (it.entry.title.isNotBlank()||it.entry["content"].isNotBlank()||it.entry["plan"].isNotBlank()||s.attachmentsFor(it.entry.id).isNotEmpty())} }
    val activities=remember(s.entries) { s.entriesOf(Kind.ACTIVITY).filter{it["status"] !in listOf("已完成","已取消")}.sortedBy{TimeRules.parseDateTime(it["start"])?:Long.MAX_VALUE}.take(2) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).testTag("home_scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(TimeRules.today(now).format(homeDateFormat),color=Muted,style=MaterialTheme.typography.bodyMedium)
        Surface(onClick={vm.create(Kind.INTERACTION)},shape=AppShapes.card,color=Mint,
            modifier=Modifier.fillMaxWidth().heightIn(min=84.dp).testTag("quick_record")) {
            Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.EditNote,null,Modifier.size(28.dp),tint=Green)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("记一笔",style=MaterialTheme.typography.titleMedium,color=Brand)
                    Text("随手记下进展与想法",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                Icon(Icons.Outlined.ArrowForward,null,Modifier.size(20.dp),tint=Green)
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {Text("需要跟进",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton(onClick={vm.navigate("tasks")},modifier=Modifier.testTag("tasks_all")){Text("全部待办")}}
        if(tasks.isEmpty())Text("今天没有待跟进的事，按自己的节奏来。",color=Muted,style=MaterialTheme.typography.bodyMedium)
        tasks.forEach{FollowUpRow(it,s,now,{vm.navigate("detail:${it.id}")},{vm.taskStatus(it,"已完成")},{vm.edit(it)})}
        SectionTitle("最近手记","查找历史"){vm.navigate("search")}
        if(visits.isEmpty())Text("从刚才的一次沟通开始，写一句也可以。",color=Muted)
        visits.forEach{RecordRow(it,s){vm.navigate("detail:${it.id}")}}
        if(drafts.isNotEmpty()){SectionTitle("留待整理","查看 ${drafts.size} 份"){vm.navigate("drafts")};drafts.take(2).forEach{DraftRow(vm,it,s)}}
        if(activities.isNotEmpty()){SectionTitle("活动安排","全部活动"){vm.navigate("activities",true)};activities.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
        Text(if(BuildConfig.FLAVOR=="qa")"试用空间 · 仅保存在本机" else "仅保存在本机 · 可离线使用",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=16.dp))
    }
}
@Composable private fun DraftRow(vm:WorkbookViewModel,d:Draft,s:Snapshot) {
    var confirm by remember{mutableStateOf(false)}
    val original=s.find(d.entry.id)
    val staleTask=isStaleTaskDraft(d,original)
    val reason=if(staleTask)"这条跟进已有更新，旧填写保留供查看和复制。" else d.recoveryReason
    Surface(onClick={if(reason.isNotBlank())confirm=true else vm.edit(d.entry,d)},color=Mint,shape=AppShapes.card,modifier=Modifier.fillMaxWidth().testTag("draft_${d.id}")) {
        Column(Modifier.padding(16.dp)){Text(d.entry.title.ifBlank{d.entry["content"].take(50).ifBlank{"${kindLabel(d.entry.kind)}填写"}},fontWeight=FontWeight.Medium);if(reason.isNotBlank())Text(reason,color=Muted,style=MaterialTheme.typography.bodySmall)}
    }
    if(confirm && staleTask) {
        AlertDialog(onDismissRequest={confirm=false},title={Text("查看旧跟进填写")},
            text={SelectionContainer {Column(Modifier.verticalScroll(rememberScrollState()).testTag("stale_task_draft_preview"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("当前跟进保留后来修改的日期、提醒和状态。可复制旧内容，再编辑当前跟进。",color=Muted)
                Text("当前跟进",fontWeight=FontWeight.SemiBold);Text(original!!.title)
                Text("日期：${original["date"].ifBlank{"未设日期"}}\n提醒：${if(original["reminder"]=="true")"开启" else "关闭"}\n状态：${original["status"]}")
                Text("保留的旧填写",fontWeight=FontWeight.SemiBold);Text(d.entry.title)
                d.entry.fields.filterValues{it.isNotBlank()}.forEach{(k,v)->Text("${fieldLabel(k)}：${if(k=="reminder") {if(v=="true")"开启" else "关闭"} else v}")}
            }}},
            confirmButton={TextButton(onClick={confirm=false;vm.edit(original!!)},modifier=Modifier.testTag("edit_current_task")){Text("编辑当前跟进")}},
            dismissButton={TextButton(onClick={confirm=false}){Text("保留旧填写")}})
    } else if(confirm) {
        val conflict=original!=null && d.recoveryReason.contains("保留")
        AlertDialog(onDismissRequest={confirm=false},title={Text(if(conflict)"选择继续编辑的内容" else "修正旧填写")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(d.recoveryReason)
                if(conflict){Text("当前记录",fontWeight=FontWeight.SemiBold);Text(original!!["content"]);Text("保留的旧填写",fontWeight=FontWeight.SemiBold);Text(d.entry["content"]);Text("采用旧填写后，当前记录内容也会另存到待整理的填写，仍可找回。",color=Muted)}
                else Text(d.entry["content"])
            }},confirmButton={TextButton(onClick={confirm=false;vm.edit(d.entry,d)}){Text(if(conflict)"采用旧填写继续编辑" else "继续修正")}},
            dismissButton={TextButton(onClick={confirm=false}){Text(if(conflict)"保留当前记录" else "暂不修改")}})
    }
}
@Composable private fun DraftList(vm:WorkbookViewModel,s:Snapshot) {
    var discard by remember{mutableStateOf<Draft?>(null)}
    val drafts=remember(s.drafts) { s.drafts.filter{!it.isNoteBuffer || it.recoveryReason.isNotBlank()} }
    Column(Modifier.fillMaxSize()){BackTitle("待整理的填写",vm::back);LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("draft_list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        items(drafts,key={it.id},contentType={"draft"}) { draft->Column { DraftRow(vm,draft,s);TextButton(onClick={discard=draft}){Text("放弃这份填写")} } }
    }}
    discard?.let{d->ConfirmDialog("放弃这份填写","正式记录仍然保留，尚未提交的填写内容将移除。",{discard=null;vm.discard(d)},{discard=null},"确认放弃",destructive=true)}
}
@Composable fun ChoiceFilter(label:String,choices:List<String>,value:String,onValue:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    MetadataChip(label,value,Icons.Outlined.FilterList,{open=true},Modifier.testTag("filter_$label"))
    if(open)AppBottomSheet(label,{open=false}){close->
        MetadataShelf {
            (listOf("全部")+choices).distinct().forEach{choice->
                AppFilterChip(choice,if(choice=="全部")value.isBlank() else value==choice,
                    {onValue(if(choice=="全部")"" else choice);close()})
            }
        }
    }
}
@Composable private fun EntryList(vm:WorkbookViewModel,s:Snapshot,kind:Kind) {
    var query by rememberSaveable(kind){mutableStateOf("")};var type by rememberSaveable(kind){mutableStateOf("")};var stage by rememberSaveable(kind){mutableStateOf("")}
    var date by rememberSaveable(kind){mutableStateOf("")};var archived by rememberSaveable(kind){mutableStateOf(false)};var filters by remember{mutableStateOf(false)}
    var datePicker by remember{mutableStateOf(false)}
    val matches=remember(s.entries,s.attachments,query,archived,kind,type,stage,date) {
        val candidates=if(query.isBlank()) s.entriesOf(kind).filter{archived||!it.archived} else searchEntries(s,query,archived).filter{it.kind==kind}
        candidates.filter{(type.isBlank()||it["type"]==type||it["tags"].contains(type))&&(stage.isBlank()||it["stage"]==stage||it["status"]==stage)&&(date.isBlank()||it["start"].startsWith(date))}
    }
    val groups=remember(matches,kind) {
        if(kind==Kind.ACTIVITY) {
            val grouped=matches.groupBy { when {
                it["status"] in listOf("已完成","已取消")->"已结束"
                it["start"].isBlank()->"时间待定"
                else->"已安排"
            } }
            listOf("已安排","时间待定","已结束").mapNotNull { name->grouped[name]?.let { name to it.sortedBy{e->e["start"]} } }
        } else listOf("" to matches.sortedByDescending{it.updatedAt})
    }
    LazyColumn(Modifier.fillMaxSize().testTag("list_scroll"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item(key="heading",contentType="heading") { SectionTitle(when(kind){Kind.ORGANIZATION->"合作单位";Kind.CUSTOMER->"客户与家庭";else->"活动安排"},"新增${kindLabel(kind)}"){vm.create(kind)} }
        item(key="search",contentType="search") { AppTextField(query,{query=it},if(kind==Kind.ACTIVITY)"搜索活动名称、地点或方案" else "搜索名称、联系人、电话或内容",Modifier.fillMaxWidth().testTag("list_search"),singleLine=true) }
        item(key="count",contentType="filters") { Row(verticalAlignment=Alignment.CenterVertically){Text("${matches.size} 个${kindLabel(kind)}",color=Muted,modifier=Modifier.weight(1f));TextButton(onClick={filters=true},modifier=Modifier.testTag("list_filters")){Text("筛选")}} }
        if(matches.isEmpty()) item(key="empty",contentType="empty") { EmptyCard("没有匹配的${kindLabel(kind)}","可以新建，或调整关键词和筛选范围。") }
        groups.forEach { (group,rows)->
            if(group.isNotBlank()) item(key="group-$group",contentType="heading") { SectionTitle(group) }
            items(rows,key={"entry-${it.id}"},contentType={it.kind}) { entry->EntryCard(entry,s){vm.navigate("detail:${entry.id}")} }
        }
    }
    if(filters)AppBottomSheet("筛选${kindLabel(kind)}",{filters=false}) { close->
        val types=remember(s.entries,kind) { s.entriesOf(kind).map{it["type"]}.filter{it.isNotBlank()}.distinct() }
        val stages=remember(s.entries,kind) { s.entriesOf(kind).map{it[if(kind==Kind.ACTIVITY)"status" else "stage"]}.filter{it.isNotBlank()}.distinct() }
        MetadataShelf {
            ChoiceFilter(if(kind==Kind.CUSTOMER)"需求标签" else "类型",if(kind==Kind.CUSTOMER)vm.tags else types,type,{type=it})
            ChoiceFilter("阶段",stages,stage,{stage=it})
            if(kind==Kind.ACTIVITY)MetadataChip("活动日期",date.takeIf{it.isNotBlank()}?.let(::friendlyDate).orEmpty(),
                Icons.Outlined.CalendarMonth,{datePicker=true},Modifier.testTag("activity_date_filter"))
        }
        if(kind!=Kind.ACTIVITY)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
        PrimaryAction("查看结果",close,Modifier.fillMaxWidth())
    }
    if(datePicker)AppDatePicker(date,{date=it},{datePicker=false},"活动日期")
}
fun searchEntries(s:Snapshot,query:String,includeArchived:Boolean):List<Entry> {
    val q=query.trim()
    if(q.isBlank()) return if(includeArchived) s.entries else s.entries.filter{!it.archived}
    val fileOwners=s.attachments.asSequence().filter{it.name.contains(q,true)}.map{it.ownerId}.toSet()
    val direct=s.entries.asSequence().filter{it.id in fileOwners||it.title.contains(q,true)||it.fields.any{(k,v)->!k.startsWith("_")&&v.contains(q,true)}}.map{it.id}.toSet()
    val parents=direct.mapNotNull{s.find(it)?.parentId}.toSet()
    return s.entries.filter{(includeArchived||!it.archived)&&(it.id in direct||it.id in parents)}
}
@Composable private fun SearchScreen(vm:WorkbookViewModel,s:Snapshot) {
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)}
    var from by rememberSaveable{mutableStateOf("")};var to by rememberSaveable{mutableStateOf("")};var tag by rememberSaveable{mutableStateOf("")}
    var objectId by rememberSaveable{mutableStateOf<String?>(null)};var objectPicker by remember{mutableStateOf(false)};var filters by remember{mutableStateOf(false)}
    var type by rememberSaveable{mutableStateOf("")}
    var datePicker by remember{mutableStateOf<String?>(null)}
    val valid=from.isBlank()||to.isBlank()||from<=to
    val results=remember(s.entries,s.attachments,query,archived,from,to,tag,objectId,type,valid) {
        if(!valid) emptyList() else searchEntries(s,query,archived).filter{e->
            (tag.isBlank()||e["tags"].contains(tag)||s.find(e.parentId)?.get("tags")?.contains(tag)==true)&&
                (type.isBlank()||kindLabel(e.kind)==type)&&(objectId==null||e.parentId==objectId||e.id==objectId)&&
                (e.kind!=Kind.INTERACTION||(from.isBlank()&&to.isBlank())||TimeRules.formatDate(e.occurredAt).let{d->(from.isBlank()||d>=from)&&(to.isBlank()||d<=to)})
        }
    }
    val groups=remember(results,query.isBlank()) {
        val grouped=results.groupBy{it.kind}
        listOf(Kind.INTERACTION,Kind.ORGANIZATION,Kind.CUSTOMER,Kind.ACTIVITY,Kind.TASK,Kind.CONTACT,Kind.RECIPIENT).mapNotNull { kind->
            grouped[kind]?.let { rows->kind to rows.sortedByDescending{it.occurredAt}.let{if(query.isBlank())it.take(10) else it} }
        }
    }
    Column(Modifier.fillMaxSize()){BackTitle("查找历史",vm::back,actions={TextButton(onClick={filters=true},modifier=Modifier.testTag("search_filters")){Text("筛选")}})
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("search_scroll"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="search",contentType="search") { AppTextField(query,{query=it},"搜索正文、名称、电话或附件名",Modifier.fillMaxWidth().testTag("global_search")) }
            if(from.isNotBlank()||to.isNotBlank()||objectId!=null||type.isNotBlank()||tag.isNotBlank()||archived) {
                item(key="active-filters",contentType="filters") {
                    MetadataShelf {
                        if(from.isNotBlank()||to.isNotBlank())MetadataChip("日期范围",listOf(from.ifBlank{"不限"},to.ifBlank{"不限"}).joinToString(" 至 "),Icons.Outlined.CalendarMonth,{filters=true})
                        s.find(objectId)?.let{MetadataChip("关联对象",it.title,Icons.Outlined.PersonOutline,{filters=true})}
                        if(type.isNotBlank())MetadataChip("业务类型",type,Icons.Outlined.Notes,{filters=true})
                        if(tag.isNotBlank())MetadataChip("业务标签",tag,Icons.Outlined.Label,{filters=true})
                        if(archived)MetadataChip("归档", "包含已归档",Icons.Outlined.Inventory2,{filters=true})
                    }
                }
            }
            if(!valid) item(key="range-error",contentType="label") { Text("结束日期不能早于开始日期",color=MaterialTheme.colorScheme.error) }
            if(query.isBlank()) item(key="recent",contentType="label") { Text("最近记录",color=Muted) }
            groups.forEach { (kind,group)->
                item(key="group-$kind",contentType="heading") { SectionTitle("${kindLabel(kind)} · ${group.size}") }
                items(group,key={"entry-${it.id}"},contentType={it.kind}) { e->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        EntryCard(e,s){vm.navigate("detail:${e.id}")}
                        if(query.isNotBlank()&&e.kind==Kind.INTERACTION){val p=e["content"].indexOf(query,ignoreCase=true);if(p>60)Text("…"+e["content"].substring((p-20).coerceAtLeast(0),(p+query.length+60).coerceAtMost(e["content"].length))+"…",color=Muted,style=MaterialTheme.typography.bodyMedium)}
                    }
                }
            }
            if(results.isEmpty()) item(key="empty",contentType="label") { Text("没有找到匹配内容",color=Muted) }
        }
    }
    if(filters)AppBottomSheet("筛选范围",{filters=false}) { close->
        Text("按需要选一两项，就能缩小范围。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        MetadataShelf {
            MetadataChip("发生日期从",from.takeIf{it.isNotBlank()}?.let(::friendlyDate).orEmpty(),Icons.Outlined.CalendarMonth,{datePicker="from"},Modifier.testTag("search_date_from"))
            MetadataChip("至",to.takeIf{it.isNotBlank()}?.let(::friendlyDate).orEmpty(),Icons.Outlined.CalendarMonth,{datePicker="to"},Modifier.testTag("search_date_to"))
            MetadataChip("全部对象",s.find(objectId)?.title.orEmpty(),Icons.Outlined.PersonOutline,{objectPicker=true},Modifier.testTag("search_object_filter"))
            ChoiceFilter("业务类型",listOf("沟通记录","单位","客户","活动","待办"),type,{type=it})
            ChoiceFilter("业务标签",vm.tags,tag,{tag=it})
        }
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
        if(!valid)Text("结束日期不能早于开始日期",color=MaterialTheme.colorScheme.error)
        PrimaryAction("查看结果",close,Modifier.fillMaxWidth())
        if(from.isNotBlank()||to.isNotBlank()||objectId!=null||type.isNotBlank()||tag.isNotBlank()||archived) {
            TextButton(onClick={from="";to="";objectId=null;type="";tag="";archived=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("清除筛选")}
        }
    }
    datePicker?.let{part->AppDatePicker(if(part=="from")from else to,{if(part=="from")from=it else to=it},{datePicker=null},if(part=="from")"发生日期从" else "至")}
    if(objectPicker) {
        val objects=remember(s.entries) { s.entriesOf(Kind.ORGANIZATION)+s.entriesOf(Kind.CUSTOMER) }
        ObjectPickerPanel(objects,objectId,{objectId=it},{objectPicker=false},title="筛选对象",clearLabel="全部对象",contacts=s.entriesOf(Kind.CONTACT))
    }
}
@Composable private fun TaskList(vm:WorkbookViewModel,s:Snapshot,onNotifications:()->Unit) {
    var history by rememberSaveable{mutableStateOf(false)}
    var editingId by rememberSaveable{mutableStateOf<String?>(null)}
    val now=vm.clockNow
    val grouped=remember(s.entries,history,now) {
        val tasks=s.entriesOf(Kind.TASK)
        val dueById=tasks.associate { it.id to TimeRules.taskDue(it) }
        val buckets=tasks.groupBy { TimeRules.taskBucket(it,now) }
        val groups=if(history)listOf("已完成","已取消") else listOf("逾期","今日","未来","未设日期")
        groups.mapNotNull { bucket->buckets[bucket]?.let { rows->bucket to rows.sortedBy { dueById[it.id] ?: Long.MAX_VALUE } } }
    }
    Column(Modifier.fillMaxSize()){BackTitle("全部待办",vm::back,actions={TextButton(onClick={vm.create(Kind.TASK)}){Text("新增")}})
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("task_list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="history-filter",contentType="filters") {
                Row{FilterChip(!history,{history=false},label={Text("未完成")});Spacer(Modifier.width(8.dp));FilterChip(history,{history=true},label={Text("已完成 / 取消")})}
            }
            grouped.forEach { (bucket,rows) ->
                item(key="group-$bucket",contentType="heading") { SectionTitle("$bucket · ${rows.size}") }
                items(rows,key={"task-${it.id}"},contentType={"task"}) { task ->
                    FollowUpRow(task,s,now,{vm.navigate("detail:${task.id}")},{vm.taskStatus(task,"已完成")},{editingId=task.id},
                        Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=tween(AppMotion.Change)))
                }
            }
            if(grouped.isEmpty()) item(key="empty") { Text("没有${if(history)"已结束" else "未完成"}事项",color=Muted) }
        }
    }
    s.find(editingId)?.let{FollowUpPanel(vm,s,it,s.find(it.sourceId),{editingId=null},onNotifications)}
}

@Composable private fun DetailScreen(vm:WorkbookViewModel,e:Entry,s:Snapshot,onAttachment:(Attachment)->Unit,onNotifications:()->Unit) {
    val context=LocalContext.current
    var cancel by remember{mutableStateOf(false)};var archive by remember{mutableStateOf(false)};var export by remember{mutableStateOf(false)}
    var more by rememberSaveable(e.id){mutableStateOf(false)};var taskEditId by rememberSaveable(e.id){mutableStateOf<String?>(null)}
    val dossier=e.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)
    val children=s.childrenOf(e.id)
    val sourced=s.sourcedFrom(e.id)
    val tasks=remember(children,sourced) { (children+sourced).filter{it.kind==Kind.TASK}.distinctBy{it.id} }
    val openTasks=remember(tasks) { tasks.filter{it["status"] !in listOf("已完成","已取消")} }
    val notes=remember(children) { children.filter{it.kind==Kind.INTERACTION}.sortedByDescending{it.occurredAt} }
    val contacts=remember(children) { children.filter{it.kind==Kind.CONTACT} }
    val recipients=remember(children) { children.filter{it.kind==Kind.RECIPIENT} }
    val files=s.attachmentsFor(e.id)
    Column(Modifier.fillMaxSize()){BackTitle(kindLabel(e.kind),vm::back,actions={TextButton(onClick={vm.edit(e)},modifier=Modifier.testTag("edit_entry")){Text("编辑")}})
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("detail_scroll"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item(key="title",contentType="heading") { Text(e.title,style=MaterialTheme.typography.headlineSmall,color=Brand) }
            if(e.archived) item(key="archived",contentType="label") { Badge("已归档 · 历史保留") }
            s.find(e.parentId)?.let{parent->item(key="parent",contentType="action") { TextButton(onClick={vm.navigate("detail:${parent.id}")}){Text("关联${kindLabel(parent.kind)}：${parent.title}")} } }
            if(dossier) {
                val body=if(e.kind==Kind.ORGANIZATION)e["notes"] else e["needs"]
                if(body.isNotBlank()) item(key="dossier-body",contentType="body") { Text(body,style=MaterialTheme.typography.bodyLarge) }
                item(key="dossier-meta",contentType="label") { Text(listOf(e["type"],e["area"],e["stage"]).filter{it.isNotBlank()}.joinToString(" · "),color=Muted) }
                item(key="record",contentType="action") { PrimaryAction("记一次沟通",{vm.create(Kind.INTERACTION,e)},Modifier.fillMaxWidth().testTag("add_interaction")) }
                item(key="tasks-heading",contentType="heading") { SectionTitle("后续跟进","添加跟进"){vm.create(Kind.TASK,e)} }
                items(openTasks,key={"task-${it.id}"},contentType={"task"}) { task->FollowUpRow(task,s,vm.clockNow,{vm.navigate("detail:${task.id}")},{vm.taskStatus(task,"已完成")},{taskEditId=task.id}) }
                item(key="notes-heading",contentType="heading") { SectionTitle(if(e.kind==Kind.ORGANIZATION)"拜访时间线" else "沟通时间线") }
                if(notes.isEmpty()) item(key="notes-empty",contentType="label") { Text("每次沟通单独保存，随时回来查看。",color=Muted) }
                items(notes,key={"note-${it.id}"},contentType={"note"}) { note->RecordRow(note,s){vm.navigate("detail:${note.id}")} }
                item(key="contacts-heading",contentType="heading") { SectionTitle("联系人","添加联系人"){vm.create(Kind.CONTACT,e)} }
                items(contacts,key={"contact-${it.id}"},contentType={"contact"}) { contact->EntryCard(contact,s){vm.navigate("detail:${contact.id}")} }
                if(e.kind==Kind.CUSTOMER) {
                    item(key="recipients-heading",contentType="heading") { SectionTitle("服务对象","添加服务对象"){vm.create(Kind.RECIPIENT,e)} }
                    items(recipients,key={"recipient-${it.id}"},contentType={"recipient"}) { recipient->EntryCard(recipient,s){vm.navigate("detail:${recipient.id}")} }
                }
            }
            if(e.kind==Kind.TASK) item(key="task-details",contentType="details") { Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Badge(TimeRules.taskBucket(e,vm.clockNow));Text("${e["date"].ifBlank{"未设日期"}} ${e["time"]}",color=Muted)
                if(e["status"] !in listOf("已完成","已取消"))PrimaryAction("完成跟进",{vm.taskStatus(e,"已完成")},Modifier.fillMaxWidth().testTag("complete_task"))
                SecondaryAction("修改跟进",{taskEditId=e.id},Modifier.fillMaxWidth().testTag("reschedule_task"))
                if(e["completionNote"].isNotBlank())Text("${if(e["status"]=="已完成")"完成备注" else "备注"}\n${e["completionNote"]}")
                s.find(e.sourceId)?.let{source->TextButton(onClick={vm.navigate("detail:${source.id}")}){Text("查看来源记录：${noteTitle(source,s)}")}}
            } }
            if(e.kind==Kind.ACTIVITY) {
                item(key="activity-meta",contentType="label") { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Badge(e["status"]);Text(e["start"].ifBlank{"时间待定"}+e["end"].takeIf{it.isNotBlank()}?.let{" 至 $it"}.orEmpty(),color=Muted) } }
                listOf("location","goal","audience","plan","division","materials","precautions").filter{e[it].isNotBlank()}.forEach { field->item(key="field-$field",contentType="field") { Text("${fieldLabel(field)}\n${e[field]}") } }
                item(key="tasks-heading",contentType="heading") { SectionTitle("筹备清单","添加筹备待办"){vm.create(Kind.TASK,e)} }
                items(tasks,key={"task-${it.id}"},contentType={"task"}) { task->FollowUpRow(task,s,vm.clockNow,{vm.navigate("detail:${task.id}")},{vm.taskStatus(task,"已完成")},{taskEditId=task.id}) }
                if(e["status"]=="已完成") {
                    item(key="review-heading",contentType="heading") { SectionTitle("执行与复盘") }
                    listOf("actualCount","execution","review","followup").filter{e[it].isNotBlank()}.forEach { field->item(key="field-$field",contentType="field") { Text("${fieldLabel(field)}\n${e[field]}") } }
                }
                item(key="source-customer",contentType="action") { SecondaryAction("新建来源客户",{vm.edit(Entry(kind=Kind.CUSTOMER,fields=mapOf("stage" to "新咨询","source" to "活动：${e.title}","linkedActivity" to e.id)))},Modifier.fillMaxWidth()) }
            }
            if(e.kind==Kind.CONTACT&&e["phone"].isNotBlank()) item(key="dial",contentType="action") { SecondaryAction("打开拨号",{runCatching{context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(e["phone"]))))}.onFailure{vm.message="没有可用的拨号应用"}},Modifier.fillMaxWidth()) }
            if(e.kind in listOf(Kind.CONTACT,Kind.RECIPIENT,Kind.PROFILE)) e.fields.filterValues{it.isNotBlank()}.forEach{(k,v)->item(key="field-$k",contentType="field") { Text("${fieldLabel(k)}\n$v") } }
            items(files,key={"attachment-${it.id}"},contentType={"attachment"}) { AttachmentRow(it,onAttachment) }
            item(key="more",contentType="details") { Column {
                TextButton(onClick={more=!more}){Text(if(more)"收起详细资料" else "详细资料与更多操作")}
                ExpandingSection(more) {
                e.fields.filter{(k,v)->v.isNotBlank()&&!k.startsWith("_")}.forEach{(k,v)->Text("${fieldLabel(k)}\n${if(k in listOf("linkedActivity","linkedOrganization","organizationId"))s.find(v)?.title ?: "关联不可用" else v}")}
                if(dossier)TextButton(onClick={archive=true},modifier=Modifier.testTag("archive_entry")){Text(if(e.archived)"恢复到列表" else "归档${kindLabel(e.kind)}")}
                if(e.kind==Kind.TASK) {
                    if(e["status"]!="未完成")TextButton(onClick={vm.taskStatus(e,"未完成")}){Text("重新打开")}
                    else TextButton(onClick={vm.taskStatus(e,"已取消")}){Text("取消跟进")}
                }
                if(e.kind==Kind.ACTIVITY&&e["status"] !in listOf("已完成","已取消"))TextButton(onClick={cancel=true}){Text("取消活动")}
                Text("创建于 ${TimeRules.formatDateTime(e.createdAt)}\n更新于 ${TimeRules.formatDateTime(e.updatedAt)}",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            } }
            if(e.kind==Kind.ACTIVITY) item(key="summary",contentType="action") { SecondaryAction("预览 / 导出摘要",{export=true},Modifier.fillMaxWidth().testTag("export_summary")) }
        }
    }
    if(cancel)CancelActivityDialog({cancel=false},{cancel=false;vm.cancelActivity(e,it)})
    if(archive)ConfirmDialog(if(e.archived)"恢复到列表" else "归档并保留历史","历史记录和附件保留，尚未完成的跟进仍在待办中。",{archive=false;vm.archive(e)},{archive=false})
    if(export)SummaryDialog(vm,e,s){export=false}
    s.find(taskEditId)?.let{FollowUpPanel(vm,s,it,s.find(it.sourceId),{taskEditId=null},onNotifications)}
}
@Composable fun CancelActivityDialog(onDismiss:()->Unit,onConfirm:(Boolean)->Unit) {
    var cancelTasks by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("取消活动，保留历史")},text={Column{Text("活动提醒将撤销，历史资料保留。");Row(verticalAlignment=Alignment.CenterVertically){Checkbox(cancelTasks,{cancelTasks=it});Text("同时取消未完成的筹备待办",Modifier.weight(1f))}}},
        confirmButton={TextButton(onClick={onConfirm(cancelTasks)}){Text("确认取消活动")}},dismissButton={TextButton(onClick=onDismiss){Text("不取消，返回")}})
}
