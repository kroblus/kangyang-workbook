@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package cn.kangyang.workbook.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
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

fun kindLabel(kind:Kind)=when(kind) {
    Kind.ORGANIZATION->"单位"; Kind.CUSTOMER->"客户"; Kind.INTERACTION->"沟通记录"; Kind.ACTIVITY->"活动";
    Kind.TASK->"待办"; Kind.CONTACT->"联系人"; Kind.RECIPIENT->"服务对象"; Kind.PROFILE->"本人资料"
}
fun kindIcon(kind:Kind):ImageVector=when(kind) {
    Kind.ORGANIZATION->Icons.Outlined.Business; Kind.CUSTOMER->Icons.Outlined.People; Kind.ACTIVITY->Icons.Outlined.EventNote;
    Kind.INTERACTION->Icons.Outlined.Forum; Kind.TASK->Icons.Outlined.CheckCircleOutline; else->Icons.Outlined.PersonOutline
}

@Composable fun WorkbookApp(vm:WorkbookViewModel,onPhoto:()->Unit,onFile:()->Unit,onAttachment:(Attachment)->Unit,onNotifications:()->Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val route=vm.route
    var quick by remember { mutableStateOf(false) }
    var cancelEditorActivity by remember { mutableStateOf(false) }
    var attachmentToRemove by remember { mutableStateOf<Attachment?>(null) }
    LaunchedEffect(vm.message) { vm.message?.let { value -> kotlinx.coroutines.delay(5000); if(vm.message==value) vm.message=null } }
    BackHandler(enabled=route!="home") { vm.back() }
    val roots=listOf("home","orgs","customers","activities")
    val isRoot=route in roots
    Scaffold(containerColor=Canvas,
        topBar={if(isRoot) TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Outlined.Spa,null,tint=Green,modifier=Modifier.size(26.dp))
            Text(vm.profile["appName"].ifBlank { "康养工作簿" },style=MaterialTheme.typography.titleMedium,maxLines=2)
        }},actions={IconButton(onClick={vm.navigate("search")},modifier=Modifier.testTag("search_all")) {Icon(Icons.Outlined.Search,"搜索全部")}; IconButton(onClick={vm.navigate("settings")},modifier=Modifier.testTag("settings")){Icon(Icons.Outlined.AccountCircle,"本人资料与设置")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Canvas))},
        bottomBar={if(isRoot) NavigationBar(containerColor=Color.White,tonalElevation=0.dp) {
            listOf(Triple("home","工作台",Icons.Outlined.Dashboard),Triple("orgs","单位",Icons.Outlined.Business),Triple("customers","客户",Icons.Outlined.People),Triple("activities","活动",Icons.Outlined.EventNote)).forEach {(dest,label,icon)->
                NavigationBarItem(selected=route==dest,onClick={vm.navigate(dest,true)},icon={Icon(icon,null)},label={Text(label)},modifier=Modifier.testTag("nav_$dest"))
            }
        }}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.message?.let { value -> Surface(color=Mint,modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) { Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Brand);IconButton(onClick={vm.message=null}){Icon(Icons.Outlined.Close,"关闭提示")} } } }
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                route=="editor" -> vm.editor?.let { e -> EntryEditor(e.entry,snapshot.entries,snapshot.attachments.filter{it.ownerId==e.entry.id},vm.tags,e.status,e.saving,
                    vm::change,{if(e.entry.kind==Kind.ACTIVITY && e.entry["status"]=="已取消" && snapshot.find(e.entry.id)?.get("status")!="已取消") cancelEditorActivity=true else vm.save()},vm::back,onPhoto,onFile,onAttachment,{a->attachmentToRemove=a},onNotifications,vm::flushDraft)
                }
                route.startsWith("detail:") -> {
                    val e=snapshot.entries.find{it.id==route.removePrefix("detail:")}
                    if(e==null) Column { BackTitle("查看记录",vm::back); EmptyCard("记录暂不可用","返回列表重新查找，或从草稿继续。") }
                    else DetailScreen(vm,e,snapshot,onAttachment)
                }
                route=="home" -> HomeScreen(vm,snapshot) { quick=true }
                route=="orgs" -> EntryList(vm,snapshot,Kind.ORGANIZATION)
                route=="customers" -> EntryList(vm,snapshot,Kind.CUSTOMER)
                route=="activities" -> EntryList(vm,snapshot,Kind.ACTIVITY)
                route=="tasks" -> TaskList(vm,snapshot)
                route=="search" -> SearchScreen(vm,snapshot)
                route=="settings" -> SettingsScreen(vm,snapshot,onNotifications)
                route=="export" -> ExportScreen(vm,snapshot)
                route=="backup" -> BackupScreen(vm,snapshot)
            }
            }
        }
    }
    if(quick) AlertDialog(onDismissRequest={quick=false},title={Text("记一笔")},text={Column {
        listOf("单位拜访","客户沟通","临时速记").forEach { label -> TextButton(onClick={quick=false;vm.create(Kind.INTERACTION); if(label=="临时速记") vm.editor?.entry?.let {vm.change(it.copy(fields=it.fields+("mode" to "临时速记")))}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("quick_$label")) {Text(label)} }
    }},confirmButton={TextButton(onClick={quick=false}){Text("取消")}})
    if(cancelEditorActivity) AlertDialog(onDismissRequest={cancelEditorActivity=false},title={Text("取消活动，保留历史")},text={Text("活动提醒将撤销。未完成筹备待办要一起取消还是保留？")},confirmButton={TextButton(onClick={cancelEditorActivity=false;vm.save(true)}){Text("同时取消待办")}},dismissButton={TextButton(onClick={cancelEditorActivity=false;vm.save(false)}){Text("保留待办")}})
    attachmentToRemove?.let { a -> AlertDialog(onDismissRequest={attachmentToRemove=null},title={Text("移除此附件")},text={Text("只移除当前记录中的“${a.name}”。正文、其他记录和其他附件会保留；移除立即生效。")},confirmButton={TextButton(onClick={attachmentToRemove=null;vm.action{vm.repository.removeAttachment(a.id)}}){Text("确认移除")}},dismissButton={TextButton(onClick={attachmentToRemove=null}){Text("取消")}}) }
}

