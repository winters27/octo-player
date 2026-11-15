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

// A find is needed while any playlist row holds it, in any item set (one
// being written names it before it is shown), while any queue holds it (a
// parked account's included), and while a playlist's edit log names it.
// The playlist rows, the queue's entries and the edit log are each probed
// on their song id index.
internal const val PRUNE_FINDS_SQL = """
    DELETE FROM online_song WHERE sourceId NOT IN (:kept) OR (
        sourceId = :sourceId AND requestedAt = 0 AND seenAt < :before
        AND NOT EXISTS (SELECT 1 FROM playlist_item i WHERE i.trackId = online_song.id)
        AND NOT EXISTS (SELECT 1 FROM queue_entry q WHERE q.trackId = online_song.id)
        AND NOT EXISTS (SELECT 1 FROM playlist_edit_log e WHERE e.trackId = online_song.id)
    )
"""

// The finds a library song adopted are found by its id when the id moves.
@Entity(tableName = "online_song", indices = [Index("sourceId"), Index("requestedAt"), Index("adoptedId")])
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
    // Zero when the server only guessed the length. Once the player has
    // played the song, the length of its sound, where the listing was
    // missing or more than a second off.
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

    // The ids on the server of server `sourceId`'s finds, for letting go of
    // every saved copy of a song found online.
    @Query("SELECT nativeId FROM online_song WHERE sourceId = :sourceId")
    suspend fun nativeIds(sourceId: String): List<String>

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

    // The finds of server `sourceId` whose library song is gone are neither
    // asked for nor arrived any more.
    @Query(
        "UPDATE online_song SET requestedAt = 0, adoptedId = '' " +
            "WHERE sourceId = :sourceId AND adoptedId != '' AND adoptedId NOT IN (SELECT id FROM track)",
    )
    suspend fun forgetGone(sourceId: String)

    // Gives a find the length the player measured, where its listing was
    // missing or wrong. Answers how many rows took it.
    @Query("UPDATE online_song SET durationMs = :durationMs WHERE id = :id")
    suspend fun setLength(id: String, durationMs: Long): Int

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

    // Lets go of finds from servers no longer kept, and this server's old
    // ones nothing needs: not asked for, and not in the saved queue or a
    // playlist. Another kept server's wait for it (its queue and playlists
    // may hold them).
    @Query(PRUNE_FINDS_SQL)
    suspend fun prune(sourceId: String, kept: List<String>, before: Long)

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
