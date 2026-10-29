package cn.kangyang.workbook.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

class DataProblem(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object DataValidation {
    private val dateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val time = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val taskStates = setOf("未完成", "已完成", "已取消")
    private val activityStates = setOf("筹备中", "待开展", "已完成", "已取消")
    fun validateFields(entry: Entry, draft: Boolean = false) {
        requireSafeId(entry.id)
        if (entry.title.length > 500 || entry.fields.size > 100 || entry.fields.any { it.key.length > 80 || it.value.length > 100_000 }) throw DataProblem("内容超出可保存范围，请分段记录")
        if (entry.createdAt < 0 || entry.updatedAt < 0 || entry.occurredAt < 0) throw DataProblem("记录时间无效")
        if (draft) return
        if (entry.kind == Kind.INTERACTION) {
            if (entry["content"].isBlank()) throw DataProblem("请填写重要内容")
        } else if (entry.title.isBlank()) throw DataProblem("请填写名称或称呼")
        if (entry.kind == Kind.ORGANIZATION && entry["type"].isBlank()) throw DataProblem("请选择单位类型")
        when (entry.kind) {
            Kind.TASK -> {
                validateOptionalDate(entry["date"]); validateOptionalTime(entry["time"])
                if (entry["time"].isNotBlank() && entry["date"].isBlank()) throw DataProblem("设置具体时刻前，请先选择日期")
                if (entry["reminder"] == "true" && entry["date"].isBlank()) throw DataProblem("开启提醒前，请先设置日期")
                if (entry["status"].isNotBlank() && entry["status"] !in taskStates) throw DataProblem("待办状态无效")
            }
            Kind.INTERACTION -> {
                if (entry["nextStep"].isNotBlank()) {
                    validateOptionalDate(entry["nextDate"]); validateOptionalTime(entry["nextTime"])
                    if (entry["nextTime"].isNotBlank() && entry["nextDate"].isBlank()) throw DataProblem("回访时刻需要先设置日期")
                    if (entry["nextReminder"] == "true" && entry["nextDate"].isBlank()) throw DataProblem("回访提醒需要先设置日期")
                }
            }
            Kind.ACTIVITY -> {
                val start = parseDateTime(entry["start"])
                val end = parseDateTime(entry["end"])
                if (start == null || end == null) throw DataProblem("正式安排请填写开始与结束时间；时间未定可保留草稿")
                if (end < start) throw DataProblem("结束时间不能早于开始时间")
                if (entry["status"].isNotBlank() && entry["status"] !in activityStates) throw DataProblem("活动状态无效")
                if (entry["remindMinutes"].isNotBlank() && entry["remindMinutes"].toLongOrNull()?.let { it in 0..43_200 } != true) throw DataProblem("提醒时间应为提前 0 至 43200 分钟")
            }
            Kind.CUSTOMER -> validateOptionalDate(entry["intentDate"])
            else -> Unit
        }
    }

    fun validateSnapshot(snapshot: Snapshot) {
        if (snapshot.entries.size > 50_000 || snapshot.drafts.size > 10_000 || snapshot.attachments.size > 10_000 || snapshot.sealedDraftIds.size > 200_000) throw DataProblem("备份记录数量超过本版本支持范围")
        fun unique(ids: List<String>, label: String) { if (ids.size != ids.toSet().size) throw DataProblem("$label 存在重复标识") }
        unique(snapshot.entries.map { it.id }, "记录"); unique(snapshot.drafts.map { it.id }, "草稿"); unique(snapshot.attachments.map { it.id }, "附件"); unique(snapshot.sealedDraftIds, "草稿提交历史")
        val byId = snapshot.entries.associateBy { it.id }
        if (snapshot.entries.count { it.kind == Kind.PROFILE } > 1 || snapshot.entries.any { it.kind == Kind.PROFILE && it.id != "profile" }) throw DataProblem("本人资料结构无效")
        if (snapshot.entries.filter { it.kind == Kind.CONTACT && it["primary"] == "true" }.groupBy { it.parentId }.any { it.value.size > 1 }) throw DataProblem("同一档案存在多位主要联系人")
        snapshot.entries.forEach { validateFields(it); validateRelations(it, byId) }
        val seals = snapshot.sealedDraftIds.toSet()
        snapshot.drafts.forEach {
            requireSafeId(it.id); validateFields(it.entry, draft = true)
            if (it.revision < 0 || it.id in seals) throw DataProblem("草稿版本或提交状态无效")
            if (byId[it.entry.id]?.kind?.let { kind -> kind != it.entry.kind } == true) throw DataProblem("草稿与原记录类型不一致")
            validateRelations(it.entry, byId, draft = true)
        }
        val owners = byId.keys + snapshot.drafts.map { it.entry.id }
        snapshot.attachments.forEach {
            requireSafeId(it.id)
            if (it.ownerId !in owners) throw DataProblem("附件没有关联的记录或草稿")
            if (!it.sha256.matches(Regex("[a-f0-9]{64}")) || it.relativePath != "attachments/${it.sha256}.bin") throw DataProblem("附件路径或校验信息无效")
            if (it.size !in 1..AttachmentFiles.MAX_FILE_BYTES || it.name.isBlank() || it.name.length > 255) throw DataProblem("附件信息无效或文件过大")
            if (!AttachmentFiles.supports(it.mime, it.name)) throw DataProblem("备份含不支持的附件格式")
        }
    }

    fun validateRelations(entry: Entry, byId: Map<String, Entry>, draft: Boolean = false) {
        entry.parentId?.let { parent ->
            val target = byId[parent] ?: throw DataProblem("关联对象已不存在，请重新选择")
            val valid = when (entry.kind) {
                Kind.CONTACT -> target.kind in setOf(Kind.ORGANIZATION, Kind.CUSTOMER)
                Kind.RECIPIENT -> target.kind == Kind.CUSTOMER
                Kind.INTERACTION -> target.kind in setOf(Kind.ORGANIZATION, Kind.CUSTOMER)
                Kind.TASK -> target.kind in setOf(Kind.ORGANIZATION, Kind.CUSTOMER, Kind.ACTIVITY)
                else -> false
            }
            if (!valid) throw DataProblem("关联对象类型不匹配")
        }
        if (!draft && entry.kind in setOf(Kind.CONTACT, Kind.RECIPIENT) && entry.parentId == null) throw DataProblem("请先选择所属档案")
        entry.sourceId?.let {
            val source = byId[it] ?: throw DataProblem("待办来源记录已不存在")
            if (entry.kind != Kind.TASK || source.kind != Kind.INTERACTION) throw DataProblem("待办来源关系无效")
            if (entry.parentId != source.parentId) throw DataProblem("待办与来源记录的对象不一致")
        }
        fun link(field: String, kind: Kind) {
            val value = entry[field]
            if (value.isNotBlank() && byId[value]?.kind != kind) throw DataProblem("关联的${if (kind == Kind.ACTIVITY) "活动" else "单位"}已不存在")
        }
        if (entry.kind == Kind.CUSTOMER) { link("linkedOrganization", Kind.ORGANIZATION); link("linkedActivity", Kind.ACTIVITY) }
        if (entry.kind == Kind.ACTIVITY) link("organizationId", Kind.ORGANIZATION)
    }

    fun requireSafeId(value: String) { if (!value.matches(Regex("[A-Za-z0-9_-]{1,120}"))) throw DataProblem("记录标识无效") }
    private fun validateOptionalDate(text: String) { if (text.isNotBlank() && runCatching { LocalDate.parse(text) }.isFailure) throw DataProblem("日期格式应为 年-月-日，例如 2026-10-29") }
    private fun validateOptionalTime(text: String) { if (text.isNotBlank() && runCatching { LocalTime.parse(text, time) }.isFailure) throw DataProblem("时间格式应为 24 小时制，例如 09:30") }
    private fun parseDateTime(text: String): LocalDateTime? = if (text.isBlank()) null else try { LocalDateTime.parse(text, dateTime) } catch (_: Exception) { throw DataProblem("活动时间格式应为 年-月-日 时:分") }
}