@Composable fun BackTitle(title:String,onBack:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"返回")}},actions=actions,colors=TopAppBarDefaults.topAppBarColors(containerColor=Canvas))
}
@Composable fun SectionTitle(title:String,action:String?=null,onAction:()->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
        if(action!=null) TextButton(onClick=onAction){Text(action)}
    }
}
@Composable fun EmptyCard(title:String,description:String) {
    Surface(shape=RoundedCornerShape(14.dp),color=Color.White,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.BookmarkBorder,null,tint=Green)
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(description,color=Muted,style=MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable fun Badge(text:String) { if(text.isNotBlank()) Surface(color=Mint,shape=RoundedCornerShape(6.dp)) { Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),color=Brand,style=MaterialTheme.typography.labelMedium) } }
@Composable fun EntryCard(entry:Entry,snapshot:Snapshot,onClick:()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(14.dp),color=Color.White,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("entry_${entry.id}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                Surface(color=Mint,shape=RoundedCornerShape(10.dp)){Icon(kindIcon(entry.kind),null,Modifier.padding(10.dp).size(22.dp),tint=Green)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(entry.title.ifBlank { "沟通记录" },style=MaterialTheme.typography.titleMedium)
                    val subtitle=when(entry.kind){
                        Kind.ORGANIZATION -> listOf(entry["type"],entry["area"]).filter{it.isNotBlank()}.joinToString(" · ")
                        Kind.CUSTOMER -> listOf(entry["stage"],entry["tags"].replace('\n','、')).filter{it.isNotBlank()}.joinToString(" · ")
                        Kind.ACTIVITY -> "${entry["start"]}  ${entry["location"]}"
                        Kind.TASK -> "${TimeRules.taskBucket(entry)} · ${entry["date"].ifBlank{"未设日期"}} ${entry["time"]}"
                        Kind.INTERACTION -> TimeRules.formatDateTime(entry.occurredAt)+" · "+(snapshot.entries.find{it.id==entry.parentId}?.title ?: "临时未关联")
                        else -> listOf(entry["role"],entry["relationship"],entry["phone"]).filter{it.isNotBlank()}.joinToString(" · ")
                    }
                    if(subtitle.isNotBlank()) Text(subtitle,color=Muted,style=MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(20.dp))
            }
            if(entry.kind==Kind.INTERACTION) Text(entry["content"],maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            if(entry.archived) Badge("已归档") else if(entry.kind==Kind.ORGANIZATION) Badge(entry["stage"]) else if(entry.kind==Kind.ACTIVITY) Badge(entry["status"])
        }
    }
}

