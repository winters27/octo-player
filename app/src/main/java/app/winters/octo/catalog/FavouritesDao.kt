package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// How many source albums or artists one read of the copy counts takes.
private const val COPY_PAGE = 200

// One source album (or artist) with how many of its songs are library
// songs, and the first and last library album (or artist) they went into
// by id: the same one unless its songs went to several.
data class CopySpan(val serverRowId: String, val firstId: String, val lastId: String, val songs: Int)

// The latest favorite albums: one sort of the favorite albums that keeps
// only `limit` of them.
internal const val LATEST_LIKED_ALBUMS_SQL = """
    SELECT a.*, l.likedAt AS likedAt FROM liked_album l JOIN album a ON a.id = l.albumId
    ORDER BY l.likedAt DESC, l.albumId LIMIT :limit
"""

// The statements below are constants so the query plan test reads the
// same text Room runs. The source is matched with a unary + so the index on
// the album (or artist) id, not the one on the source, drives the walk and
// keeps it in id order; CROSS JOIN keeps source_track the outer table.

internal const val ALBUM_COPY_PAGE_SQL = """
    SELECT st.albumId AS serverRowId, MIN(t.albumId) AS firstId, MAX(t.albumId) AS lastId, COUNT(*) AS songs
    FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.albumId > :after AND st.albumId < :until AND +st.sourceId = :sourceId
      AND EXISTS (SELECT 1 FROM source_album sa WHERE sa.id = st.albumId AND sa.sourceId = :sourceId)
    GROUP BY st.albumId ORDER BY st.albumId LIMIT :limit
"""

internal const val ALBUM_LIBRARY_AFTER_SQL = """
    SELECT MIN(t.albumId) FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.albumId = :serverRowId AND t.albumId > :after
"""

internal const val ALBUM_SONGS_IN_SQL = """
    SELECT COUNT(*) FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.albumId = :serverRowId AND t.albumId = :libraryId
"""

internal const val ARTIST_COPY_PAGE_SQL = """
    SELECT st.artistId AS serverRowId, MIN(t.artistId) AS firstId, MAX(t.artistId) AS lastId, COUNT(*) AS songs
    FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.artistId > :after AND st.artistId < :until AND +st.sourceId = :sourceId
      AND EXISTS (SELECT 1 FROM source_artist sr WHERE sr.id = st.artistId AND sr.sourceId = :sourceId)
    GROUP BY st.artistId ORDER BY st.artistId LIMIT :limit
"""

internal const val ARTIST_LIBRARY_AFTER_SQL = """
    SELECT MIN(t.artistId) FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.artistId = :serverRowId AND t.artistId > :after
"""

internal const val ARTIST_SONGS_IN_SQL = """
    SELECT COUNT(*) FROM source_track st CROSS JOIN track t ON t.id = st.mergedId
    WHERE st.artistId = :serverRowId AND t.artistId = :libraryId
"""

// The ids of `kind` that show as a favorite: one liked itself (unless a
// rename by key of it does not reach its like), the id a rename that
// reaches a like made of it, and every old id renamed to an id that shows.
// While a save is part way a favorite may still be on its old id or
// already on its new one, so either shows it. One id may come more than
// once. The same statement for albums and for artists.
internal const val FAVOURITE_ALBUM_IDS_SQL = """
    SELECT l.albumId FROM liked_album l WHERE NOT EXISTS (SELECT 1 FROM save_rename r WHERE r.kind = 'album' AND r.`old` = l.albumId AND r.byKey = 1 AND r.`key` != l.relinkKey)
    UNION ALL
    SELECT r.`new` FROM liked_album l CROSS JOIN save_rename r ON r.kind = 'album' AND r.`old` = l.albumId WHERE r.byKey = 0 OR r.`key` = l.relinkKey
    UNION ALL
    SELECT o.`old` FROM liked_album l CROSS JOIN save_rename o ON o.kind = 'album' AND o.`new` = l.albumId
    UNION ALL
    SELECT o.`old` FROM liked_album l CROSS JOIN save_rename r ON r.kind = 'album' AND r.`old` = l.albumId CROSS JOIN save_rename o ON o.kind = 'album' AND o.`new` = r.`new`
    WHERE (r.byKey = 0 OR r.`key` = l.relinkKey) AND o.`old` != l.albumId
"""

