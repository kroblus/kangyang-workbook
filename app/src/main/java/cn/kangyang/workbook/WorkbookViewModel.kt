package cn.kangyang.workbook

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.storage.BackupManager
import cn.kangyang.workbook.storage.PreparedRestore
import cn.kangyang.workbook.reminders.ReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

val DefaultTags = listOf("康养中心推介", "康复器具与耗材", "术后康复护理", "医院及居家护理", "长期照护师培训")
fun defaultProfile() = Entry(id="profile", kind=Kind.PROFILE, title="姜宏", fields=mapOf("role" to "养老顾问", "company" to "湖北省大千生活服务有限公司", "appName" to "康养工作簿", "tags" to DefaultTags.joinToString("\n")))
data class EditorSession(
    val draftId:String, val entry:Entry, val revision:Long=0,
    val status:String="开始填写后自动保存", val saving:Boolean=false,
    val generation:Long=0, val persistedRevision:Long=-1,
    val existed:Boolean=false, val occurrenceChosen:Boolean=false,
    val attachmentStatus:String?=null,
    val original:Entry?=null,
)

fun isStaleTaskDraft(draft:Draft,current:Entry?):Boolean = draft.entry.kind==Kind.TASK &&
    current!=null && draft.entry.updatedAt!=current.updatedAt

private data class TaskClockKey(val id:String,val date:String,val time:String,val status:String)

class WorkbookViewModel(application:Application, private val savedState:SavedStateHandle) : AndroidViewModel(application) {
    val repository=Repository.get(application)
    val snapshot=repository.state.stateIn(viewModelScope,SharingStarted.Eagerly,Snapshot())
    var route by mutableStateOf("home"); private set
    private val history=mutableListOf<String>()
    var editor by mutableStateOf<EditorSession?>(null); private set
    var message by mutableStateOf<String?>(null)
    var transitioning by mutableStateOf(false); private set
    var clockNow by mutableLongStateOf(TimeRules.now()); private set
    var pendingExport:ByteArray?=null
    private val writes=Mutex()
    private var debounce:Job?=null
    private var deadline:Job?=null
    private var clockJob:Job?=null
    private val operations=mutableSetOf<Job>()
    private val attachmentOperations=mutableMapOf<Job,String>()
    var uiEpoch by mutableLongStateOf(0L); private set
    private var foreground=false
    // Instrumentation can exercise a midnight without changing the emulator/system clock.
    internal var clockProvider:()->Long = TimeRules::now
    val profile get()=snapshot.value.entries.find { it.kind==Kind.PROFILE } ?: defaultProfile()
    val tags get()=profile["tags"].lines().filter { it.isNotBlank() }.ifEmpty { DefaultTags }