@Composable private fun HomeScreen(vm:WorkbookViewModel,s:Snapshot,onQuick:()->Unit) {
    var discard by remember { mutableStateOf<Draft?>(null) }
    val tasks=s.entries.filter{it.kind==Kind.TASK}
    val today=tasks.filter{TimeRules.taskBucket(it)=="今日"}
    val overdue=tasks.filter{TimeRules.taskBucket(it)=="逾期"}
    val visits=s.entries.filter{it.kind==Kind.INTERACTION}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("home_scroll"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(BuildConfig.FLAVOR=="qa") Badge("验收空间 · 仅使用虚构资料")
        Text(TimeRules.today().format(DateTimeFormatter.ofPattern("M月d日 EEEE",java.util.Locale.CHINA)),color=Muted,style=MaterialTheme.typography.bodySmall)
        Text("把每次沟通，\n接着做好。",style=MaterialTheme.typography.headlineLarge,color=Brand)
        Text("记录重要的事，让下一步更清楚。",color=Muted)
        Surface(color=Green,shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("今日待办",color=Color.White.copy(alpha=.85f)); Text("${today.size}",style=MaterialTheme.typography.headlineLarge,color=Color.White,modifier=Modifier.testTag("today_count")) }
                    Column(Modifier.weight(1f)) { Text("逾期事项",color=Color.White.copy(alpha=.85f)); Text("${overdue.size}",style=MaterialTheme.typography.headlineLarge,color=Color.White,modifier=Modifier.testTag("overdue_count")) }
                    Column(Modifier.weight(1f)) { Text("拜访 / 沟通",color=Color.White.copy(alpha=.85f)); Text("${visits.size}",style=MaterialTheme.typography.headlineLarge,color=Color.White) }
                }
                Button(onClick=onQuick,colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Brand),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("quick_record")) {Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("记一笔")}
            }
        }
        SectionTitle("需要跟进","全部待办") {vm.navigate("tasks")}
        if(today.isEmpty()&&overdue.isEmpty()) EmptyCard("今天从容安排","未来事项和未设日期的跟进，都可以在“全部待办”找到。")
        (overdue+today).take(3).forEach {EntryCard(it,s){vm.navigate("detail:${it.id}")}}
        if(s.drafts.isNotEmpty()) {
            SectionTitle("继续草稿")
            s.drafts.sortedByDescending{it.updatedAt}.forEach {d->Surface(onClick={vm.edit(d.entry,d)},shape=RoundedCornerShape(14.dp),color=Mint,modifier=Modifier.fillMaxWidth().testTag("draft_${d.id}")) {
                Column(Modifier.padding(16.dp)) {Text(d.entry.title.ifBlank { "未完成的${kindLabel(d.entry.kind)}" },fontWeight=FontWeight.SemiBold);Text(d.entry["content"].ifBlank{"点击继续填写"},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall);TextButton(onClick={discard=d}){Text("放弃此草稿")}}
            }}
        }
        val upcoming=s.entries.filter{it.kind==Kind.ACTIVITY&&it["status"] !in listOf("已完成","已取消")&&(TimeRules.parseDateTime(it["end"])?:0)>=System.currentTimeMillis()}.sortedBy{it["start"]}.take(2)
        if(upcoming.isNotEmpty()) {SectionTitle("临近活动","查看全部"){vm.navigate("activities",true)};upcoming.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
        SectionTitle("最近记录","查找历史"){vm.navigate("search")}
        if(visits.isEmpty()) EmptyCard("每一次交流，都值得记下","从“记一笔”开始。也可以先建单位或客户，再记录沟通。")
        visits.sortedByDescending{it.occurredAt}.take(5).forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
        Text("仅保存在本机 · 可离线使用",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=12.dp))
    }
    discard?.let { d->AlertDialog(onDismissRequest={discard=null},title={Text("放弃此草稿")},text={Text("尚未提交的填写内容将被移除。如果在编辑已有记录，正式记录仍保留。")},confirmButton={TextButton(onClick={discard=null;vm.discard(d)}){Text("确认放弃")}},dismissButton={TextButton(onClick={discard=null}){Text("继续保留")}}) }
}

@Composable fun ChoiceFilter(label:String,choices:List<String>,value:String,onValue:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick={open=true},modifier=Modifier.heightIn(min=48.dp).testTag("filter_$label")) {Text(if(value.isBlank()) label else value);Icon(Icons.Outlined.ExpandMore,null)}
    if(open) AlertDialog(onDismissRequest={open=false},title={Text(label)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        (listOf("全部")+choices).distinct().forEach { choice->TextButton(onClick={onValue(if(choice=="全部") "" else choice);open=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(choice)} }
    }},confirmButton={TextButton(onClick={open=false}){Text("关闭")}})
}

