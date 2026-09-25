package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    // Likes

    @Query("SELECT trackId FROM liked_track")
    fun likedIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun like(row: LikedTrackEntity)

    @Query("DELETE FROM liked_track WHERE trackId = :trackId")
    suspend fun unlike(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_track WHERE trackId = :trackId)")
    suspend fun isLiked(trackId: String): Boolean

    // Plays

    @Insert
    suspend fun addPlay(row: PlayEventEntity)

    // The saved queue

    @Query("SELECT * FROM queue_item ORDER BY position")
    suspend fun queueItems(): List<QueueItemEntity>

    @Query("SELECT * FROM queue_state WHERE id = 0")
    suspend fun queueState(): QueueStateEntity?

    @Query("DELETE FROM queue_item")
    suspend fun clearQueueItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(rows: List<QueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setQueueState(row: QueueStateEntity)

    // Replaces the saved queue in one go, so a half-written queue is never read back.
    @Transaction
    suspend fun saveQueue(items: List<QueueItemEntity>, state: QueueStateEntity) {
        clearQueueItems()
        items.chunked(500).forEach { insertQueueItems(it) }
        setQueueState(state)
    }

    // After a scan, points rows whose song id vanished at the song that now
    // has the same relink key. OR IGNORE skips a like that would duplicate one.
    @Query(
        """
        UPDATE OR IGNORE liked_track
        SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = liked_track.relinkKey LIMIT 1)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = liked_track.relinkKey AND t.relinkKey != '')
        """,
    )
    suspend fun relinkLikes()

    @Query(
        """
        UPDATE play_event
        SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = play_event.relinkKey LIMIT 1)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = play_event.relinkKey AND t.relinkKey != '')
        """,
    )
    suspend fun relinkPlays()

    @Query(
        """
        UPDATE playlist_item
        SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = playlist_item.relinkKey LIMIT 1)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = playlist_item.relinkKey AND t.relinkKey != '')
        """,
    )
    suspend fun relinkPlaylists()

    @Transaction
    suspend fun relinkAll() {
        relinkLikes()
        relinkPlays()
        relinkPlaylists()
    }
}
