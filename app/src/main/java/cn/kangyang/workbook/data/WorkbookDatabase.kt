package cn.kangyang.workbook.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val dataJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

class DataConverters {
    @TypeConverter fun kindToString(value: Kind): String = value.name
    @TypeConverter fun stringToKind(value: String): Kind = Kind.valueOf(value)
    @TypeConverter fun mapToString(value: Map<String, String>): String = dataJson.encodeToString(value)
    @TypeConverter fun stringToMap(value: String): Map<String, String> = dataJson.decodeFromString(value)
    @TypeConverter fun entryToString(value: Entry): String = dataJson.encodeToString(value)
    @TypeConverter fun stringToEntry(value: String): Entry = dataJson.decodeFromString(value)
}

@Dao
interface WorkbookDao {
    @Query("SELECT * FROM entries ORDER BY occurredAt DESC, id") suspend fun entries(): List<Entry>
    @Query("SELECT * FROM entries WHERE id=:id") suspend fun entry(id: String): Entry?
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC") suspend fun drafts(): List<Draft>
    @Query("SELECT * FROM drafts WHERE id=:id") suspend fun draft(id: String): Draft?
    @Query("SELECT * FROM attachments ORDER BY id") suspend fun attachments(): List<Attachment>
    @Query("SELECT * FROM sealed_drafts") suspend fun sealedDrafts(): List<SealedDraft>
    @Query("SELECT EXISTS(SELECT 1 FROM sealed_drafts WHERE id=:id)") suspend fun sealed(id: String): Boolean
    // Referencing every mutable table gives a single invalidation signal; each emission reads a transaction snapshot.
    @Query("SELECT (SELECT COUNT(*) FROM entries) + (SELECT COUNT(*) FROM drafts) + (SELECT COUNT(*) FROM attachments) + (SELECT COUNT(*) FROM sealed_drafts)")
    fun changes(): Flow<Long>
    @Upsert suspend fun upsert(entry: Entry)
    @Upsert suspend fun upsertEntries(entries: List<Entry>)
    @Upsert suspend fun upsert(draft: Draft)
    @Upsert suspend fun upsertDrafts(drafts: List<Draft>)
    @Upsert suspend fun upsert(attachment: Attachment)
    @Upsert suspend fun upsertAttachments(attachments: List<Attachment>)
    @Upsert suspend fun seal(seal: SealedDraft)
    @Upsert suspend fun sealAll(seals: List<SealedDraft>)
    @Upsert suspend fun rollback(rollback: RestoreRollback)
    @Query("SELECT * FROM restore_rollback WHERE id=1") suspend fun rollback(): RestoreRollback?
    @Query("DELETE FROM drafts WHERE id=:id") suspend fun deleteDraft(id: String)
    @Query("DELETE FROM attachments WHERE id=:id") suspend fun deleteAttachment(id: String)
    @Query("DELETE FROM entries WHERE id=:id") suspend fun deleteEntry(id: String)
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM drafts") suspend fun clearDrafts()
    @Query("DELETE FROM attachments") suspend fun clearAttachments()
    @Query("DELETE FROM sealed_drafts") suspend fun clearSeals()
}

@Database(entities = [Entry::class, Draft::class, Attachment::class, SealedDraft::class, RestoreRollback::class], version = 1, exportSchema = true)
@TypeConverters(DataConverters::class)
abstract class WorkbookDatabase : RoomDatabase() { abstract fun dao(): WorkbookDao }