@Composable private fun EntryList(vm:WorkbookViewModel,s:Snapshot,kind:Kind) {
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var type by rememberSaveable(kind) { mutableStateOf("") }
    var stage by rememberSaveable(kind) { mutableStateOf("") }
    var date by rememberSaveable(kind) { mutableStateOf("") }
    var archived by rememberSaveable(kind) { mutableStateOf(false) }
    val matches=searchEntries(s,query,archived).filter{it.kind==kind}.filter{type.isBlank()||it["type"]==type||it["tags"].contains(type)}.filter{stage.isBlank()||it["stage"]==stage||it["status"]==stage}.filter{date.isBlank()||it["start"].startsWith(date)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("list_scroll"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SectionTitle(when(kind){Kind.ORGANIZATION->"合作单位";Kind.CUSTOMER->"客户与家庭";else->"活动安排"},"新增${kindLabel(kind)}"){vm.create(kind)}
        OutlinedTextField(query,{query=it},label={Text("搜索名称、联系人、电话或内容")},leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth().testTag("list_search"),singleLine=true)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ChoiceFilter(if(kind==Kind.CUSTOMER) "需求标签" else "类型",if(kind==Kind.CUSTOMER) vm.tags else s.entries.filter{it.kind==kind}.map{it["type"]}.filter{it.isNotBlank()}.distinct(),type,{type=it})
            ChoiceFilter("阶段",s.entries.filter{it.kind==kind}.map{it[if(kind==Kind.ACTIVITY)"status" else "stage"]}.filter{it.isNotBlank()}.distinct(),stage,{stage=it})
        }
        if(kind==Kind.ACTIVITY) OutlinedTextField(date,{date=it},label={Text("按日期查看（yyyy-MM-dd，可留空）")},modifier=Modifier.fillMaxWidth().testTag("activity_date_filter"),singleLine=true)
        else Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
        Text("${matches.size} 个${kindLabel(kind)}",color=Muted,style=MaterialTheme.typography.bodySmall)
        if(matches.isEmpty()) EmptyCard(if(query.isBlank()) "还没有${kindLabel(kind)}" else "没有找到匹配内容",if(query.isBlank()) "先补充最少信息，其余可以以后再完善。" else "试试姓名、电话、业务标签，或包含已归档。")
        matches.sortedByDescending{it.updatedAt}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
    }
}

fun searchEntries(s:Snapshot,query:String,includeArchived:Boolean):List<Entry> {
    val q=query.trim()
    val directly=s.entries.filter{q.isBlank()||it.title.contains(q,true)||it.fields.values.any{v->v.contains(q,true)}}.map{it.id}.toSet()
    val parentMatches=s.entries.filter{it.id in directly}.mapNotNull{it.parentId}.toSet()
    return s.entries.filter{(includeArchived||!it.archived)&&(q.isBlank()||it.id in directly||it.id in parentMatches)}
}
@Composable private fun SearchScreen(vm:WorkbookViewModel,s:Snapshot) {
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)}
    var dateFrom by rememberSaveable{mutableStateOf("")};var dateTo by rememberSaveable{mutableStateOf("")}
    var tag by rememberSaveable{mutableStateOf("")}
    val results=searchEntries(s,query,archived).filter { e->
        val d=Instant.ofEpochMilli(e.occurredAt).atZone(TimeRules.zone).toLocalDate().toString()
        (tag.isBlank()||e["tags"].contains(tag)||s.entries.find{it.id==e.parentId}?.get("tags")?.contains(tag)==true)&&
            (e.kind!=Kind.INTERACTION || ((dateFrom.isBlank()||d>=dateFrom)&&(dateTo.isBlank()||d<=dateTo)))
    }
    Column(Modifier.fillMaxSize()) {BackTitle("查找历史",vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query,{query=it},label={Text("单位、客户、电话、标签、沟通内容")},modifier=Modifier.fillMaxWidth().testTag("global_search"))
            ChoiceFilter("业务标签",vm.tags,tag,{tag=it})
            OutlinedTextField(dateFrom,{dateFrom=it},label={Text("记录开始日期 yyyy-MM-dd")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(dateTo,{dateTo=it},label={Text("记录结束日期 yyyy-MM-dd")},modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
            listOf(Kind.ORGANIZATION,Kind.CUSTOMER,Kind.ACTIVITY,Kind.INTERACTION).forEach {kind->
                val group=results.filter{it.kind==kind};SectionTitle("${kindLabel(kind)} · ${group.size}")
                group.sortedByDescending{it.occurredAt}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
            }
            if(results.none{it.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER,Kind.ACTIVITY,Kind.INTERACTION)}) EmptyCard("没有找到匹配内容","请换一个关键词或扩大日期范围。")
        }
    }
}