    init {
        viewModelScope.launch {
            try {
                repository.migrateLegacyDrafts()
                val id=savedState.get<String>("editingId")
                val data=repository.snapshot()
                if(id!=null) (data.find(id) ?: data.drafts.firstOrNull{it.entry.id==id}?.entry)?.let { edit(it) }
            } catch(e:Exception) { message="旧编辑内容仍保留：${e.message}" }
        }
        viewModelScope.launch {
            snapshot.map { data ->
                data.entries.asSequence().filter { it.kind==Kind.TASK }
                    .map { TaskClockKey(it.id,it["date"],it["time"],it["status"]) }.toSet()
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
                .collect { if(foreground) refreshClock() }
        }
    }
    private fun launchOperation(block:suspend ()->Unit):Job {
        val epoch=uiEpoch
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try { block() } catch(e:CancellationException) {throw e}
            catch(e:Exception) { if(epoch==uiEpoch) message=e.message ?: "操作未完成，请重试" }
        }
        operations.add(job); job.invokeOnCompletion { operations.remove(job) }; job.start();return job
    }
    fun action(block:suspend ()->Unit) { if(!transitioning) launchOperation(block) }
    private fun go(target:String,root:Boolean=false) {
        message=null
        if(root) history.clear() else if(route!=target) history.add(route)
        route=target
    }
    fun navigate(target:String,root:Boolean=false) {
        if(transitioning) return
        if(route=="editor") leaveEditor { go(target,root) } else go(target,root)
    }
    private fun pop() { route=if(history.isNotEmpty()) history.removeAt(history.lastIndex) else "home" }
    fun back() { if(!transitioning) { if(route=="editor") leaveEditor(::pop) else pop() } }
    fun edit(entry:Entry,draft:Draft?=null) {
        if(transitioning) return
        val epoch=uiEpoch
        action {
            val data=repository.snapshot()
            // Conflicting legacy drafts are opened only by choosing their recovery entry.
            val published=data.find(entry.id)
            val staleTaskDraft=data.drafts.any{it.entry.id==entry.id && isStaleTaskDraft(it,published)}
            // A legacy TASK draft has no per-field baseline. Replaying it against a newer TASK
            // would mistake stale dates/reminders for intentional edits. Keep it for inspection.
            val recovered=if(draft!=null) draft.takeUnless{isStaleTaskDraft(it,published)} else data.drafts.firstOrNull{
                it.entry.id==entry.id && it.recoveryReason.isBlank() && !isStaleTaskDraft(it,published)}
            val id=recovered?.id ?: UUID.randomUUID().toString()
            val generation=repository.generation
            val revision=if(entry.kind==Kind.INTERACTION) repository.beginNoteSession(entryId=entry.id,sessionId=id,expectedGeneration=generation) else recovered?.revision ?: 0L
            if(epoch!=uiEpoch) return@action
            val value=recovered?.entry ?: data.find(entry.id) ?: entry
            editor=EditorSession(id,value,maxOf(revision,recovered?.revision ?: 0),
                if(recovered?.recoveryReason?.isNotBlank()==true) "待检查：${recovered.recoveryReason}" else if(data.find(entry.id)!=null) "已保存" else "写下内容后自动保存",
                generation=generation,persistedRevision=if(data.find(entry.id)!=null && recovered==null) revision else -1,
                existed=data.find(entry.id)!=null,occurrenceChosen=data.find(entry.id)!=null || recovered!=null,original=data.find(entry.id))
            savedState["editingId"]=entry.id
            go("editor")
            if(staleTaskDraft && recovered==null) message="已打开当前跟进；旧填写仍保留在待整理中，可查看和复制。"
        }
    }
    fun create(kind:Kind,parent:Entry?=null) {
        val defaults=when(kind) {
            Kind.ORGANIZATION->mapOf("type" to "养老机构","stage" to "待联系","nature" to "暂不清楚")
            Kind.CUSTOMER->mapOf("stage" to "新咨询","urgency" to "普通")
            Kind.INTERACTION->mapOf("mode" to "面谈","certainty" to "讨论过")
            Kind.ACTIVITY->mapOf("status" to "筹备中","type" to "线下交流")
            Kind.TASK->mapOf("status" to "未完成","priority" to "普通")
            else->emptyMap()
        }
        edit(Entry(kind=kind,parentId=parent?.id,fields=defaults))
    }
    fun change(entry:Entry) {
        val current=editor ?: return
        if(current.saving || transitioning) return
        val chose=current.occurrenceChosen || current.entry.occurredAt!=entry.occurredAt
        val firstContent=entry.kind==Kind.INTERACTION && !chose && entry["content"].isNotBlank()
        val changed=if(firstContent) entry.copy(occurredAt=TimeRules.now()) else entry
        editor=current.copy(entry=changed,revision=current.revision+1,occurrenceChosen=chose||firstContent,
            status=if(entry.kind==Kind.INTERACTION) "正在保存…" else "正在保存草稿…")
        debounce?.cancel()
        debounce=viewModelScope.launch {delay(350);persistLatest()}
        // Continuous input must not postpone every commit indefinitely.
        if(deadline?.isActive!=true) deadline=viewModelScope.launch {delay(1500);persistLatest();deadline=null}
    }
    private suspend fun persist(session:EditorSession):Boolean = writes.withLock {
        if(session.generation!=repository.generation || transitioning) return@withLock false
        if(session.persistedRevision==session.revision) return@withLock true
        try {
            val saved=if(session.entry.kind==Kind.INTERACTION) {
                repository.saveNoteRevision(session.entry,session.draftId,session.revision,session.generation)
            } else {
                if(session.revision>0 && !repository.saveDraft(Draft(session.draftId,session.entry,session.revision),session.generation)) throw DataProblem("这份填写已发生变化，请重新打开后重试")
                null
            }
            if(editor?.draftId==session.draftId && editor?.revision==session.revision && session.generation==repository.generation) {
                editor=editor?.copy(entry=saved ?: session.entry,persistedRevision=session.revision,
                    existed=session.existed || saved!=null,
                    status=if(session.entry.kind!=Kind.INTERACTION) "草稿已保存" else if(saved!=null) "已保存" else "写下内容后自动保存")
            }
            true
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            if(editor?.draftId==session.draftId && editor?.revision==session.revision && session.generation==repository.generation) {
                editor=editor?.copy(status="未保存，点击重试")
                message=e.message ?: "未保存，请重试"
            }
            false
        }
    }
    private suspend fun persistLatest():Boolean { val current=editor ?: return true;return persist(current) }
    fun flushDraft() {
        if(transitioning) return
        debounce?.cancel();deadline?.cancel();deadline=null
        launchOperation {persistLatest()}
    }
    private suspend fun latestAfterAttachments(session:EditorSession):EditorSession? {
        val pending=attachmentOperations.filterValues{it==session.draftId}.keys.toList()
        pending.joinAll()
        val latest=editor?.takeIf{it.draftId==session.draftId && it.generation==session.generation} ?: return null
        // If an import failed while the user was waiting to leave, keep its feedback visible.
        // A subsequent explicit back is allowed once no import is in flight.
        if(pending.isNotEmpty() && latest.attachmentStatus?.startsWith("附件未导入")==true) {
            editor=latest.copy(saving=false);message=latest.attachmentStatus;return null
        }
        return latest
    }
    private fun leaveEditor(then:()->Unit) {
        val current=editor ?: run{then();return}
        if(current.saving) return
        editor=current.copy(saving=true)
        debounce?.cancel();deadline?.cancel();deadline=null
        action {
            val latest=latestAfterAttachments(current) ?: return@action
            if(persist(latest)) {
                if(latest.entry.kind==Kind.INTERACTION) repository.finishNoteSession(latest.draftId,latest.generation)
                editor=null;savedState.remove<String>("editingId");then()
            } else editor=editor?.copy(saving=false)
        }
    }
    fun completeNote() = leaveEditor(::pop)
    fun save(cancelActivityTasks:Boolean?=null) {
        val current=editor ?: return
        if(current.entry.kind==Kind.INTERACTION) {completeNote();return}
        if(current.saving || transitioning) return
        editor=current.copy(saving=true);debounce?.cancel();deadline?.cancel()
        action {
            val latest=latestAfterAttachments(current) ?: return@action
            try {
                val saved=if(latest.entry.kind==Kind.TASK && latest.original!=null) {
                    repository.saveTaskChanges(latest.entry,latest.original,latest.generation).also {repository.discardDraft(latest.draftId,latest.generation)}
                } else repository.save(latest.entry,latest.draftId,cancelActivityTasks,latest.generation)
                editor=null;savedState.remove<String>("editingId")
                val dest=if(saved.kind==Kind.PROFILE) "settings" else "detail:${saved.id}"
                if(history.lastOrNull()==dest) history.removeAt(history.lastIndex)
                route=dest;message="已保存"
            } catch(e:Exception) {
                editor=latest.copy(saving=false);persist(latest);message=e.message ?: "未能保存，请重试"
            }
        }
    }
    fun expectAttachment() {
        editor?.let {savedState["attachmentSession"]=it.draftId;savedState["attachmentGeneration"]=it.generation}
    }
    fun attachment(uri:Uri?) {
        if(uri==null || transitioning) return
        val current=editor ?: return
        val expected=savedState.remove<String>("attachmentSession")
        val generation=savedState.remove<Long>("attachmentGeneration")
        if(expected!=current.draftId || generation!=current.generation) {message="编辑内容已切换，请在当前记录重新选择附件";return}
        val epoch=uiEpoch
        editor=current.copy(attachmentStatus="正在导入附件…")
        val job=launchOperation {
            try {
                repository.saveDraft(Draft(current.draftId,current.entry,current.revision,isNoteBuffer=current.entry.kind==Kind.INTERACTION),current.generation)
                repository.importAttachment(current.entry.id,uri,current.generation)
                if(epoch!=uiEpoch || editor?.draftId!=current.draftId) return@launchOperation
                val latest=editor ?: return@launchOperation
                editor=latest.copy(entry=if(latest.occurrenceChosen || latest.entry.kind!=Kind.INTERACTION)latest.entry else latest.entry.copy(occurredAt=TimeRules.now()),
                    occurrenceChosen=true,revision=latest.revision+1,attachmentStatus="附件已保存到本机",status="正在保存…")
                persistLatest()
            } catch(e:CancellationException){throw e}
            catch(e:Exception) {
                if(epoch==uiEpoch && editor?.draftId==current.draftId) editor=editor?.copy(attachmentStatus="附件未导入：${e.message}")
            }
        }
        attachmentOperations[job]=current.draftId
        job.invokeOnCompletion {attachmentOperations.remove(job)}
    }
    fun removeAttachment(a:Attachment)=action { repository.removeAttachment(a.id,repository.generation);message="附件已移除" }
    suspend fun submitFollowUp(task:Entry,original:Entry?,onSaved:(Entry)->Unit) {
        if(transitioning) return
        if(editor?.entry?.kind==Kind.INTERACTION && !persistLatest()) return
        val currentNote=editor?.entry?.takeIf {it.kind==Kind.INTERACTION}?.let {repository.snapshot().find(it.id)}
        val source=currentNote ?: task.sourceId?.let{repository.snapshot().find(it)}
        val validTask=task.copy(sourceId=source?.id,parentId=source?.parentId ?: task.parentId,
            id=if(source==null&&original==null&&task.id.startsWith("followup-"))"independent-${task.id.removePrefix("followup-")}" else task.id)
        val saved=if(original==null) repository.createTaskIfAbsent(validTask,expectedGeneration=repository.generation)
            else repository.patchTask(original.id,title=validTask.title.takeIf{it!=original.title},
                fields=validTask.fields.filter{(k,v)->v!=original[k]},expectedGeneration=repository.generation)
        onSaved(saved)
    }
    fun taskStatus(entry:Entry,status:String)=action { repository.setTaskStatus(entry.id,status,expectedGeneration=repository.generation);message="跟进状态已更新" }
    fun archive(entry:Entry)=action {repository.archive(entry.id,!entry.archived,repository.generation);message=if(!entry.archived) "已归档，历史保留" else "已恢复到列表"}
    fun discard(draft:Draft)=action {repository.discardDraft(draft.id,repository.generation);message="草稿已移除"}
    fun cancelActivity(entry:Entry,cancelTasks:Boolean)=action {repository.cancelActivity(entry.id,cancelTasks,repository.generation);message="活动已取消，历史保留"}