internal const val FAVOURITE_ARTIST_IDS_SQL = """
    SELECT l.artistId FROM liked_artist l WHERE NOT EXISTS (SELECT 1 FROM save_rename r WHERE r.kind = 'artist' AND r.`old` = l.artistId AND r.byKey = 1 AND r.`key` != l.relinkKey)
    UNION ALL
    SELECT r.`new` FROM liked_artist l CROSS JOIN save_rename r ON r.kind = 'artist' AND r.`old` = l.artistId WHERE r.byKey = 0 OR r.`key` = l.relinkKey
    UNION ALL
    SELECT o.`old` FROM liked_artist l CROSS JOIN save_rename o ON o.kind = 'artist' AND o.`new` = l.artistId
    UNION ALL
    SELECT o.`old` FROM liked_artist l CROSS JOIN save_rename r ON r.kind = 'artist' AND r.`old` = l.artistId CROSS JOIN save_rename o ON o.kind = 'artist' AND o.`new` = r.`new`
    WHERE (r.byKey = 0 OR r.`key` = l.relinkKey) AND o.`old` != l.artistId
"""

// Pins whose album, artist or playlist the library has, in row order, each
// on the id it names now: a pin still on an old id that a rename reaches
// shows as the id it became.
private const val PIN_ID_NOW =
    "COALESCE((SELECT r.`new` FROM save_rename r WHERE r.kind = p.kind AND r.`old` = p.itemId AND (r.byKey = 0 OR r.`key` = p.relinkKey)), p.itemId)"

internal const val SHOWN_PINS_SQL = """
    SELECT p.kind AS kind, $PIN_ID_NOW AS itemId, p.relinkKey AS relinkKey, p.position AS position
    FROM pinned_item p
    WHERE (p.kind = 'album' AND $PIN_ID_NOW IN (SELECT id FROM album))
       OR (p.kind = 'artist' AND $PIN_ID_NOW IN (SELECT id FROM artist))
       OR (p.kind = 'playlist' AND p.itemId IN (SELECT id FROM playlist WHERE itemSet >= 0))
    ORDER BY p.position
"""

internal const val KEPT_ARTISTS_SQL = """
    SELECT sr.id AS serverRowId, sr.id AS libraryId, 0 AS songs FROM source_artist sr JOIN artist r ON r.id = sr.id
    WHERE sr.sourceId = :sourceId
"""

// Favourite albums and artists, and pins on Home.
@Dao
interface FavouritesDao {
    // Favourite albums

    @Query("SELECT albumId FROM liked_album")
    fun likedAlbumIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeAlbums(rows: List<LikedAlbumEntity>)

    @Query("DELETE FROM liked_album WHERE albumId = :albumId")
    suspend fun unlikeAlbum(albumId: String)

    // The albums that show as favorites (FAVOURITE_ALBUM_IDS_SQL), for the
    // screens' hearts.
    @Query(FAVOURITE_ALBUM_IDS_SQL)
    fun favouriteAlbumIds(): Flow<List<String>>

    @Query("DELETE FROM liked_album WHERE albumId = :old AND (:byKey = 0 OR relinkKey = :key)")
    suspend fun unlikeOldAlbum(old: String, byKey: Boolean, key: String)

    // Takes an album's favorite away, and that of every old id renamed to
    // it that its rename reaches.
    @Transaction
    suspend fun unlikeAlbumEither(albumId: String) {
        unlikeAlbum(albumId)
        for (r in renamedTo(RENAME_ALBUM, albumId)) unlikeOldAlbum(r.old, r.byKey, r.key)
    }

    // Favourite albums the library still has.
    @Query("SELECT a.*, l.likedAt AS likedAt FROM liked_album l JOIN album a ON a.id = l.albumId")
    fun likedAlbums(): Flow<List<LikedAlbum>>

    // The latest favorite albums the library still has, the newest
    // favorite first; of two made at once, the lower album id.
    @Query(LATEST_LIKED_ALBUMS_SQL)
    fun latestLikedAlbums(limit: Int): Flow<List<LikedAlbum>>

    @Query("SELECT * FROM liked_album")
    suspend fun likedAlbumRows(): List<LikedAlbumEntity>

    // Favourite artists

    @Query("SELECT artistId FROM liked_artist")
    fun likedArtistIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeArtists(rows: List<LikedArtistEntity>)