@Composable private fun TaskList(vm:WorkbookViewModel,s:Snapshot) {
    var bucket by rememberSaveable {mutableStateOf("今日")}
    Column(Modifier.fillMaxSize()) {BackTitle("全部待办",vm::back,actions={TextButton(onClick={vm.create(Kind.TASK)}){Text("新增")}})
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("今日","未来","逾期","未设日期","已完成","已取消").forEach{b->FilterChip(selected=bucket==b,onClick={bucket=b},label={Text(b)},modifier=Modifier.testTag("bucket_$b"))}
            }
            val tasks=s.entries.filter{it.kind==Kind.TASK&&TimeRules.taskBucket(it)==bucket}.sortedBy{it["date"]+it["time"]}
            Text("${tasks.size} 项",color=Muted,modifier=Modifier.testTag("task_count"))
            if(tasks.isEmpty()) EmptyCard("没有${bucket}事项","新增跟进时可只记标题，日期和提醒可以稍后补充。")
            tasks.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
        }
    }
}

@Composable private fun DetailScreen(vm:WorkbookViewModel,e:Entry,s:Snapshot,onAttachment:(Attachment)->Unit) {
    val context=LocalContext.current
    var cancel by remember {mutableStateOf(false)}
    var archiveConfirm by remember{mutableStateOf(false)}
    var export by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        BackTitle(kindLabel(e.kind),vm::back,actions={TextButton(onClick={vm.edit(e)},modifier=Modifier.testTag("edit_entry")){Text("编辑")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("detail_scroll"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(e.title,style=MaterialTheme.typography.headlineMedium,color=Brand)
            if(e.archived) Badge("已归档 · 历史保留")
            val parent=s.entries.find{it.id==e.parentId}
            if(parent!=null) TextButton(onClick={vm.navigate("detail:${parent.id}")}){Text("关联${kindLabel(parent.kind)}：${parent.title}")}
            if(e.kind==Kind.INTERACTION) {
                Text(TimeRules.formatDateTime(e.occurredAt),color=Muted)
                if(parent==null) Badge("临时未关联 · 编辑可补充对象")
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(e.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)) {
                    Button(onClick={vm.create(Kind.INTERACTION,e)},modifier=Modifier.testTag("add_interaction")){Icon(Icons.Outlined.Add,null);Text(if(e.kind==Kind.ORGANIZATION) "记一次拜访" else "记一次沟通")}
                    OutlinedButton(onClick={vm.create(Kind.CONTACT,e)},modifier=Modifier.testTag("add_contact")){Text("添加联系人")}
                    if(e.kind==Kind.CUSTOMER) OutlinedButton(onClick={vm.create(Kind.RECIPIENT,e)},modifier=Modifier.testTag("add_recipient")){Text("添加服务对象")}
                }
                if(e.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER,Kind.ACTIVITY)) OutlinedButton(onClick={vm.create(Kind.TASK,e)},modifier=Modifier.testTag("add_task")){Text(if(e.kind==Kind.ACTIVITY)"添加筹备待办" else "添加跟进")}
                if(e.kind==Kind.TASK) {
                    if(e["status"]!="已完成") Button(onClick={vm.taskStatus(e,"已完成")},modifier=Modifier.testTag("complete_task")){Text("完成跟进")}
                    if(e["status"]!="未完成") OutlinedButton(onClick={vm.taskStatus(e,"未完成")}){Text("重新打开")}
                    if(e["status"]=="未完成") OutlinedButton(onClick={vm.taskStatus(e,"已取消")}){Text("取消跟进")}
                    TextButton(onClick={vm.edit(e)}){Text("改期 / 完成备注")}
                }
                if(e.kind==Kind.ACTIVITY) {
                    if(e["status"]!="已取消") OutlinedButton(onClick={cancel=true}){Text("取消活动")}
                    OutlinedButton(onClick={vm.create(Kind.CUSTOMER);vm.editor?.entry?.let {vm.change(it.copy(fields=it.fields+mapOf("linkedActivity" to e.id,"source" to "活动：${e.title}")))}}){Text("新建来源客户")}
                }
                if(e.kind==Kind.CONTACT && e["phone"].isNotBlank()) OutlinedButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(e["phone"]))))}.onFailure{vm.message="没有可用的拨号应用"}}){Icon(Icons.Outlined.Phone,null);Text("打开拨号")}
            }
            val keys=e.fields.keys.filter{it !in listOf("occurredDate","occurredTime")&&e[it].isNotBlank()}
            if(keys.isNotEmpty()) Surface(color=Color.White,shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    keys.forEach {key->Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(fieldLabel(key),style=MaterialTheme.typography.labelMedium,color=Muted)
                        val value=e[key]
                        Text(if(key in listOf("linkedOrganization","linkedActivity","organizationId")) s.entries.find{it.id==value}?.title?:"未找到关联对象" else if(value=="true")"已开启" else if(value=="false")"未开启" else value,style=MaterialTheme.typography.bodyLarge)
                    }}
                }
            }
            if(e.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)) {
                SectionTitle("联系人")
                val contacts=s.entries.filter{it.kind==Kind.CONTACT&&it.parentId==e.id}
                if(contacts.isEmpty()) Text("尚未添加联系人",color=Muted)
                contacts.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
                if(e.kind==Kind.CUSTOMER) {SectionTitle("服务对象");val recipients=s.entries.filter{it.kind==Kind.RECIPIENT&&it.parentId==e.id};if(recipients.isEmpty())Text("按需填写，器具和培训咨询不必填写",color=Muted);recipients.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
                SectionTitle(if(e.kind==Kind.ORGANIZATION)"拜访时间线" else "沟通时间线")
                val interactions=s.entries.filter{it.kind==Kind.INTERACTION&&it.parentId==e.id}.sortedByDescending{it.occurredAt}
                if(interactions.isEmpty()) Text("每次沟通独立记录，不会覆盖历史。",color=Muted)
                interactions.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
            }
            val tasks=s.entries.filter{it.kind==Kind.TASK&&(it.parentId==e.id||it.sourceId==e.id)}
            if(tasks.isNotEmpty()){SectionTitle(if(e.kind==Kind.ACTIVITY)"筹备清单" else "关联跟进");tasks.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
            if(e.kind==Kind.TASK&&e.sourceId!=null) s.entries.find{it.id==e.sourceId}?.let{source->TextButton(onClick={vm.navigate("detail:${source.id}")}){Text("查看来源记录：${source.title}")}}
            val files=s.attachments.filter{it.ownerId==e.id}
            if(files.isNotEmpty()) {SectionTitle("附件");files.forEach{a->OutlinedButton(onClick={onAttachment(a)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.AttachFile,null);Text(a.name,modifier=Modifier.weight(1f))}}}
            if(e.kind in listOf(Kind.INTERACTION,Kind.ACTIVITY)) OutlinedButton(onClick={export=true},modifier=Modifier.fillMaxWidth().testTag("export_summary")){Text("预览 / 导出摘要")}
            if(e.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)) TextButton(onClick={archiveConfirm=true}){Text(if(e.archived)"恢复到列表" else "归档${kindLabel(e.kind)}")}
            Text("创建于 ${TimeRules.formatDateTime(e.createdAt)}\n更新于 ${TimeRules.formatDateTime(e.updatedAt)}",color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
    if(cancel) AlertDialog(onDismissRequest={cancel=false},title={Text("取消活动，保留历史")},text={Text("活动提醒将撤销。请选择未完成筹备待办的处理方式。")},confirmButton={TextButton(onClick={cancel=false;vm.cancelActivity(e,true)}){Text("同时取消待办")}},dismissButton={TextButton(onClick={cancel=false;vm.cancelActivity(e,false)}){Text("保留待办")}})
    if(archiveConfirm) AlertDialog(onDismissRequest={archiveConfirm=false},title={Text(if(e.archived)"恢复到列表" else "归档并保留历史")},text={Text("历史记录和附件都会保留。归档不会取消尚未完成的跟进，请在全部待办中单独处理。")},confirmButton={TextButton(onClick={archiveConfirm=false;vm.archive(e)}){Text("确认")}},dismissButton={TextButton(onClick={archiveConfirm=false}){Text("返回")}})
    if(export) SummaryDialog(vm,e,s){export=false}
}