    suspend fun restoreData(manager:BackupManager,prepared:PreparedRestore?=null,rollback:Boolean=false) {
        if(transitioning) throw DataProblem("正在切换资料，请稍候")
        transitioning=true;uiEpoch++
        val caller=currentCoroutineContext()[Job]
        try {
            debounce?.cancelAndJoin();deadline?.cancelAndJoin();deadline=null
            operations.toList().filter{it!=caller}.forEach {it.cancelAndJoin()}
            editor=null;savedState.remove<String>("editingId");pendingExport=null;history.clear()
            if(rollback) repository.rollbackLastRestore() else manager.restore(requireNotNull(prepared))
            repository.migrateLegacyDrafts()
            ReminderScheduler(getApplication()).rebuild(repository.reminderSnapshot(),force=true)
            route="restore";message=if(rollback) "已回到恢复前的资料" else "完整恢复成功"
        } catch(e:CancellationException) { throw e
        } catch(e:Exception) {
            message="资料切换未完成：${e.message ?: "请重试"}"
            android.util.Log.e("WorkbookRestore","资料切换未完成",e)
        } finally {transitioning=false}
    }
    fun onForeground() {foreground=true;refreshClock()}
    fun onBackground() {foreground=false;clockJob?.cancel();flushDraft()}
    fun refreshClock() {
        clockNow=clockProvider();clockJob?.cancel()
        if(!foreground) return
        val at=TimeRules.nextRefreshAt(snapshot.value.entries,clockNow)
        clockJob=viewModelScope.launch {delay((at-clockNow).coerceAtLeast(50));refreshClock()}
    }
}
