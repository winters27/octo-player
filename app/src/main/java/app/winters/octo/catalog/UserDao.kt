package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    // Likes

    @Query("SELECT trackId FROM liked_track")
    fun likedIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun like(row: LikedTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeAll(rows: List<LikedTrackEntity>)

    @Query("DELETE FROM liked_track WHERE trackId = :trackId")
    suspend fun unlike(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_track WHERE trackId = :trackId)")
    suspend fun isLiked(trackId: String): Boolean

    // Liked songs the catalog still has, newest like first.
    @Query("SELECT t.* FROM liked_track l JOIN track t ON t.id = l.trackId ORDER BY l.likedAt DESC")
    fun likedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM liked_track l JOIN track t ON t.id = l.trackId")
    fun likedCount(): Flow<Int>

    // Ratings made on the phone

    @Query("SELECT * FROM track_rating")
    suspend fun ratings(): List<TrackRatingEntity>

    @Query("SELECT rating FROM track_rating WHERE trackId = :trackId")
    suspend fun rating(trackId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rate(row: TrackRatingEntity)

    @Query("DELETE FROM track_rating WHERE trackId = :trackId")
    suspend fun unrate(trackId: String)

    // The rating the library shows for one song.
    @Query("UPDATE track SET rating = :rating WHERE id = :trackId")
    suspend fun showRating(trackId: String, rating: Int)

    // After the library is rebuilt: songs no server rated show the rating
    // made on the phone.
    @Query(
        """
        UPDATE track SET rating = (SELECT r.rating FROM track_rating r WHERE r.trackId = track.id)
        WHERE rating = 0 AND id IN (SELECT trackId FROM track_rating)
        """,
    )
    suspend fun showPhoneRatings()

    // Playlists, the one changed last first

    @Query("SELECT * FROM playlist ORDER BY updatedAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>

    // Every playlist song the catalog still has, in play order.
    @Query(
        """
        SELECT i.playlistId, t.albumId, t.durationMs, t.artwork
        FROM playlist_item i JOIN track t ON t.id = i.trackId
        ORDER BY i.playlistId, i.position
        """,
    )
    fun playlistEntries(): Flow<List<PlaylistEntry>>

    @Query(
        """
        SELECT i.id AS itemId, t.*
        FROM playlist_item i JOIN track t ON t.id = i.trackId
        WHERE i.playlistId = :id
        ORDER BY i.position
        """,
    )
    fun playlistTracks(id: String): Flow<List<PlaylistTrack>>

    @Insert
    suspend fun insertPlaylist(row: PlaylistEntity)

    @Query("UPDATE playlist SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, now: Long)

    @Query("UPDATE playlist SET updatedAt = :now WHERE id = :id")
    suspend fun touchPlaylist(id: String, now: Long)

    // Its songs go with it.
    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position")
    suspend fun playlistItems(id: String): List<PlaylistItemEntity>

    @Insert
    suspend fun insertPlaylistItems(rows: List<PlaylistItemEntity>)

    @Update
    suspend fun updatePlaylistItems(rows: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    suspend fun deletePlaylistItem(itemId: Long)

    @Transaction
    suspend fun addToPlaylist(id: String, tracks: List<TrackEntity>, now: Long) {
        val start = playlistItems(id).lastOrNull()?.position?.plus(1) ?: 0
        insertPlaylistItems(appendedItems(id, start, tracks))
        touchPlaylist(id, now)
    }

    @Transaction
    suspend fun removeFromPlaylist(id: String, itemId: Long, now: Long) {
        deletePlaylistItem(itemId)
        val rest = playlistItems(id)
        savePlaces(rest, renumbered(rest))
        touchPlaylist(id, now)
    }

    @Transaction
    suspend fun moveInPlaylist(id: String, itemId: Long, targetId: Long, now: Long) {
        val items = playlistItems(id)
        savePlaces(items, movedItem(items, itemId, targetId))
        touchPlaylist(id, now)
    }

    // Plays

    @Insert
    suspend fun addPlay(row: PlayEventEntity)

    // Every played song the library still has; songs that are gone drop out.
    @Query(
        """
        SELECT t.*, COUNT(*) AS plays, MAX(p.startedAt) AS lastPlayedAt
        FROM play_event p JOIN track t ON t.id = p.trackId
        GROUP BY t.id
        """,
    )
    fun playedTracks(): Flow<List<PlayedTrack>>

    // Every album with a played song that the library still has.
    @Query(
        """
        SELECT a.*, MAX(p.startedAt) AS lastPlayedAt
        FROM play_event p JOIN track t ON t.id = p.trackId JOIN album a ON a.id = t.albumId
        GROUP BY a.id
        """,
    )
    fun playedAlbums(): Flow<List<PlayedAlbum>>

    // Library songs a server has a play record for, one row per server copy.
    @Query(
        """
        SELECT t.*, s.nativeId AS serverId, COALESCE(s.playCount, 0) AS serverPlays, s.lastPlayedAt AS serverLastPlayedAt
        FROM source_track s JOIN track t ON t.id = s.mergedId
        WHERE s.sourceId LIKE 'server:%' AND (s.playCount > 0 OR s.lastPlayedAt IS NOT NULL)
        """,
    )
    fun serverPlayedTracks(): Flow<List<ServerPlayedTrack>>

    // Every album in the library with a song a server saw played.
    @Query(
        """
        SELECT a.*, MAX(s.lastPlayedAt) AS lastPlayedAt
        FROM source_track s JOIN track t ON t.id = s.mergedId JOIN album a ON a.id = t.albumId
        WHERE s.sourceId LIKE 'server:%' AND s.lastPlayedAt IS NOT NULL
        GROUP BY a.id
        """,
    )
    fun serverPlayedAlbums(): Flow<List<PlayedAlbum>>

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

    @Query(
        """
        UPDATE OR IGNORE track_rating
        SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = track_rating.relinkKey LIMIT 1)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = track_rating.relinkKey AND t.relinkKey != '')
        """,
    )
    suspend fun relinkRatings()

    @Transaction
    suspend fun relinkAll() {
        relinkLikes()
        relinkPlays()
        relinkPlaylists()
        relinkRatings()
    }
}

// Writes only the playlist rows whose place changed.
private suspend fun UserDao.savePlaces(before: List<PlaylistItemEntity>, after: List<PlaylistItemEntity>) {
    val unchanged = before.toSet()
    updatePlaylistItems(after.filterNot { it in unchanged })
}
