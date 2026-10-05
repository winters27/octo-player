package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// Songs the server found online that are not in the library: in search,
// radio and stations. Kept so a queue holding one still plays after the app
// restarts, and so a download asked for can be followed until it arrives.
const val FIND_PREFIX = "find:"

fun findId(serverId: String) = "$FIND_PREFIX$serverId"

fun isFind(trackId: String) = trackId.startsWith(FIND_PREFIX)

@Entity(tableName = "online_song", indices = [Index("sourceId"), Index("requestedAt")])
data class OnlineSongEntity(
    // "find:<server id>", the id it has in the queue.
    @PrimaryKey val id: String,
    val sourceId: String,
    val nativeId: String,
    val title: String,
    val artist: String,
    // Empty when the server did not say.
    val album: String,
    val albumId: String?,
    val artistId: String?,
    // Zero when the server only guessed the length, until the player has
    // played the song and learned it.
    val durationMs: Long,
    val coverId: String?,
    val mimeType: String?,
    // Kilobits a second, as the server says.
    val bitrate: Int?,
    val seenAt: Long,
    // When a download was asked for, or zero.
    @ColumnInfo(defaultValue = "0") val requestedAt: Long = 0,
    // The library song it became once downloaded, or empty.
    @ColumnInfo(defaultValue = "") val adoptedId: String = "",
    // True when the server marks it explicit, false when clean, null when
    // nothing says.
    val explicit: Boolean? = null,
)

@Dao
interface OnlineDao {
    @Query("SELECT * FROM online_song WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<OnlineSongEntity>

    @Query("SELECT * FROM online_song WHERE id = :id")
    suspend fun song(id: String): OnlineSongEntity?

    @Query("SELECT * FROM online_song WHERE id = :id")
    fun songFlow(id: String): Flow<OnlineSongEntity?>

    // Every find with a download asked for, for showing which are on the way.
    @Query("SELECT * FROM online_song WHERE requestedAt > 0")
    fun requestedFlow(): Flow<List<OnlineSongEntity>>

    // Downloads asked for that have not turned up in the library yet.
    @Query("SELECT * FROM online_song WHERE requestedAt > 0 AND adoptedId = ''")
    suspend fun waiting(): List<OnlineSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<OnlineSongEntity>)

    @Query("UPDATE online_song SET requestedAt = :at WHERE id = :id")
    suspend fun setRequested(id: String, at: Long)

    @Query("UPDATE online_song SET adoptedId = :trackId WHERE id = :id")
    suspend fun adopt(id: String, trackId: String)

    // Gives a find the length the player learned, only when it has none.
    // Answers how many rows took it.
    @Query("UPDATE online_song SET durationMs = :durationMs WHERE id = :id AND durationMs = 0")
    suspend fun fillLength(id: String, durationMs: Long): Int

    // Stores what the server says about these songs now, keeping what the
    // app knows about each, and answers them as stored.
    @Transaction
    suspend fun keep(rows: List<OnlineSongEntity>): List<OnlineSongEntity> {
        if (rows.isEmpty()) return rows
        val before = rows.map { it.id }.chunked(900).flatMap { byIds(it) }.associateBy { it.id }
        val kept = rows.map { row -> before[row.id]?.let { keptFind(row, it) } ?: row }
        insert(kept)
        return kept
    }

    // Lets go of finds from other servers, and old ones nothing needs: not
    // asked for, and not in the saved queue or a playlist.
    @Query(
        """
        DELETE FROM online_song WHERE sourceId != :sourceId OR (
            requestedAt = 0 AND seenAt < :before AND id NOT IN (SELECT trackId FROM queue_item)
            AND id NOT IN (SELECT trackId FROM playlist_item)
        )
        """,
    )
    suspend fun prune(sourceId: String, before: Long)

    @Query("DELETE FROM online_song")
    suspend fun clear()
}

// What the server says about a find now, with what the app already knew:
// whether a download was asked for, what it became, and a length learned
// from playing it when the server still sends none.
fun keptFind(now: OnlineSongEntity, before: OnlineSongEntity): OnlineSongEntity = now.copy(
    requestedAt = before.requestedAt,
    adoptedId = before.adoptedId,
    durationMs = if (now.durationMs > 0) now.durationMs else before.durationMs,
)
