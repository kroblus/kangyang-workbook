package cn.kangyang.workbook.data

import cn.kangyang.workbook.domain.TimeRules

data class LegacyMigrationResult(val snapshot: Snapshot, val promoted: Int, val needsReview: Int)

/** Pure conversion is shared by startup, V1 import and rollback; no reminders or tasks are created. */
object LegacyNotes {
    fun convert(snapshot: Snapshot): LegacyMigrationResult {
        val entries = snapshot.entries.associateBy { it.id }.toMutableMap()
        // V1 used occurredAt as the actual timeline/export truth. Preserve it and retain any stale
        // text in trace fields rather than reinterpreting history using an inconsistent UI string.
        entries.values.toList().filter { it.kind == Kind.INTERACTION }.forEach { entry ->
            if (runCatching { DataValidation.validateNoteTime(entry) }.isFailure) {
                val time = TimeRules.formatDateTime(entry.occurredAt).split(' ')
                entries[entry.id] = entry.copy(fields = entry.fields + mapOf(
                    "legacyOccurredDate" to (entry.fields["occurredDate"] ?: entry["date"]),
                    "legacyOccurredTime" to (entry.fields["occurredTime"] ?: entry["time"]),
                    "occurredDate" to time[0], "occurredTime" to time[1],
                ))
            }
        }
        val drafts = mutableListOf<Draft>()
        val seals = snapshot.sealedDraftIds.toMutableSet()
        var promoted = 0
        var review = 0
        snapshot.drafts.sortedBy { it.updatedAt }.forEach { draft ->
            val entry = draft.entry
            // A successful attachment import may have committed ownership just before the process
            // died and before its first note publication. Recover that valid orphan buffer on open.
            val pendingAttachmentPublication = draft.isNoteBuffer && entries[entry.id] == null && snapshot.attachments.any { it.ownerId == entry.id }
            if (entry.kind != Kind.INTERACTION || (draft.isNoteBuffer && !pendingAttachmentPublication) || draft.recoveryReason.isNotBlank()) {
                drafts += draft
                if (draft.recoveryReason.isNotBlank()) review++
                return@forEach
            }
            val previous = entries[entry.id]
            val hasContent = entry["content"].isNotBlank() || snapshot.attachments.any { it.ownerId == entry.id }
            if (!hasContent && previous == null) { drafts += draft; return@forEach }
            val failure = runCatching {
                DataValidation.validateFields(entry)
                DataValidation.validateRelations(entry, entries)
            }.exceptionOrNull()?.message
            if (failure != null) {
                drafts += draft.copy(isNoteBuffer = false, recoveryReason = "这份旧编辑内容需要修正：$failure")
                review++
                return@forEach
            }
            // Legacy buffers remember the original Entry.updatedAt. A newer formal row wins until
            // the user explicitly chooses which preserved content to continue.
            val base = draft.baseUpdatedAt ?: entry.updatedAt
            val conflict = previous != null && previous.updatedAt > base && previous != entry
            if (conflict) {
                drafts += draft.copy(recoveryReason = "原记录已有更新；这份旧编辑内容已单独保留，请查看后选择是否继续")
                review++
                return@forEach
            }
            val saved = entry.copy(createdAt = previous?.createdAt ?: entry.createdAt, updatedAt = maxOf(entry.updatedAt, draft.updatedAt))
            entries[entry.id] = saved
            // Reassociation changes only ownership, not any independently maintained task fields.
            entries.values.toList().filter { it.kind == Kind.TASK && it.sourceId == entry.id && it.parentId != entry.parentId }.forEach {
                entries[it.id] = it.copy(parentId = entry.parentId)
            }
            seals += draft.id
            promoted++
        }
        return LegacyMigrationResult(snapshot.copy(entries = entries.values.toList(), drafts = drafts, sealedDraftIds = seals.toList()), promoted, review)
    }
}

/** Presentation-only titles never become stored body search terms. Manual legacy titles survive. */
fun Entry.displayTitle(snapshot: Snapshot): String {
    if (title.isNotBlank()) return title
    if (kind != Kind.INTERACTION) return "未命名"
    return this["content"].lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
        ?: snapshot.attachments.firstOrNull { it.ownerId == id }?.name
        ?: "空白记录"
}

fun Entry.attachmentSummary(snapshot: Snapshot): String = snapshot.attachments.filter { it.ownerId == id }
    .joinToString("、") { it.name }
