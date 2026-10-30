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
    var cancelEditorActivity by remember{mutableStateOf(false)}
    var remove by remember{mutableStateOf<Attachment?>(null)}
    BackHandler(enabled=route!="home"){vm.back()}
    val root=route in listOf("home","orgs","customers","activities")
    Scaffold(containerColor=Canvas,
        topBar={if(root) TopAppBar(title={Text(if(route=="home")"今天" else vm.profile["appName"].ifBlank{"康养工作簿"},style=MaterialTheme.typography.titleLarge)},
            actions={IconButton(onClick={vm.navigate("search")},modifier=Modifier.testTag("search_all")){Icon(Icons.Outlined.Search,"搜索全部")};IconButton(onClick={vm.navigate("settings")},modifier=Modifier.testTag("settings")){Icon(Icons.Outlined.AccountCircle,"本人资料与设置")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Canvas))},
        bottomBar={if(root) NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
            listOf(Triple("home","工作台",Icons.Outlined.Dashboard),Triple("orgs","单位",Icons.Outlined.Business),Triple("customers","客户",Icons.Outlined.People),Triple("activities","活动",Icons.Outlined.EventNote)).forEach{(dest,label,icon)->
                NavigationBarItem(selected=route==dest,onClick={vm.navigate(dest,true)},icon={Icon(icon,null)},label={Text(label)},modifier=Modifier.testTag("nav_$dest"))
            }
        }}) {padding->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            vm.message?.let{value->Surface(color=Mint){Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Brand);IconButton(onClick={vm.message=null}){Icon(Icons.Outlined.Close,"关闭提示")}}}}
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(vm.uiEpoch) {
                    val browseState=rememberSaveableStateHolder()
                    val page: @Composable () -> Unit = {
                        when {
                            route=="editor"->vm.editor?.let{e->
                                if(e.entry.kind==Kind.INTERACTION) NoteEditorScreen(vm,e,s,onPhoto,onFile,onAttachment,{remove=it},onNotifications)
                                else EntryEditor(e.entry,s.entries,s.attachments.filter{it.ownerId==e.entry.id},vm.tags,e.status,e.saving,vm::change,
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
                    // Retain only browsing pages, never a completed or replaced editing session.
                    if(route in setOf("home","orgs","customers","activities","tasks","drafts","search")) {
                        browseState.SaveableStateProvider(route) { page() }
                    } else page()
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
    Surface(onClick=onClick,shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("entry_${entry.id}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
                snapshot.entries.filter{it.kind==Kind.CONTACT&&it.parentId==entry.id}.sortedByDescending{it["primary"]=="true"}.firstOrNull()?.let{Text(listOf(it.title,it["phone"]).filter{v->v.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)}
                snapshot.entries.filter{it.kind==Kind.INTERACTION&&it.parentId==entry.id}.maxByOrNull{it.occurredAt}?.let{Text(it["content"].ifBlank{noteTitle(it,snapshot)},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)}
                snapshot.entries.filter{it.kind==Kind.TASK&&it.parentId==entry.id&&it["status"] !in listOf("已完成","已取消")}.minByOrNull{TimeRules.taskDue(it)?:Long.MAX_VALUE}?.let{Text("下一步：${it.title}",color=Green,style=MaterialTheme.typography.bodySmall)}
            }
            if(entry.archived)Badge("已归档") else if(entry.kind==Kind.ACTIVITY)Badge(entry["status"])
        }
    }
}
@Composable fun RecordRow(e:Entry,s:Snapshot,onClick:()->Unit) {
    Surface(onClick=onClick,shape=AppShapes.card,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("entry_${e.id}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(noteTitle(e,s),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            val body=if(e.title.isBlank())e["content"].substringAfter('\n',"") else e["content"]
            if(body.isNotBlank())Text(body,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text(listOf(occurrenceLabel(e),s.find(e.parentId)?.title.orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),color=Muted,style=MaterialTheme.typography.bodySmall)
            val files=s.attachments.count{it.ownerId==e.id};if(files>0)Text("$files 个附件",color=Muted,style=MaterialTheme.typography.bodySmall)
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
    val visits=s.entries.filter{it.kind==Kind.INTERACTION}
    val tasks=s.entries.filter{it.kind==Kind.TASK&&TimeRules.taskBucket(it,vm.clockNow) in listOf("逾期","今日")}.sortedBy{TimeRules.taskDue(it)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).testTag("home_scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(TimeRules.today(vm.clockNow).format(DateTimeFormatter.ofPattern("M月d日 EEEE",java.util.Locale.CHINA)),color=Muted,style=MaterialTheme.typography.bodyMedium)
        PrimaryAction("记一笔",{vm.create(Kind.INTERACTION)},Modifier.fillMaxWidth().testTag("quick_record"))
        Row(verticalAlignment=Alignment.CenterVertically) {Text("需要跟进",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton(onClick={vm.navigate("tasks")},modifier=Modifier.testTag("tasks_all")){Text("全部待办")}}
        if(tasks.isEmpty())Text("今天暂无待办，随时记下新进展。",color=Muted,style=MaterialTheme.typography.bodyMedium)
        tasks.take(2).forEach{FollowUpRow(it,s,vm.clockNow,{vm.navigate("detail:${it.id}")},{vm.taskStatus(it,"已完成")},{vm.edit(it)})}
        SectionTitle("最近编辑","查找历史"){vm.navigate("search")}
        if(visits.isEmpty())Text("从“记一笔”开始，只写一句也会保存。",color=Muted)
        visits.sortedByDescending{it.updatedAt}.take(5).forEach{RecordRow(it,s){vm.navigate("detail:${it.id}")}}
        if(visits.isNotEmpty())Text("卡片时间为事情发生时间",style=MaterialTheme.typography.bodySmall,color=Muted)
        val drafts=s.drafts.filter{!it.isNoteBuffer && (it.entry.title.isNotBlank()||it.entry["content"].isNotBlank()||it.entry["plan"].isNotBlank()||s.attachments.any{a->a.ownerId==it.entry.id})}
        if(drafts.isNotEmpty()){SectionTitle("待整理的填写","查看 ${drafts.size} 份"){vm.navigate("drafts")};drafts.take(2).forEach{DraftRow(vm,it,s)}}
        val activities=s.entries.filter{it.kind==Kind.ACTIVITY&&it["status"] !in listOf("已完成","已取消")}.sortedBy{TimeRules.parseDateTime(it["start"])?:Long.MAX_VALUE}.take(2)
        if(activities.isNotEmpty()){SectionTitle("活动安排","全部活动"){vm.navigate("activities",true)};activities.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
        Text(if(BuildConfig.FLAVOR=="qa")"验收空间 · 虚构资料" else "仅保存在本机 · 可离线使用",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=16.dp))
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
    Column{BackTitle("待整理的填写",vm::back);Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        s.drafts.filter{!it.isNoteBuffer || it.recoveryReason.isNotBlank()}.forEach{DraftRow(vm,it,s);TextButton(onClick={discard=it}){Text("放弃这份填写")}}
    }}
    discard?.let{d->ConfirmDialog("放弃这份填写","正式记录仍然保留，尚未提交的填写内容将移除。",{discard=null;vm.discard(d)},{discard=null},"确认放弃",destructive=true)}
}
@Composable fun ChoiceFilter(label:String,choices:List<String>,value:String,onValue:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    SecondaryAction(if(value.isBlank())label else value,{open=true},Modifier.testTag("filter_$label"))
    if(open)AppBottomSheet(label,{open=false}){(listOf("全部")+choices).distinct().forEach{choice->TextButton(onClick={onValue(if(choice=="全部")"" else choice);open=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(choice)}}}
}
@Composable private fun EntryList(vm:WorkbookViewModel,s:Snapshot,kind:Kind) {
    var query by rememberSaveable(kind){mutableStateOf("")};var type by rememberSaveable(kind){mutableStateOf("")};var stage by rememberSaveable(kind){mutableStateOf("")}
    var date by rememberSaveable(kind){mutableStateOf("")};var archived by rememberSaveable(kind){mutableStateOf(false)};var filters by remember{mutableStateOf(false)}
    val matches=searchEntries(s,query,archived).filter{it.kind==kind&&(type.isBlank()||it["type"]==type||it["tags"].contains(type))&&(stage.isBlank()||it["stage"]==stage||it["status"]==stage)&&(date.isBlank()||it["start"].startsWith(date))}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("list_scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionTitle(when(kind){Kind.ORGANIZATION->"合作单位";Kind.CUSTOMER->"客户与家庭";else->"活动安排"},"新增${kindLabel(kind)}"){vm.create(kind)}
        AppTextField(query,{query=it},if(kind==Kind.ACTIVITY)"搜索活动名称、地点或方案" else "搜索名称、联系人、电话或内容",Modifier.fillMaxWidth().testTag("list_search"),singleLine=true)
        Row(verticalAlignment=Alignment.CenterVertically){Text("${matches.size} 个${kindLabel(kind)}",color=Muted,modifier=Modifier.weight(1f));TextButton(onClick={filters=true},modifier=Modifier.testTag("list_filters")){Text("筛选")}}
        if(matches.isEmpty())EmptyCard("没有匹配的${kindLabel(kind)}","可以新建，或调整关键词和筛选范围。")
        if(kind==Kind.ACTIVITY) {
            listOf("已安排","时间待定","已结束").forEach{group->
                val rows=matches.filter{when(group){"已结束"->it["status"] in listOf("已完成","已取消");"时间待定"->it["start"].isBlank()&&it["status"] !in listOf("已完成","已取消");else->it["start"].isNotBlank()&&it["status"] !in listOf("已完成","已取消")}}
                if(rows.isNotEmpty()){SectionTitle(group);rows.sortedBy{it["start"]}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
            }
        } else matches.sortedByDescending{it.updatedAt}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
    }
    if(filters)AppBottomSheet("筛选${kindLabel(kind)}",{filters=false}) {
        ChoiceFilter(if(kind==Kind.CUSTOMER)"需求标签" else "类型",if(kind==Kind.CUSTOMER)vm.tags else s.entries.filter{it.kind==kind}.map{it["type"]}.filter{it.isNotBlank()}.distinct(),type,{type=it})
        ChoiceFilter("阶段",s.entries.filter{it.kind==kind}.map{it[if(kind==Kind.ACTIVITY)"status" else "stage"]}.filter{it.isNotBlank()}.distinct(),stage,{stage=it})
        if(kind==Kind.ACTIVITY)DateField("活动日期",date,{date=it},Modifier.fillMaxWidth().testTag("activity_date_filter"))
        else Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
        PrimaryAction("查看结果",{filters=false},Modifier.fillMaxWidth())
    }
}
fun searchEntries(s:Snapshot,query:String,includeArchived:Boolean):List<Entry> {
    val q=query.trim()
    val fileOwners=s.attachments.filter{it.name.contains(q,true)}.map{it.ownerId}.toSet()
    val direct=s.entries.filter{q.isBlank()||it.id in fileOwners||it.title.contains(q,true)||it.fields.filterKeys{k->!k.startsWith("_")}.values.any{v->v.contains(q,true)}}.map{it.id}.toSet()
    val parents=s.entries.filter{it.id in direct}.mapNotNull{it.parentId}.toSet()
    return s.entries.filter{(includeArchived||!it.archived)&&(q.isBlank()||it.id in direct||it.id in parents)}
}
@Composable private fun SearchScreen(vm:WorkbookViewModel,s:Snapshot) {
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)}
    var from by rememberSaveable{mutableStateOf("")};var to by rememberSaveable{mutableStateOf("")};var tag by rememberSaveable{mutableStateOf("")}
    var objectId by rememberSaveable{mutableStateOf<String?>(null)};var objectPicker by remember{mutableStateOf(false)};var filters by remember{mutableStateOf(false)}
    var type by rememberSaveable{mutableStateOf("")}
    val valid=from.isBlank()||to.isBlank()||from<=to
    val results=searchEntries(s,query,archived).filter{e->val d=TimeRules.formatDate(e.occurredAt)
        valid&&(tag.isBlank()||e["tags"].contains(tag)||s.find(e.parentId)?.get("tags")?.contains(tag)==true)&&
        (type.isBlank()||kindLabel(e.kind)==type)&&(objectId==null||e.parentId==objectId||e.id==objectId)&&
        (e.kind!=Kind.INTERACTION||((from.isBlank()||d>=from)&&(to.isBlank()||d<=to)))}
    Column(Modifier.fillMaxSize()){BackTitle("查找历史",vm::back,actions={TextButton(onClick={filters=true},modifier=Modifier.testTag("search_filters")){Text("筛选")}})
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AppTextField(query,{query=it},"搜索正文、名称、电话或附件名",Modifier.fillMaxWidth().testTag("global_search"))
            if(!valid)Text("结束日期不能早于开始日期",color=MaterialTheme.colorScheme.error)
            if(query.isBlank())Text("最近记录",color=Muted)
            listOf(Kind.INTERACTION,Kind.ORGANIZATION,Kind.CUSTOMER,Kind.ACTIVITY,Kind.TASK,Kind.CONTACT,Kind.RECIPIENT).forEach{kind->
                val group=results.filter{it.kind==kind}.sortedByDescending{it.occurredAt}.let{if(query.isBlank())it.take(10) else it}
                if(group.isNotEmpty()){SectionTitle("${kindLabel(kind)} · ${group.size}");group.forEach{e->EntryCard(e,s){vm.navigate("detail:${e.id}")}
                    if(query.isNotBlank()&&e.kind==Kind.INTERACTION){val p=e["content"].indexOf(query,ignoreCase=true);if(p>60)Text("…"+e["content"].substring((p-20).coerceAtLeast(0),(p+query.length+60).coerceAtMost(e["content"].length))+"…",color=Muted,style=MaterialTheme.typography.bodyMedium)}
                }}
            }
            if(results.isEmpty())Text("没有找到匹配内容",color=Muted)
        }
    }
    if(filters)AppBottomSheet("筛选范围",{filters=false}) {
        DateField("发生日期从",from,{from=it},Modifier.fillMaxWidth());DateField("至",to,{to=it},Modifier.fillMaxWidth())
        SecondaryAction(s.find(objectId)?.title ?: "全部对象",{objectPicker=true},Modifier.fillMaxWidth())
        ChoiceFilter("业务类型",listOf("沟通记录","单位","客户","活动","待办"),type,{type=it});ChoiceFilter("业务标签",vm.tags,tag,{tag=it})
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(archived,{archived=it});Text("包含已归档")}
        PrimaryAction("查看结果",{filters=false},Modifier.fillMaxWidth())
    }
    if(objectPicker)ObjectPickerPanel(s.entries.filter{it.kind in listOf(Kind.ORGANIZATION,Kind.CUSTOMER)},objectId,{objectId=it;objectPicker=false},{objectPicker=false},title="筛选对象",clearLabel="全部对象",contacts=s.entries.filter{it.kind==Kind.CONTACT})
}
@Composable private fun TaskList(vm:WorkbookViewModel,s:Snapshot,onNotifications:()->Unit) {
    var history by rememberSaveable{mutableStateOf(false)}
    var editingId by rememberSaveable{mutableStateOf<String?>(null)}
    val groups=if(history)listOf("已完成","已取消") else listOf("逾期","今日","未来","未设日期")
    val grouped=groups.map { bucket -> bucket to s.entries.filter {
        it.kind==Kind.TASK && TimeRules.taskBucket(it,vm.clockNow)==bucket
    }.sortedBy { TimeRules.taskDue(it) ?: Long.MAX_VALUE } }.filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize()){BackTitle("全部待办",vm::back,actions={TextButton(onClick={vm.create(Kind.TASK)}){Text("新增")}})
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("task_list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="history-filter") {
                Row{FilterChip(!history,{history=false},label={Text("未完成")});Spacer(Modifier.width(8.dp));FilterChip(history,{history=true},label={Text("已完成 / 取消")})}
            }
            grouped.forEach { (bucket,rows) ->
                item(key="group-$bucket") { SectionTitle("$bucket · ${rows.size}") }
                items(rows,key={"task-${it.id}"}) { task ->
                    FollowUpRow(task,s,vm.clockNow,{vm.navigate("detail:${task.id}")},{vm.taskStatus(task,"已完成")},{editingId=task.id},
                        Modifier.animateItem(fadeInSpec=tween(AppMotion.Quick),fadeOutSpec=tween(AppMotion.Quick),placementSpec=tween(AppMotion.Change)))
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
    val tasks=s.entries.filter{it.kind==Kind.TASK&&(it.parentId==e.id||it.sourceId==e.id)}
    Column(Modifier.fillMaxSize()){BackTitle(kindLabel(e.kind),vm::back,actions={TextButton(onClick={vm.edit(e)},modifier=Modifier.testTag("edit_entry")){Text("编辑")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("detail_scroll"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(e.title,style=MaterialTheme.typography.headlineSmall,color=Brand)
            if(e.archived)Badge("已归档 · 历史保留")
            s.find(e.parentId)?.let{parent->TextButton(onClick={vm.navigate("detail:${parent.id}")}){Text("关联${kindLabel(parent.kind)}：${parent.title}")}}
            if(dossier) {
                Text(listOf(e["type"],e["area"],e["stage"]).filter{it.isNotBlank()}.joinToString(" · "),color=Muted)
                PrimaryAction("记一次沟通",{vm.create(Kind.INTERACTION,e)},Modifier.fillMaxWidth().testTag("add_interaction"))
                SectionTitle("后续跟进","添加跟进"){vm.create(Kind.TASK,e)}
                tasks.filter{it["status"] !in listOf("已完成","已取消")}.forEach{FollowUpRow(it,s,vm.clockNow,{vm.navigate("detail:${it.id}")},{vm.taskStatus(it,"已完成")},{taskEditId=it.id})}
                SectionTitle(if(e.kind==Kind.ORGANIZATION)"拜访时间线" else "沟通时间线")
                val notes=s.entries.filter{it.kind==Kind.INTERACTION&&it.parentId==e.id}.sortedByDescending{it.occurredAt}
                if(notes.isEmpty())Text("每次沟通单独保存，随时回来查看。",color=Muted)
                notes.forEach{RecordRow(it,s){vm.navigate("detail:${it.id}")}}
                SectionTitle("联系人","添加联系人"){vm.create(Kind.CONTACT,e)}
                s.entries.filter{it.kind==Kind.CONTACT&&it.parentId==e.id}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}
                if(e.kind==Kind.CUSTOMER){SectionTitle("服务对象","添加服务对象"){vm.create(Kind.RECIPIENT,e)};s.entries.filter{it.kind==Kind.RECIPIENT&&it.parentId==e.id}.forEach{EntryCard(it,s){vm.navigate("detail:${it.id}")}}}
            }
            if(e.kind==Kind.TASK) {
                Badge(TimeRules.taskBucket(e,vm.clockNow));Text("${e["date"].ifBlank{"未设日期"}} ${e["time"]}",color=Muted)
                if(e["status"] !in listOf("已完成","已取消"))PrimaryAction("完成跟进",{vm.taskStatus(e,"已完成")},Modifier.fillMaxWidth().testTag("complete_task"))
                SecondaryAction("修改跟进",{taskEditId=e.id},Modifier.fillMaxWidth().testTag("reschedule_task"))
                if(e["completionNote"].isNotBlank())Text("${if(e["status"]=="已完成")"完成备注" else "备注"}\n${e["completionNote"]}")
                s.find(e.sourceId)?.let{source->TextButton(onClick={vm.navigate("detail:${source.id}")}){Text("查看来源记录：${noteTitle(source,s)}")}}
            }
            if(e.kind==Kind.ACTIVITY) {
                Badge(e["status"]);Text(e["start"].ifBlank{"时间待定"}+e["end"].takeIf{it.isNotBlank()}?.let{" 至 $it"}.orEmpty(),color=Muted)
                listOf("location","goal","audience","plan","division","materials","precautions").filter{e[it].isNotBlank()}.forEach{Text("${fieldLabel(it)}\n${e[it]}")}
                SectionTitle("筹备清单","添加筹备待办"){vm.create(Kind.TASK,e)}
                tasks.forEach{FollowUpRow(it,s,vm.clockNow,{vm.navigate("detail:${it.id}")},{vm.taskStatus(it,"已完成")},{taskEditId=it.id})}
                if(e["status"]=="已完成"){SectionTitle("执行与复盘");listOf("actualCount","execution","review","followup").filter{e[it].isNotBlank()}.forEach{Text("${fieldLabel(it)}\n${e[it]}")}}
                SecondaryAction("新建来源客户",{vm.edit(Entry(kind=Kind.CUSTOMER,fields=mapOf("stage" to "新咨询","source" to "活动：${e.title}","linkedActivity" to e.id)))},Modifier.fillMaxWidth())
            }
            if(e.kind==Kind.CONTACT&&e["phone"].isNotBlank())SecondaryAction("打开拨号",{runCatching{context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(e["phone"]))))}.onFailure{vm.message="没有可用的拨号应用"}},Modifier.fillMaxWidth())
            if(e.kind in listOf(Kind.CONTACT,Kind.RECIPIENT,Kind.PROFILE)) e.fields.filterValues{it.isNotBlank()}.forEach{(k,v)->Text("${fieldLabel(k)}\n$v")}
            s.attachments.filter{it.ownerId==e.id}.forEach{AttachmentRow(it,onAttachment)}
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
            if(e.kind==Kind.ACTIVITY)SecondaryAction("预览 / 导出摘要",{export=true},Modifier.fillMaxWidth().testTag("export_summary"))
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
