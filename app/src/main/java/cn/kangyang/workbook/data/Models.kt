package cn.kangyang.workbook.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/** Each kind is a separate business entity, with stable, explicit cross-entity references. */
@Serializable
enum class Kind { ORGANIZATION, CUSTOMER, CONTACT, RECIPIENT, INTERACTION, ACTIVITY, TASK, PROFILE }

@Serializable
@Entity(tableName = "entries", indices = [Index("kind"), Index("parentId"), Index("sourceId"), Index("occurredAt")])
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val title: String = "",
    val parentId: String? = null,
    val sourceId: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String = fields[key].orEmpty()
}

@Serializable
@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey val id: String,
    val entry: Entry,
    val revision: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isNoteBuffer: Boolean = false,
    val baseUpdatedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val recoveryReason: String = "",
)

/** Continuous note sessions are separate from one-shot legacy draft submission seals. */
@Entity(tableName = "note_sessions")
data class NoteSession(
    @PrimaryKey val entryId: String,
    val sessionId: String,
    val revision: Long = -1,
)

@Serializable
@Entity(tableName = "attachments", indices = [Index("ownerId"), Index("relativePath")])
data class Attachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val ownerId: String,
    val name: String,
    val mime: String,
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class Snapshot(
    val entries: List<Entry> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val sealedDraftIds: List<String> = emptyList(),
) {
    fun find(id: String?): Entry? = entries.firstOrNull { it.id == id }
}

@Entity(tableName = "sealed_drafts")
data class SealedDraft(@PrimaryKey val id: String)

@Entity(tableName = "restore_rollback")
data class RestoreRollback(@PrimaryKey val id: Int = 1, val snapshotJson: String, val createdAt: Long)

val DEFAULT_TAGS = listOf("康养中心推介", "康复器具与耗材", "术后康复护理", "医院及居家护理", "长期照护师培训")

fun defaultProfile() = Entry(
    id = "profile", kind = Kind.PROFILE, title = "姜宏",
    fields = mapOf("role" to "养老顾问", "company" to "湖北省大千生活服务有限公司", "appName" to "康养工作簿", "tags" to DEFAULT_TAGS.joinToString("\n")),
)