    @Query("DELETE FROM liked_artist WHERE artistId = :artistId")
    suspend fun unlikeArtist(artistId: String)

    // The artists that show as favorites, as for albums.
    @Query(FAVOURITE_ARTIST_IDS_SQL)
    fun favouriteArtistIds(): Flow<List<String>>

    @Query("DELETE FROM liked_artist WHERE artistId = :old AND (:byKey = 0 OR relinkKey = :key)")
    suspend fun unlikeOldArtist(old: String, byKey: Boolean, key: String)

    @Transaction
    suspend fun unlikeArtistEither(artistId: String) {
        unlikeArtist(artistId)
        for (r in renamedTo(RENAME_ARTIST, artistId)) unlikeOldArtist(r.old, r.byKey, r.key)
    }

    // The renames in force that made `id` of `kind` (SongIds).
    @Query(RENAMED_TO_SQL)
    suspend fun renamedTo(kind: String, id: String): List<SaveRenameEntity>

    // The id an album or artist names now, as it changes.
    @Query(CURRENT_ID_SQL)
    fun currentIdFlow(kind: String, id: String): Flow<String>

    // Favourite artists the library still has.
    @Query("SELECT r.*, l.likedAt AS likedAt FROM liked_artist l JOIN artist r ON r.id = l.artistId")
    fun likedArtists(): Flow<List<LikedArtist>>

    @Query("SELECT * FROM liked_artist")
    suspend fun likedArtistRows(): List<LikedArtistEntity>

    // Library albums and artists, for a relink key or to tell they exist

    @Query("SELECT id, searchKey FROM album WHERE id = :id")
    suspend fun albumKey(id: String): KeyedId?

    @Query("SELECT id, searchKey FROM artist WHERE id = :id")
    suspend fun artistKey(id: String): KeyedId?

    // One page of a source's albums after `after`, in id order, with their
    // library songs (see serverAlbumCounts). `until` ends the source's ids.
    @Query(ALBUM_COPY_PAGE_SQL)
    suspend fun albumCopyPage(sourceId: String, after: String, until: String, limit: Int): List<CopySpan>

    // The next library album, by id, that a source album's songs went into.
    @Query(ALBUM_LIBRARY_AFTER_SQL)
    suspend fun albumLibraryAfter(serverRowId: String, after: String): String?

    @Query(ALBUM_SONGS_IN_SQL)
    suspend fun albumSongsIn(serverRowId: String, libraryId: String): Int

    // The same for a source's artists (a song's artist is its album artist).
    @Query(ARTIST_COPY_PAGE_SQL)
    suspend fun artistCopyPage(sourceId: String, after: String, until: String, limit: Int): List<CopySpan>

    @Query(ARTIST_LIBRARY_AFTER_SQL)
    suspend fun artistLibraryAfter(serverRowId: String, after: String): String?

    @Query(ARTIST_SONGS_IN_SQL)
    suspend fun artistSongsIn(serverRowId: String, libraryId: String): Int

    // A source's artists the library kept under their own id.
    @Query(KEPT_ARTISTS_SQL)
    suspend fun keptServerArtists(sourceId: String): List<CopyCount>

    // Pins

    @Query("SELECT * FROM pinned_item ORDER BY position")
    suspend fun pins(): List<PinnedItemEntity>

