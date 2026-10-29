package cn.kangyang.workbook.storage

import cn.kangyang.workbook.data.Entry
import cn.kangyang.workbook.data.Kind
import cn.kangyang.workbook.data.Snapshot
import cn.kangyang.workbook.data.displayTitle
import cn.kangyang.workbook.data.attachmentSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Exports {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
    fun csv(snapshot: Snapshot, interactions: List<Entry>, includeSensitive: Boolean = false, includeBody: Boolean = false): ByteArray {
        val headers = mutableListOf("记录名称", "类型", "对象", "发生时间（北京时间）", "方式", "确认程度", "附件名称")
        if (includeBody) headers += listOf("重要内容", "结论", "下一步")
        if (includeSensitive) headers += listOf("联系人电话（导入时请选择文本列）", "服务对象照护信息")
        val rows = interactions.filter { it.kind == Kind.INTERACTION }.map { entry ->
            val parent = snapshot.find(entry.parentId)
            mutableListOf(exportTitle(entry, snapshot, includeBody), if (parent?.kind == Kind.CUSTOMER) "客户沟通" else if (parent?.kind == Kind.ORGANIZATION) "单位拜访" else "随手记录",
                parent?.title.orEmpty(), format.format(Instant.ofEpochMilli(entry.occurredAt)), entry["mode"], entry["certainty"], entry.attachmentSummary(snapshot)).apply {
                if (includeBody) addAll(listOf(entry["content"], entry["conclusion"], followUpText(entry, snapshot)))
                if (includeSensitive) {
                    add(snapshot.entries.filter { it.kind == Kind.CONTACT && it.parentId == entry.parentId && entry.parentId != null }.joinToString("；") { "${it.title} ${it["phone"]}" })
                    add(snapshot.entries.filter { it.kind == Kind.RECIPIENT && it.parentId == entry.parentId && entry.parentId != null }.joinToString("；") { "${it.title} ${it["care"]}" })
                }
            }
        }
        val text = (listOf(headers) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }
        return byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray(Charsets.UTF_8)
    }

    /** CSV does not store column types; this prefix neutralizes formulas without manufacturing formulas. */
    internal fun csvCell(value: String): String {
        val first = value.dropWhile { it.isWhitespace() || it.code < 32 || it == '\uFEFF' }.firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun summary(entry: Entry, snapshot: Snapshot, includeSensitive: Boolean = false, includeBody: Boolean = false): String = buildString {
        appendLine(exportTitle(entry, snapshot, includeBody))
        when (entry.kind) {
            Kind.INTERACTION -> {
                appendField("对象", snapshot.find(entry.parentId)?.title.orEmpty())
                appendLine("发生时间：${format.format(Instant.ofEpochMilli(entry.occurredAt))}（北京时间）")
                appendField("方式", entry["mode"]); appendField("确认程度", entry["certainty"])
                if (includeBody) { appendField("重要内容", entry["content"]); appendField("结论", entry["conclusion"]); appendField("下一步", followUpText(entry, snapshot)) }
                appendField("附件", entry.attachmentSummary(snapshot))
                appendLine("谈判记录仅用于工作跟进，不作为合同。")
            }
            Kind.ACTIVITY -> {
                appendField("类型", entry["type"]); appendField("时间", if (entry["start"].isBlank() && entry["end"].isBlank()) "时间待定" else "${entry["start"]} 至 ${entry["end"]}（北京时间）")
                appendField("地点", entry["location"]); appendField("关联单位", snapshot.find(entry["organizationId"])?.title.orEmpty())
                listOf("预计人数" to "expectedCount", "状态" to "status", "实际人数" to "actualCount").forEach { (label, field) -> appendField(label, entry[field]) }
                if (includeBody) listOf("目标" to "goal", "面向人群" to "audience", "方案与议程" to "plan", "人员分工" to "division", "物料清单" to "materials", "注意事项" to "precautions", "执行情况" to "execution", "复盘" to "review", "后续跟进" to "followup").forEach { (label, field) -> appendField(label, entry[field]) }
                val tasks = snapshot.entries.filter { it.kind == Kind.TASK && it.parentId == entry.id }
                if (includeBody && tasks.isNotEmpty()) { appendLine("筹备清单："); tasks.forEach { appendLine("• ${it.title}（${it["status"].ifBlank { "未完成" }}${it["date"].takeIf(String::isNotBlank)?.let { date -> "，$date" }.orEmpty()}）") } }
            }
            else -> {
                if (includeBody) { appendField("备注", entry["notes"]); appendField("需求", entry["needs"]) }
            }
        }
        if (includeSensitive) {
            val owner = if (entry.kind == Kind.INTERACTION) entry.parentId else entry.id
            if (owner != null) {
                snapshot.entries.filter { it.kind == Kind.CONTACT && it.parentId == owner }.forEach { appendField("联系人", "${it.title} ${it["phone"]} ${it["wechat"]}") }
                snapshot.entries.filter { it.kind == Kind.RECIPIENT && it.parentId == owner }.forEach { appendField("服务对象", "${it.title} ${it["care"]}") }
            }
        }
    }.trim()

    private fun followUpText(entry: Entry, snapshot: Snapshot): String {
        val tasks = snapshot.entries.filter { it.kind == Kind.TASK && it.sourceId == entry.id }
        return if (tasks.isNotEmpty()) tasks.joinToString("；") { "${it.title}（${it["date"].ifBlank { "未设日期" }} ${it["time"]}，${it["status"].ifBlank { "未完成" }}）" }
        else entry["nextStep"].takeIf(String::isNotBlank)?.let { "$it（旧记录建议，尚未加入跟进）" }.orEmpty()
    }

    private fun exportTitle(entry: Entry, snapshot: Snapshot, includeBody: Boolean): String =
        if (includeBody || entry.title.isNotBlank() || entry.kind != Kind.INTERACTION) entry.displayTitle(snapshot)
        else if (snapshot.attachments.any { it.ownerId == entry.id }) entry.attachmentSummary(snapshot) else "沟通记录"

    private fun StringBuilder.appendField(label: String, value: String) { if (value.isNotBlank()) appendLine("$label：$value") }
}
