package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Which of some songs are liked themselves: a like on an old id that a
// rename by key does not reach (another song's key) is left out. The list
// is bound once, so a chunk of 900 stays under old phones' 999 values.
internal const val LIKED_AMONG_SQL = """
    SELECT l.trackId FROM liked_track l WHERE l.trackId IN (:trackIds)
      AND NOT EXISTS (SELECT 1 FROM save_rename r WHERE r.kind = 'track' AND r.`old` = l.trackId AND r.byKey = 1 AND r.`key` != l.relinkKey)
"""

// Whether one song shows as liked: liked itself (unless a rename by key of
// it does not reach its like), renamed to a liked song, renamed to by an
// old id whose like the rename reaches, or renamed to the same song as such
// an old id. While a save is part way a song's like may still be on its old
// id or already on its new one, so either shows the heart.
internal const val LIKED_EITHER_SQL = """
    SELECT EXISTS (
        SELECT 1 FROM liked_track l WHERE l.trackId = :trackId
          AND NOT EXISTS (SELECT 1 FROM save_rename r WHERE r.kind = 'track' AND r.`old` = l.trackId AND r.byKey = 1 AND r.`key` != l.relinkKey)
        UNION ALL
        SELECT 1 FROM save_rename r CROSS JOIN liked_track l ON l.trackId = r.`new` WHERE r.kind = 'track' AND r.`old` = :trackId
        UNION ALL
        SELECT 1 FROM save_rename r CROSS JOIN liked_track l ON l.trackId = r.`old`
        WHERE r.kind = 'track' AND r.`new` = :trackId AND (r.byKey = 0 OR l.relinkKey = r.`key`)
        UNION ALL
        SELECT 1 FROM save_rename a CROSS JOIN save_rename r ON r.kind = a.kind AND r.`new` = a.`new` CROSS JOIN liked_track l ON l.trackId = r.`old`
        WHERE a.kind = 'track' AND a.`old` = :trackId AND r.`old` != a.`old` AND (r.byKey = 0 OR l.relinkKey = r.`key`)
    )
"""

// The song a library id names now, as CURRENT_ID_SQL gives it.
private const val CURRENT_SONG = "COALESCE((SELECT c.`new` FROM save_rename c WHERE c.kind = 'track' AND c.`old` = :trackId), :trackId)"

// The rating the phone keeps for one song: the one on the song it is now,
// else one an old id renamed to it still holds where its rename reaches it.
internal const val RATING_EITHER_SQL = """
    SELECT COALESCE(
        (SELECT t.rating FROM track_rating t WHERE t.trackId = $CURRENT_SONG),
        (SELECT t.rating FROM save_rename r CROSS JOIN track_rating t ON t.trackId = r.`old`
         WHERE r.kind = 'track' AND r.`new` = $CURRENT_SONG AND (r.byKey = 0 OR t.relinkKey = r.`key`) LIMIT 1))
"""

// How many songs of a playlist kept with a server have no copy there, so
// the server's copy goes without them. Each song's copy is looked for by
// the song: `+` keeps the planner off the indexes that start with the
// source, which would walk every copy the server has for each song.
internal const val PHONE_ONLY_COUNT_SQL = """
    SELECT COUNT(*) FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet
    WHERE p.id = :id AND p.itemSet >= 0 AND p.sourceId IS NOT NULL AND i.serverSongId IS NULL
      AND i.trackId NOT LIKE 'find:%'
      AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = i.trackId AND +s.sourceId = p.sourceId)
"""

// How many ids one lookup of likes or renames binds.
internal const val LIKE_LOOKUP_CHUNK = 900