    // Pins whose album, artist or playlist the library still has, in row
    // order, each on the id it names now (SHOWN_PINS_SQL); a playlist being
    // deleted is gone already. Two pins may name one id while a save is
    // part way.
    @Query(SHOWN_PINS_SQL)
    fun shownPins(): Flow<List<PinnedItemEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM pinned_item p
        WHERE (p.kind = 'album' AND p.itemId IN (SELECT id FROM album))
           OR (p.kind = 'artist' AND p.itemId IN (SELECT id FROM artist))
           OR (p.kind = 'playlist' AND p.itemId IN (SELECT id FROM playlist WHERE itemSet >= 0))
        """,
    )
    suspend fun shownPinCount(): Int

    @Query("DELETE FROM pinned_item")
    suspend fun clearPins()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPins(rows: List<PinnedItemEntity>)

    // Swaps in the whole row of pins in one go.
    @Transaction
    suspend fun savePins(rows: List<PinnedItemEntity>) {
        clearPins()
        insertPins(rows)
    }

    // Adds a pin at the end of the row. False when Home is full.
    @Transaction
    suspend fun pin(row: PinnedItemEntity): Boolean {
        val next = withPin(pins(), row.copy(position = Int.MAX_VALUE), shownPinCount()) ?: return false
        savePins(next)
        return true
    }

    @Transaction
    suspend fun unpin(kind: String, itemId: String) = savePins(withoutPin(pins(), kind, itemId))

    @Transaction
    suspend fun moveToFront(kind: String, itemId: String) = savePins(movedToFront(pins(), kind, itemId))

    // While a save is part way, the pin of an album or artist may still be
    // on an old id renamed to it: these reach every pin that stands for
    // `row` (or `itemId`), the old ones whose rename reaches them included.

    // Pins at the end of the row unless a pin already stands for it. False
    // when Home is full.
    @Transaction
    suspend fun pinEither(row: PinnedItemEntity): Boolean {
        val all = pins()
        if (all.any { it.kind == row.kind && it.itemId in standingFor(all, row.kind, row.itemId) }) return true
        return pin(row)
    }

    @Transaction
    suspend fun unpinEither(kind: String, itemId: String) {
        val all = pins()
        savePins(standingFor(all, kind, itemId).fold(all) { rows, id -> withoutPin(rows, kind, id) })
    }

    // Moves the first pin that stands for it to the front.
    @Transaction
    suspend fun moveToFrontEither(kind: String, itemId: String) {
        val all = pins()
        val ids = standingFor(all, kind, itemId)
        val first = all.inPinOrder().firstOrNull { it.kind == kind && it.itemId in ids } ?: return
        savePins(movedToFront(all, kind, first.itemId))
    }

    // The pinned ids of `kind` that stand for `itemId`: itself, and each old
    // id renamed to it whose rename reaches that pin.
    @Transaction
    suspend fun standingFor(all: List<PinnedItemEntity>, kind: String, itemId: String): Set<String> {
        if (kind == PinKind.Playlist.id) return setOf(itemId)
        val renamed = renamedTo(kind, itemId)
        return setOf(itemId) + renamed.filter { r -> all.any { it.kind == kind && it.itemId == r.old && r.reaches(it.relinkKey) } }.map { it.old }
    }
}

// Which library album each of a server's albums' songs went into, and how
// many. A server album split over several library albums has a row for
// each. Read a page of albums at a time, so what is held grows with the
// albums, not the songs.
suspend fun FavouritesDao.serverAlbumCounts(sourceId: String): List<CopyCount> =
    copyCounts(
        sourceId,
        page = { after, until -> albumCopyPage(sourceId, after, until, COPY_PAGE) },
        libraryAfter = { row, after -> albumLibraryAfter(row, after) },
        songsIn = { row, library -> albumSongsIn(row, library) },
    )

// The same for a server's artists, plus server artists the library kept
// under their own id.
suspend fun FavouritesDao.serverArtistCounts(sourceId: String): List<CopyCount> =
    copyCounts(
        sourceId,
        page = { after, until -> artistCopyPage(sourceId, after, until, COPY_PAGE) },
        libraryAfter = { row, after -> artistLibraryAfter(row, after) },
        songsIn = { row, library -> artistSongsIn(row, library) },
    ) + keptServerArtists(sourceId)

private suspend fun copyCounts(
    sourceId: String,
    page: suspend (after: String, until: String) -> List<CopySpan>,
    libraryAfter: suspend (serverRowId: String, after: String) -> String?,
    songsIn: suspend (serverRowId: String, libraryId: String) -> Int,
): List<CopyCount> {
    val counts = ArrayList<CopyCount>()
    // A source's row ids all start "<source>:", and ';' sorts right after ':'.
    var after = "$sourceId:"
    val until = "$sourceId;"
    while (true) {
        val spans = page(after, until)
        for (span in spans) {
            if (span.firstId == span.lastId) {
                counts += CopyCount(span.serverRowId, span.firstId, span.songs)
                continue
            }
            var library: String? = span.firstId
            while (library != null) {
                counts += CopyCount(span.serverRowId, library, songsIn(span.serverRowId, library))
                library = libraryAfter(span.serverRowId, library)
            }
        }
        if (spans.size < COPY_PAGE) return counts
        after = spans.last().serverRowId
    }
}
