package cn.kangyang.workbook

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

val DefaultTags = listOf("康养中心推介", "康复器具与耗材", "术后康复护理", "医院及居家护理", "长期照护师培训")
fun defaultProfile() = Entry(id="profile", kind=Kind.PROFILE, title="姜宏", fields=mapOf("role" to "养老顾问", "company" to "湖北省大千生活服务有限公司", "appName" to "康养工作簿", "tags" to DefaultTags.joinToString("\n")))
data class EditorSession(val draftId:String, val entry:Entry, val revision:Long=0, val status:String="开始填写后自动保存", val saving:Boolean=false)

class WorkbookViewModel(application: Application) : AndroidViewModel(application) {
    val repository=Repository.get(application)
    val snapshot=repository.state.stateIn(viewModelScope,SharingStarted.Eagerly,Snapshot())
    var route by mutableStateOf("home"); private set
    private val history=mutableListOf<String>()
    var editor by mutableStateOf<EditorSession?>(null); private set
    var message by mutableStateOf<String?>(null)
    var pendingExport:ByteArray?=null
    private var draftJob:Job?=null
    val profile get()=snapshot.value.entries.find { it.kind==Kind.PROFILE } ?: defaultProfile()
    val tags get()=profile["tags"].lines().filter { it.isNotBlank() }.ifEmpty { DefaultTags }

    fun navigate(target:String, root:Boolean=false) {
        message=null
        if(route=="editor") flushDraft()
        if(root) history.clear() else if(route!=target) history.add(route)
        route=target
    }
    fun back() {
        if(route=="editor") flushDraft()
        route=if(history.isNotEmpty()) history.removeAt(history.lastIndex) else "home"
    }
    fun edit(entry:Entry, draft:Draft?=null) {
        message=null
        val existing=draft ?: snapshot.value.drafts.firstOrNull { it.entry.id==entry.id }
        editor=EditorSession(existing?.id ?: UUID.randomUUID().toString(),existing?.entry ?: entry,existing?.revision ?: 0,
            if(existing!=null) "已继续上次草稿" else "开始填写后自动保存")
        navigate("editor")
    }
    fun create(kind:Kind, parent:Entry?=null) {
        val defaults=when(kind) {
            Kind.ORGANIZATION -> mapOf("type" to "养老机构","stage" to "待联系","nature" to "暂不清楚")
            Kind.CUSTOMER -> mapOf("stage" to "新咨询","urgency" to "普通")
            Kind.INTERACTION -> mapOf("mode" to "面谈","certainty" to "讨论过")
            Kind.ACTIVITY -> mapOf("status" to "筹备中","type" to "线下交流")
            Kind.TASK -> mapOf("status" to "未完成","priority" to "普通")
            else -> emptyMap()
        }
        edit(Entry(kind=kind,parentId=parent?.id,fields=defaults))
    }
    fun change(entry:Entry) {
        val current=editor ?: return
        if(current.saving) return
        val session=current.copy(entry=entry,revision=current.revision+1,status="正在保存草稿…")
        editor=session
        draftJob?.cancel()
        draftJob=viewModelScope.launch { delay(350); persistDraft(session) }
    }
    private suspend fun persistDraft(session:EditorSession) {
        try {
            val written=repository.saveDraft(Draft(id=session.draftId,entry=session.entry,revision=session.revision,updatedAt=System.currentTimeMillis()))
            if(editor?.draftId==session.draftId && editor?.revision==session.revision && editor?.saving==false)
                editor=editor?.copy(status=if(written) "草稿已保存" else "此草稿已提交")
        } catch(e:CancellationException) { throw e } catch(e:Exception) {
            if(editor?.draftId==session.draftId) editor=editor?.copy(status="草稿未保存，点击重试")
            message=e.message ?: "保存草稿失败，请重试"
        }
    }
    fun flushDraft() {
        val current=editor ?: return
        if(current.revision==0L || current.saving) return
        draftJob?.cancel()
        draftJob=viewModelScope.launch { persistDraft(current) }
    }
    fun save(cancelActivityTasks:Boolean?=null) {
        val current=editor ?: return
        if(current.saving) return
        editor=current.copy(saving=true)
        draftJob?.cancel()
        viewModelScope.launch {
            try {
                val saved=repository.save(current.entry,current.draftId,cancelActivityTasks)
                editor=null
                val destination=if(saved.kind==Kind.PROFILE) "settings" else "detail:${saved.id}"
                if(history.lastOrNull()==destination) history.removeAt(history.lastIndex)
                route=destination
                message=when {
                    saved.kind==Kind.ACTIVITY -> "活动已保存；改期后请检查独立设置日期的筹备待办"
                    saved.kind==Kind.INTERACTION && saved["nextStep"].isNotBlank() -> "记录和关联跟进已保存"
                    else -> "已保存"
                }
            } catch(e:Exception) {
                editor=current.copy(status="草稿已保留，可修改后重试")
                persistDraft(current)
                message=e.message ?: "未能保存，请重试"
            }
        }
    }
    fun action(block:suspend ()->Unit) { viewModelScope.launch { try { block() } catch(e:Exception) { message=e.message ?: "操作未完成，请重试" } } }
    fun attachment(uri:Uri?) {
        if(uri==null) return
        val current=editor ?: return
        draftJob?.cancel()
        action {
            // Establish persisted ownership before importing; cancellation or failure leaves the draft intact.
            persistDraft(current)
            repository.importAttachment(current.entry.id,uri)
            if(editor?.draftId==current.draftId) editor=editor?.copy(status="草稿已保存")
            message="附件已保存到本机"
        }
    }
    fun taskStatus(entry:Entry,status:String) = action { repository.setTaskStatus(entry.id,status); message=if(status=="已完成") "已完成跟进" else "状态已更新" }
    fun archive(entry:Entry)=action { repository.archive(entry.id,!entry.archived); message=if(!entry.archived) "已归档，历史保留；未完成待办请单独处理" else "已恢复到列表" }
    fun discard(draft:Draft)=action { repository.discardDraft(draft.id); message="草稿已移除" }
    fun cancelActivity(entry:Entry,cancelTasks:Boolean)=action {
        repository.cancelActivity(entry.id,cancelTasks)
        message="活动已取消，历史保留"
    }
}