// A song and the old ids renamed to it by follow, whose server copies are
// the song's copies until the save moves them.
internal fun copyIds(trackId: String, renamed: List<SaveRenameEntity>): List<String> = listOf(trackId) + renamed.filter { !it.byKey }.map { it.old }

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

    // The like an old id renamed to a song still holds, where its rename
    // reaches it.
    @Query("DELETE FROM liked_track WHERE trackId = :old AND (:byKey = 0 OR relinkKey = :key)")
    suspend fun unlikeOld(old: String, byKey: Boolean, key: String)

    // Takes a song's like away, and the like of every old id renamed to it
    // that its rename reaches.
    @Transaction
    suspend fun unlikeEither(trackId: String) {
        unlike(trackId)
        for (r in renamedTo(RENAME_SONG, trackId)) unlikeOld(r.old, r.byKey, r.key)
    }

    // Whether a song shows as liked (LIKED_AMONG_SQL).
    @Query(LIKED_EITHER_SQL)
    suspend fun isLiked(trackId: String): Boolean

    // Whether one song shows as liked, as it changes.
    @Query(LIKED_EITHER_SQL)
    fun likedFlow(trackId: String): Flow<Boolean>

    // Which of these songs are liked themselves, as that changes. At most
    // 900 songs at a time, for the database's limit.
    @Query(LIKED_AMONG_SQL)
    fun likedAmong(trackIds: List<String>): Flow<List<String>>

    @Query(LIKED_AMONG_SQL)
    suspend fun likedAmongNow(trackIds: List<String>): List<String>

    @Query("SELECT * FROM liked_track WHERE trackId IN (:trackIds)")
    suspend fun likedRows(trackIds: List<String>): List<LikedTrackEntity>

    // Which of these songs show as liked, as LIKED_EITHER_SQL tells it for
    // one: at most 900 songs, each statement binding at most that many.
    @Transaction
    suspend fun shownLikedAmong(trackIds: List<String>): Set<String> {
        val shown = likedAmongNow(trackIds).toHashSet()
        val renamed = songRenamesOf(trackIds)
        val olds = songsRenamedTo(trackIds)
        val targets = renamed.map { it.new }.distinct()
        val siblings = targets.chunked(LIKE_LOOKUP_CHUNK).flatMap { songsRenamedTo(it) }
        val related = (targets + siblings.map { it.old } + olds.map { it.old }).distinct()
        val likes = related.chunked(LIKE_LOOKUP_CHUNK).flatMap { likedRows(it) }.associate { it.trackId to it.relinkKey }
        fun reached(r: SaveRenameEntity) = likes[r.old]?.let { r.reaches(it) } == true
        val byTarget = siblings.groupBy { it.new }
        for (r in renamed) if (r.new in likes || byTarget[r.new].orEmpty().any(::reached)) shown += r.old
        for (r in olds) if (reached(r)) shown += r.new
        return shown
    }

    // A page of liked songs the catalog still has, newest like first, then
    // the higher id, from just past the song liked at `beforeAt` with id
    // `beforeId`: for the covers of the liked songs.
    @Query(LIKED_COVER_PAGE_SQL)
    suspend fun likedCoverPage(beforeAt: Long, beforeId: String, limit: Int): List<LikedCover>

    @Query("SELECT * FROM liked_track WHERE trackId = :trackId")
    suspend fun likedRow(trackId: String): LikedTrackEntity?

    // The like a song shows: its own, else one an old id renamed to it
    // holds where its rename reaches it.
    @Transaction
    suspend fun likedRowEither(trackId: String): LikedTrackEntity? =
        likedRow(trackId) ?: renamedTo(RENAME_SONG, trackId).firstNotNullOfOrNull { r -> likedRow(r.old)?.takeIf { r.reaches(it.relinkKey) } }

    // Renames in force (SongIds).

    @Query(CURRENT_ID_SQL)
    suspend fun currentId(kind: String, id: String): String

    @Query(CURRENT_ROW_ID_SQL)
    suspend fun currentRowId(kind: String, id: String, key: String): String

    @Query(RENAMED_TO_SQL)
    suspend fun renamedTo(kind: String, id: String): List<SaveRenameEntity>

    @Query(SONG_RENAMES_OF_SQL)
    suspend fun songRenamesOf(ids: List<String>): List<SaveRenameEntity>

    @Query(SONGS_RENAMED_TO_SQL)
    suspend fun songsRenamedTo(ids: List<String>): List<SaveRenameEntity>
    // Liked songs the catalog still has, newest like first.
    @Query("SELECT t.* FROM liked_track l JOIN track t ON t.id = l.trackId ORDER BY l.likedAt DESC")
    fun likedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM liked_track l JOIN track t ON t.id = l.trackId")
    fun likedCount(): Flow<Int>

    // Ratings made on the phone

    @Query("SELECT * FROM track_rating")
    suspend fun ratings(): List<TrackRatingEntity>

    // The rating the phone keeps for a song (RATING_EITHER_SQL).
    @Query(RATING_EITHER_SQL)
    suspend fun rating(trackId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rate(row: TrackRatingEntity)

    @Query("DELETE FROM track_rating WHERE trackId = :trackId")
    suspend fun unrate(trackId: String)

    // The rating an old id renamed to a song still holds, where its rename
    // reaches it.
    @Query("DELETE FROM track_rating WHERE trackId = :old AND (:byKey = 0 OR relinkKey = :key)")
    suspend fun unrateOld(old: String, byKey: Boolean, key: String)

    // The rating the library shows for one song.
    @Query("UPDATE track SET rating = :rating WHERE id = :trackId")
    suspend fun showRating(trackId: String, rating: Int)

    // The same for an old id's song still listed while a save is part way.
    @Query("UPDATE track SET rating = :rating WHERE id = :old AND (:byKey = 0 OR relinkKey = :key)")
    suspend fun showOldRating(old: String, byKey: Boolean, key: String, rating: Int)

    @Query("SELECT relinkKey FROM track WHERE id = :trackId")
    suspend fun relinkKeyOf(trackId: String): String?

    @Query("UPDATE source_track SET rating = :rating WHERE mergedId = :trackId AND sourceId LIKE 'server:%'")
    suspend fun setCopiesRating(trackId: String, rating: Int)

    // While a copy is being built or a merge is between its claim and its
    // swap, a song's server copies note their new rating, so the swap puts
    // it back over the one the copy or the merge read before.
    @Query(
        """
        INSERT OR REPLACE INTO rating_note (sourceRowId, rating)
        SELECT id, :rating FROM source_track
        WHERE mergedId = :trackId AND sourceId LIKE 'server:%'
          AND ((SELECT claimActive FROM merge_state WHERE id = 0) = 1
            OR EXISTS (SELECT 1 FROM source_meta WHERE stage != 'none'))
        """,
    )
    suspend fun noteCopiesRating(trackId: String, rating: Int)

    @Query(
        """
        INSERT OR REPLACE INTO rating_tap (sourceRowId, rating)
        SELECT id, :rating FROM source_track WHERE mergedId = :trackId AND sourceId LIKE 'server:%'
        """,
    )
    suspend fun noteTapOnCopies(trackId: String, rating: Int)

    // One change to a library song's rating: the library shows it; with a
    // relink key the phone keeps it as its own (0 takes the phone's away);
    // with `copies` the song's server copies are noted as rated too, so a
    // merge before the next sync still shows it. While a save is part way,
    // the change reaches what an old id renamed to the song still holds:
    // the phone's rating and the listed song where the rename reaches
    // them, and the copies of an old id renamed by follow.
    @Transaction
    suspend fun noteRating(trackId: String, rating: Int, relinkKey: String?, now: Long, copies: Boolean) {
        val renamed = renamedTo(RENAME_SONG, trackId)
        if (relinkKey != null) {
            for (r in renamed) unrateOld(r.old, r.byKey, r.key)
            if (rating == 0) unrate(trackId) else rate(TrackRatingEntity(trackId, relinkKey, rating, now))
        }
        showRating(trackId, rating)
        for (r in renamed) showOldRating(r.old, r.byKey, r.key, rating)
        if (copies) {
            for (id in copyIds(trackId, renamed)) {
                setCopiesRating(id, rating)
                noteCopiesRating(id, rating)
            }
        }
    }

    // A rating tapped on the phone: noted as noteRating does, and with it,
    // in the same step, kept as a tap the server has yet to confirm, so a
    // sync that reads the copies showing it still sends it.
    @Transaction
    suspend fun noteTap(trackId: String, rating: Int, relinkKey: String, now: Long) {
        noteRating(trackId, rating, relinkKey, now, copies = true)
        for (id in copyIds(trackId, renamedTo(RENAME_SONG, trackId))) noteTapOnCopies(id, rating)
    }

    @Query("SELECT COUNT(*) FROM source_track WHERE mergedId = :trackId AND sourceId LIKE 'server:%' AND COALESCE(rating, 0) != :rating")
    suspend fun copiesNotShowing(trackId: String, rating: Int): Int

    // A rating restored from a backup: a tap when a server copy differs from
    // it, otherwise only the phone's own (the server already has it).
    // Answers whether it is a tap, to be sent.
    @Transaction
    suspend fun noteRestored(trackId: String, rating: Int, relinkKey: String, now: Long): Boolean {
        val copies = copyIds(trackId, renamedTo(RENAME_SONG, trackId))
        if (copies.sumOf { copiesNotShowing(it, rating) } > 0) {
            noteTap(trackId, rating, relinkKey, now)
            return true
        }
        noteRating(trackId, rating, relinkKey, now, copies = false)
        // An older tap of another rating is overruled; one of this rating
        // still waits to be sent.
        for (id in copies) forgetOtherTaps(id, rating)
        return false
    }

    @Query("DELETE FROM rating_tap WHERE rating != :rating AND sourceRowId IN (SELECT id FROM source_track WHERE mergedId = :trackId)")
    suspend fun forgetOtherTaps(trackId: String, rating: Int)

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
        WHERE p.sourceId IS NULL AND i.itemSet = p.itemSet
        ORDER BY i.playlistId, i.ord, i.itemKey
        """,
    )
    suspend fun phonePlaylistSongKeys(): List<SongKeyRow>

    // Playlists, the one changed last first; one being deleted is left out
    // of every read of playlists as playlists.

    @Query("SELECT * FROM playlist WHERE itemSet >= 0 ORDER BY updatedAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id AND itemSet >= 0")
    fun playlist(id: String): Flow<PlaylistEntity?>

    // Every playlist as its list shows it, the one changed last first, then
    // by id; one being deleted is left out.
    @Query(PLAYLIST_ROWS_SQL)
    suspend fun playlistRows(): List<PlaylistRow>

    @Query("$PLAYLIST_ROW_COLUMNS WHERE id = :id AND itemSet >= 0")
    suspend fun playlistRowOf(id: String): PlaylistRow?

    // A playlist's song count and length over its current songs, and the
    // set and change they were counted at; null when it is gone.
    @Query(PLAYLIST_TOTALS_SQL)
    suspend fun playlistTotals(id: String): PlaylistTotals?

    // A page of a playlist's item set in play order, from just past the row
    // at (`afterOrd`, `afterKey`), each row with its song's album and cover:
    // for the playlist's picture.
    @Query(PLAYLIST_COVER_PAGE_SQL)
    suspend fun playlistCoverPage(id: String, itemSet: Long, afterOrd: Long, afterKey: Long, limit: Int): List<PlaylistCoverRow>

    // Whether a playlist's item set holds a song found online.
    @Query(PLAYLIST_HOLDS_FIND_SQL)
    suspend fun playlistHoldsFind(id: String, itemSet: Long): Boolean

    // Keeps a playlist's item set from being deleted while a walk of it
    // runs, one row per walker and playlist, inside a transaction of the
    // walk's own; PlaylistSets.releasePin lets it go.
    @Query("INSERT OR REPLACE INTO playlist_set_pin (owner, playlistId, itemSet) VALUES (:owner, :playlistId, :itemSet)")
    suspend fun pinPlaylistSet(owner: String, playlistId: String, itemSet: Long)

    // A playlist's songs in play order: library songs, and songs found
    // online shaped like them, the way asTrack shapes a find.
    @Query(
        """
        SELECT itemId, id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId,
            trackNo, discNo, year, durationMs, addedAt, mimeType, sizeBytes, artwork, uri, albumOrder,
            relinkKey, genre, onPhone, rating, explicit, artistSortKey, albumSortKey, lookupKey, looseKey
        FROM (
            SELECT i.id AS itemId, i.ord AS ord, i.itemKey AS itemKey, t.id, t.sourceId, t.nativeId, t.title, t.searchKey,
                t.sortKey, t.artist, t.artistId, t.album, t.albumId, t.trackNo, t.discNo, t.year, t.durationMs,
                t.addedAt, t.mimeType, t.sizeBytes, t.artwork, t.uri, t.albumOrder, t.relinkKey, t.genre,
                t.onPhone, t.rating, t.explicit, t.artistSortKey, t.albumSortKey, t.lookupKey, t.looseKey
            FROM playlist_item i JOIN track t ON t.id = i.trackId
            WHERE i.playlistId = :id
            UNION ALL
            SELECT i.id, i.ord, i.itemKey, o.id, o.sourceId, o.nativeId, o.title, LOWER(o.title), LOWER(o.title),
                o.artist, '', o.album, '', NULL, NULL, NULL, o.durationMs, 0, o.mimeType, NULL,
                CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END,
                NULL, 0, '', '', 0, 0, o.explicit, '', '', '', ''
            FROM playlist_item i JOIN online_song o ON o.id = i.trackId
            WHERE i.playlistId = :id
        )
        ORDER BY ord, itemKey
        """,
    )
    fun playlistTracks(id: String): Flow<List<PlaylistTrack>>

    @Query(PHONE_ONLY_COUNT_SQL)
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

    @Query("SELECT * FROM playlist WHERE id = :id AND itemSet >= 0")
    suspend fun playlistRow(id: String): PlaylistEntity?

    // Every playlist kept with this server, made there or waiting to be.
    @Query("SELECT * FROM playlist WHERE sourceId = :sourceId AND itemSet >= 0")
    suspend fun serverPlaylists(sourceId: String): List<PlaylistEntity>

    // Playlists only on the phone.
    @Query("SELECT * FROM playlist WHERE sourceId IS NULL AND itemSet >= 0")
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

    // The server's notice mark for its copy of a playlist.
    @Query("UPDATE playlist SET octoNotice = :notice WHERE id = :id")
    suspend fun markNotice(id: String, notice: String?)

    // Whether the server marks its copy as one of Octo's library upkeep lists, and which kind.
    @Query("UPDATE playlist SET upkeep = :upkeep, upkeepKind = :kind WHERE id = :id")
    suspend fun markUpkeep(id: String, upkeep: Boolean, kind: String?)

    // Whether the server marks its copy as an imported playlist's copy.
    @Query("UPDATE playlist SET octoImport = :octoImport WHERE id = :id")
    suspend fun markImport(id: String, octoImport: Boolean)

    // Whether a playlist's songs hold a song found online now.
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet
            WHERE p.id = :id AND p.itemSet >= 0 AND i.trackId >= 'find:' AND i.trackId < 'find;')
        """,
    )
    suspend fun holdsFindNow(id: String): Boolean

    // The keys of a playlist's rows of these songs: by the server id a row
    // came from, or by the song found online it is.
    @Query(
        """
        SELECT i.itemKey FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet
        WHERE p.id = :id AND p.itemSet >= 0 AND (i.serverSongId IN (:serverIds) OR i.trackId IN (:trackIds))
        """,
    )
    suspend fun rowKeysOf(id: String, serverIds: List<String>, trackIds: List<String>): List<Long>

    // Whether a server's playlists had their bases seeded (PlaylistSteps.ensureBase).
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_base_mark WHERE sourceId = :sourceId)")
    suspend fun baseSeeded(sourceId: String): Boolean

    @Query("INSERT OR REPLACE INTO playlist_base_mark (sourceId, at) VALUES (:sourceId, :at)")
    suspend fun markBaseSeeded(sourceId: String, at: Long)

    // Whether the server lists its copy of a playlist as a family playlist.
    @Query("UPDATE playlist SET family = :family WHERE id = :id")
    suspend fun markFamily(id: String, family: Boolean)

    // What the listener may do with a family playlist, as Octo's rows said at
    // the last sync.
    @Query(
        "UPDATE playlist SET familyMayAdd = :add, familyMayRemove = :remove, familyMayRename = :rename, " +
            "familyMayDelete = :delete WHERE id = :id",
    )
    suspend fun markFamilyRights(id: String, add: Boolean, remove: Boolean, rename: Boolean, delete: Boolean)

    // Both sides now agree on what the playlist was at `syncedAt`.
    @Query("UPDATE playlist SET syncedAt = :syncedAt, syncedName = :name, serverStamp = :stamp WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long, name: String, stamp: String)

    // A playlist's songs, its current item set, in play order, whole: for
    // checks only. Pages and edits read a set a page or a row at a time.
    @Query(
        "SELECT * FROM playlist_item WHERE playlistId = :id AND itemSet = (SELECT itemSet FROM playlist WHERE id = :id) " +
            "ORDER BY ord, itemKey",
    )
    suspend fun playlistItems(id: String): List<PlaylistItemEntity>

    // Answers the new rows' ids.
    @Insert
    suspend fun insertPlaylistItems(rows: List<PlaylistItemEntity>): List<Long>

    // Takes a block of `n` playlist row keys: the first is nextItemKey's
    // value before the block.
    @Query("UPDATE playlist_meta SET nextItemKey = nextItemKey + :n WHERE id = 0")
    suspend fun raiseNextItemKey(n: Int)

    @Query("SELECT nextItemKey FROM playlist_meta WHERE id = 0")
    suspend fun nextItemKey(): Long

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

    // The songs of one page of a server folder level, the rows from `from`
    // up to `until` of `owner`, name the songs they resolved to: a song
    // merged into another is followed, as an album's listing follows it,
    // and a song gone is relinked by its key.
    @Query(FOLLOW_SERVER_DIRS_SQL)
    suspend fun followMergedServerDirs(owner: String, from: Long, until: Long): Int

    @Query(RELINK_SERVER_DIRS_SQL)
    suspend fun relinkServerDirs(owner: String, from: Long, until: Long): Int
}

// The song rows of a page of a server folder level whose song is gone,
// sought by owner and place: each follows its copy to the song that copy
// merged into, or else moves to the first song with its relink key.
internal const val FOLLOW_SERVER_DIRS_SQL = """
    UPDATE lists_server_dir SET trackId = (SELECT s.mergedId FROM source_track s WHERE s.id = lists_server_dir.trackId)
    WHERE owner = :owner AND position >= :from AND position < :until AND kind = 1
      AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = lists_server_dir.trackId)
      AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = lists_server_dir.trackId)
"""

internal const val RELINK_SERVER_DIRS_SQL = """
    UPDATE lists_server_dir SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = lists_server_dir.relinkKey ORDER BY t.id LIMIT 1)
    WHERE owner = :owner AND position >= :from AND position < :until AND kind = 1
      AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = lists_server_dir.trackId)
      AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = lists_server_dir.relinkKey AND t.relinkKey != '')
"""
