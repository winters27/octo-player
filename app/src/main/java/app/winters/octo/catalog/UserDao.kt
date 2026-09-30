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

    @Query("SELECT * FROM liked_track WHERE trackId = :trackId")
    suspend fun likedRow(trackId: String): LikedTrackEntity?

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

    // For a backup: likes, ratings and the songs of playlists only on the
    // phone, each with what is known of the song, in order.

    @Query(
        """
        SELECT NULL AS playlistId, l.relinkKey, t.title, t.artist, t.album, t.durationMs, NULL AS rating
        FROM liked_track l LEFT JOIN track t ON t.id = l.trackId
        ORDER BY l.likedAt
        """,
    )
    suspend fun likedSongKeys(): List<SongKeyRow>

    @Query(
        """
        SELECT NULL AS playlistId, r.relinkKey, t.title, t.artist, t.album, t.durationMs, r.rating
        FROM track_rating r LEFT JOIN track t ON t.id = r.trackId
        ORDER BY r.ratedAt
        """,
    )
    suspend fun ratedSongKeys(): List<SongKeyRow>

    @Query(
        """
        SELECT i.playlistId, i.relinkKey, COALESCE(t.title, o.title) AS title, COALESCE(t.artist, o.artist) AS artist,
            COALESCE(t.album, o.album) AS album, COALESCE(t.durationMs, o.durationMs) AS durationMs, NULL AS rating
        FROM playlist_item i JOIN playlist p ON p.id = i.playlistId
            LEFT JOIN track t ON t.id = i.trackId LEFT JOIN online_song o ON o.id = i.trackId
        WHERE p.sourceId IS NULL
        ORDER BY i.playlistId, i.position
        """,
    )
    suspend fun phonePlaylistSongKeys(): List<SongKeyRow>

    // Playlists, the one changed last first

    @Query("SELECT * FROM playlist ORDER BY updatedAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>

    // Every playlist song the catalog still has, in play order. A song found
    // online (from a server's copy of a playlist) counts too.
    // Its cover is written the way onlineArtwork writes one.
    @Query(
        """
        SELECT playlistId, albumId, durationMs, artwork FROM (
            SELECT i.playlistId, i.position, t.albumId, t.durationMs, t.artwork
            FROM playlist_item i JOIN track t ON t.id = i.trackId
            UNION ALL
            SELECT i.playlistId, i.position, COALESCE(o.albumId, ''), o.durationMs,
                CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END
            FROM playlist_item i JOIN online_song o ON o.id = i.trackId
        )
        ORDER BY playlistId, position
        """,
    )
    fun playlistEntries(): Flow<List<PlaylistEntry>>

    // A playlist's songs in play order: library songs, and songs found
    // online shaped like them, the way asTrack shapes a find.
    @Query(
        """
        SELECT itemId, id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId,
            trackNo, discNo, year, durationMs, addedAt, mimeType, sizeBytes, artwork, uri, albumOrder,
            relinkKey, genre, onPhone, rating
        FROM (
            SELECT i.id AS itemId, i.position AS position, t.id, t.sourceId, t.nativeId, t.title, t.searchKey,
                t.sortKey, t.artist, t.artistId, t.album, t.albumId, t.trackNo, t.discNo, t.year, t.durationMs,
                t.addedAt, t.mimeType, t.sizeBytes, t.artwork, t.uri, t.albumOrder, t.relinkKey, t.genre,
                t.onPhone, t.rating
            FROM playlist_item i JOIN track t ON t.id = i.trackId
            WHERE i.playlistId = :id
            UNION ALL
            SELECT i.id, i.position, o.id, o.sourceId, o.nativeId, o.title, LOWER(o.title), LOWER(o.title),
                o.artist, '', o.album, '', NULL, NULL, NULL, o.durationMs, 0, o.mimeType, NULL,
                CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END,
                NULL, 0, '', '', 0, 0
            FROM playlist_item i JOIN online_song o ON o.id = i.trackId
            WHERE i.playlistId = :id
        )
        ORDER BY position
        """,
    )
    fun playlistTracks(id: String): Flow<List<PlaylistTrack>>

    // How many songs of a playlist kept with a server have no copy there,
    // so the server's copy goes without them.
    @Query(
        """
        SELECT COUNT(*) FROM playlist_item i JOIN playlist p ON p.id = i.playlistId
        WHERE i.playlistId = :id AND p.sourceId IS NOT NULL AND i.serverSongId IS NULL
          AND i.trackId NOT LIKE 'find:%'
          AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = i.trackId AND s.sourceId = p.sourceId)
        """,
    )
    fun phoneOnlyCount(id: String): Flow<Int>

    @Insert
    suspend fun insertPlaylist(row: PlaylistEntity)

    // A change always moves updatedAt forward, even if the clock went back,
    // so it is never mistaken for one a server already has.
    @Query("UPDATE playlist SET name = :name, updatedAt = MAX(:now, updatedAt + 1) WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, now: Long)

    @Query("UPDATE playlist SET updatedAt = MAX(:now, updatedAt + 1) WHERE id = :id")
    suspend fun touchPlaylist(id: String, now: Long)

    // Keeping playlists in step with a server

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlistRow(id: String): PlaylistEntity?

    // Every playlist kept with this server, made there or waiting to be.
    @Query("SELECT * FROM playlist WHERE sourceId = :sourceId")
    suspend fun serverPlaylists(sourceId: String): List<PlaylistEntity>

    // Playlists only on the phone.
    @Query("SELECT * FROM playlist WHERE sourceId IS NULL")
    suspend fun phonePlaylists(): List<PlaylistEntity>

    // Marks a playlist to be made on this server. It is linked once made.
    @Query("UPDATE playlist SET sourceId = :sourceId WHERE id = :id AND sourceId IS NULL")
    suspend fun keepOnServer(id: String, sourceId: String)

    @Query(
        """
        UPDATE playlist SET serverId = :serverId, sourceId = :sourceId, syncedAt = :syncedAt,
            syncedName = :name, serverStamp = :stamp
        WHERE id = :id
        """,
    )
    suspend fun linkPlaylist(id: String, serverId: String, sourceId: String, syncedAt: Long, name: String, stamp: String)

    // Both sides now agree on what the playlist was at `syncedAt`.
    @Query("UPDATE playlist SET syncedAt = :syncedAt, syncedName = :name, serverStamp = :stamp WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long, name: String, stamp: String)

    // Turns a playlist back into one only on the phone.
    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL
        WHERE id = :id
        """,
    )
    suspend fun unlinkPlaylist(id: String)

    // Every playlist kept with another server than this one (all of them
    // for null) goes back to being only on the phone.
    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL
        WHERE sourceId IS NOT NULL AND (:keep IS NULL OR sourceId != :keep)
        """,
    )
    suspend fun unlinkOtherServers(keep: String?)

    // Every playlist kept with this server goes back to being only on the phone.
    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL
        WHERE sourceId = :sourceId
        """,
    )
    suspend fun unlinkServer(sourceId: String)

    // Every playlist kept with a server not among these goes back to being
    // only on the phone.
    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL
        WHERE sourceId IS NOT NULL AND sourceId NOT IN (:kept)
        """,
    )
    suspend fun unlinkServersExcept(kept: List<String>)

    // Deletes a playlist unless it changed since it was read.
    @Query("DELETE FROM playlist WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun deleteUnchangedPlaylist(id: String, updatedAt: Long)

    @Query("DELETE FROM playlist_item WHERE playlistId = :id")
    suspend fun clearPlaylistItems(id: String)

    @Update
    suspend fun updatePlaylistRow(row: PlaylistEntity)

    // A server playlist's phone copy, with its songs, in one go.
    @Transaction
    suspend fun insertServerPlaylist(row: PlaylistEntity, items: List<PlaylistItemEntity>) {
        insertPlaylist(row)
        items.chunked(500).forEach { insertPlaylistItems(it) }
    }

    // Swaps in the server's songs and saves the playlist as `updated`,
    // unless it changed on the phone since it was read as `expected`.
    // Answers whether it did.
    @Transaction
    suspend fun takeServerSongs(expected: PlaylistEntity, items: List<PlaylistItemEntity>, updated: PlaylistEntity): Boolean {
        val current = playlistRow(expected.id) ?: return false
        if (current.updatedAt != expected.updatedAt) return false
        clearPlaylistItems(expected.id)
        items.chunked(500).forEach { insertPlaylistItems(it) }
        updatePlaylistRow(updated)
        return true
    }

    // Its songs go with it.
    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position")
    suspend fun playlistItems(id: String): List<PlaylistItemEntity>

    // Answers the new rows' ids.
    @Insert
    suspend fun insertPlaylistItems(rows: List<PlaylistItemEntity>): List<Long>

    @Update
    suspend fun updatePlaylistItems(rows: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    suspend fun deletePlaylistItem(itemId: Long)

    // Answers the new rows' ids, so the add can be taken back.
    @Transaction
    suspend fun addToPlaylist(id: String, tracks: List<TrackEntity>, now: Long): List<Long> {
        val start = playlistItems(id).lastOrNull()?.position?.plus(1) ?: 0
        val added = insertPlaylistItems(appendedItems(id, start, tracks))
        touchPlaylist(id, now)
        return added
    }

    // Answers the row taken out, its position set to the place it had, so
    // it can be put back there. Null when it was already gone.
    @Transaction
    suspend fun removeFromPlaylist(id: String, itemId: Long, now: Long): PlaylistItemEntity? {
        val items = playlistItems(id)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return null
        deletePlaylistItem(itemId)
        val rest = playlistItems(id)
        savePlaces(rest, renumbered(rest))
        touchPlaylist(id, now)
        return items[index].copy(position = index)
    }

    // Takes out several rows at once, such as the songs just added.
    @Transaction
    suspend fun removeItemsFromPlaylist(id: String, itemIds: List<Long>, now: Long) {
        itemIds.forEach { deletePlaylistItem(it) }
        val rest = playlistItems(id)
        savePlaces(rest, renumbered(rest))
        touchPlaylist(id, now)
    }

    // Puts a row taken out back where it was, with its own id, unless the
    // playlist is gone or the row is back already.
    @Transaction
    suspend fun restoreToPlaylist(id: String, item: PlaylistItemEntity, now: Long) {
        if (playlistRow(id) == null) return
        val items = playlistItems(id)
        if (items.any { it.id == item.id }) return
        val after = restoredItems(items, item)
        insertPlaylistItems(after.filter { it.id == item.id })
        savePlaces(items, after.filter { it.id != item.id })
        touchPlaylist(id, now)
    }

    // Which of these songs each playlist has, to ask before a song goes on
    // one twice. At most 900 songs at a time, for the database's limit.
    @Query("SELECT DISTINCT playlistId, trackId FROM playlist_item WHERE trackId IN (:trackIds)")
    fun playlistHoldings(trackIds: List<String>): Flow<List<PlaylistHolding>>

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

    // Played songs counted from a moment on, for "most played" over a
    // stretch of time.
    @Query(
        """
        SELECT t.*, COUNT(*) AS plays, MAX(p.startedAt) AS lastPlayedAt
        FROM play_event p JOIN track t ON t.id = p.trackId
        WHERE p.startedAt >= :since
        GROUP BY t.id
        """,
    )
    fun playedTracksSince(since: Long): Flow<List<PlayedTrack>>

    // The latest plays one by one, newest first, of songs the library still has.
    @Query(
        """
        SELECT t.*, p.startedAt AS startedAt
        FROM play_event p JOIN track t ON t.id = p.trackId
        ORDER BY p.startedAt DESC
        LIMIT :limit
        """,
    )
    fun recentPlays(limit: Int): Flow<List<PlayedAt>>

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

    // After a rebuild, points rows at the library song a vanished song is
    // now a copy of: a song liked while it was only on the server follows it
    // once the phone's copy of it takes its place, whatever the two copies'
    // tags say. OR IGNORE skips a like that would duplicate one.
    @Query(
        """
        UPDATE OR IGNORE liked_track
        SET trackId = (SELECT s.mergedId FROM source_track s WHERE s.id = liked_track.trackId)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = liked_track.trackId)
        """,
    )
    suspend fun followMergedLikes()

    @Query(
        """
        UPDATE play_event
        SET trackId = (SELECT s.mergedId FROM source_track s WHERE s.id = play_event.trackId)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = play_event.trackId)
        """,
    )
    suspend fun followMergedPlays()

    @Query(
        """
        UPDATE playlist_item
        SET trackId = (SELECT s.mergedId FROM source_track s WHERE s.id = playlist_item.trackId)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = playlist_item.trackId)
        """,
    )
    suspend fun followMergedPlaylists()

    @Query(
        """
        UPDATE OR IGNORE track_rating
        SET trackId = (SELECT s.mergedId FROM source_track s WHERE s.id = track_rating.trackId)
        WHERE trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = track_rating.trackId)
        """,
    )
    suspend fun followMergedRatings()

    // A merged copy is followed first, since it is certain; the relink key
    // catches the rest.
    @Transaction
    suspend fun relinkAll() {
        followMergedLikes()
        followMergedPlays()
        followMergedPlaylists()
        followMergedRatings()
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
