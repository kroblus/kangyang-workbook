package cn.kangyang.workbook.data

import cn.kangyang.workbook.domain.TimeRules

class DataProblem(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object DataValidation {
    private val taskStates = setOf("未完成", "已完成", "已取消")
    private val activityStates = setOf("筹备中", "待开展", "已完成", "已取消")
    fun validateFields(entry: Entry, draft: Boolean = false, legacyV1: Boolean = false) {
        requireSafeId(entry.id)
        if (entry.title.length > 500 || entry.fields.size > (if (legacyV1) 100 else 128) || entry.fields.any { it.key.length > 80 || it.value.length > 100_000 }) throw DataProblem("内容超出可保存范围，请分段记录")
        if (entry.createdAt < 0 || entry.updatedAt < 0 || entry.occurredAt < 0) throw DataProblem("记录时间无效")
        if (draft) return
        if (entry.kind == Kind.INTERACTION) {
            // V1.1 allows an existing cleared note and an attachment-only note. Creation eligibility
            // is checked transactionally against current attachments in Repository.
            if (legacyV1 && entry["content"].isBlank()) throw DataProblem("V1 备份的正式沟通缺少正文")
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
                if (!legacyV1) validateNoteTime(entry)
                // Retained next* values are historical suggestions, never current TASK state.
                if (legacyV1 && entry["nextStep"].isNotBlank()) {
                    validateOptionalDate(entry["nextDate"]); validateOptionalTime(entry["nextTime"])
                    if (entry["nextTime"].isNotBlank() && entry["nextDate"].isBlank()) throw DataProblem("回访时刻需要先设置日期")
                    if (entry["nextReminder"] == "true" && entry["nextDate"].isBlank()) throw DataProblem("回访提醒需要先设置日期")
                }
            }
            Kind.ACTIVITY -> {
                if (entry["status"].isNotBlank() && entry["status"] !in activityStates) throw DataProblem("活动状态无效")
                val pending = !legacyV1 && entry["status"].ifBlank { "筹备中" } in setOf("筹备中", "已取消") && entry["start"].isBlank() && entry["end"].isBlank()
                if (!pending) {
                    val start = TimeRules.parseDateTime(entry["start"]) ?: throw DataProblem("请选择完整有效的开始日期与时刻；筹备中可选时间待定")
                    val end = TimeRules.parseDateTime(entry["end"]) ?: throw DataProblem("已安排活动请补齐结束日期与时刻")
                    if (end < start) throw DataProblem("结束时间不能早于开始时间")
                }
                if (entry["remindMinutes"].isNotBlank() && entry["remindMinutes"].toLongOrNull()?.let { it in 0..43_200 } != true) throw DataProblem("提醒时间应为提前 0 至 43200 分钟")
            }
            Kind.CUSTOMER -> validateOptionalDate(entry["intentDate"])
            else -> Unit
        }
    }

    fun validateSnapshot(snapshot: Snapshot, sourceVersion: Int = 2) {
        if (sourceVersion !in 1..2) throw DataProblem("备份数据版本不受支持")
        if (snapshot.entries.size > 50_000 || snapshot.drafts.size > 10_000 || snapshot.attachments.size > 10_000 || snapshot.sealedDraftIds.size > 200_000) throw DataProblem("备份记录数量超过本版本支持范围")
        fun unique(ids: List<String>, label: String) { if (ids.size != ids.toSet().size) throw DataProblem("$label 存在重复标识") }
        unique(snapshot.entries.map { it.id }, "记录"); unique(snapshot.drafts.map { it.id }, "草稿"); unique(snapshot.attachments.map { it.id }, "附件"); unique(snapshot.sealedDraftIds, "草稿提交历史")
        val byId = snapshot.entries.associateBy { it.id }
        if (snapshot.entries.count { it.kind == Kind.PROFILE } > 1 || snapshot.entries.any { it.kind == Kind.PROFILE && it.id != "profile" }) throw DataProblem("本人资料结构无效")
        if (snapshot.entries.filter { it.kind == Kind.CONTACT && it["primary"] == "true" }.groupBy { it.parentId }.any { it.value.size > 1 }) throw DataProblem("同一档案存在多位主要联系人")
        snapshot.entries.forEach { validateFields(it, legacyV1 = sourceVersion == 1); validateRelations(it, byId) }
        val seals = snapshot.sealedDraftIds.toSet()
        snapshot.drafts.forEach {
            requireSafeId(it.id); validateFields(it.entry, draft = true)
            if (it.revision < 0 || it.id in seals) throw DataProblem("草稿版本或提交状态无效")
            if (it.recoveryReason.length > 1000 || it.baseUpdatedAt?.let { time -> time < 0 } == true) throw DataProblem("编辑恢复信息无效")
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
    fun validateNoteTime(entry: Entry) {
        val date = entry.fields["occurredDate"] ?: entry["date"]
        val time = entry.fields["occurredTime"] ?: entry["time"]
        if (date.isBlank() && time.isBlank()) return
        val parsed = TimeRules.parseDateTime("$date $time") ?: throw DataProblem("发生时间无效，请重新选择日期与时刻；时刻精确到分钟，例如 09:30")
        if (TimeRules.formatDateTime(parsed) != TimeRules.formatDateTime(entry.occurredAt)) throw DataProblem("发生时间与所选日期不一致，请重新选择日期与时刻")
    }
    private fun validateOptionalDate(text: String) { if (text.isNotBlank() && TimeRules.parseDate(text) == null) throw DataProblem("日期无效，请重新选择日期") }
    private fun validateOptionalTime(text: String) { if (text.isNotBlank() && TimeRules.parseTime(text) == null) throw DataProblem("时间应精确到分钟，例如 09:30，不支持秒") }
}
