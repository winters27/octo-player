package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// How many ids go in one lookup, well under what one query can take.
private const val LOOKUP_CHUNK = 500

// How many source albums or artists one read of the copy counts takes.
private const val COPY_PAGE = 200

// One source album (or artist) with how many of its songs are library
// songs, and the first and last library album (or artist) they went into
// by id: the same one unless its songs went to several.
data class CopySpan(val serverRowId: String, val firstId: String, val lastId: String, val songs: Int)

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

    // Favourite albums the library still has.
    @Query("SELECT a.*, l.likedAt AS likedAt FROM liked_album l JOIN album a ON a.id = l.albumId")
    fun likedAlbums(): Flow<List<LikedAlbum>>

    @Query("SELECT * FROM liked_album")
    suspend fun likedAlbumRows(): List<LikedAlbumEntity>

    // Favourite artists

    @Query("SELECT artistId FROM liked_artist")
    fun likedArtistIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeArtists(rows: List<LikedArtistEntity>)

    @Query("DELETE FROM liked_artist WHERE artistId = :artistId")
    suspend fun unlikeArtist(artistId: String)

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

    @Query("SELECT id, searchKey FROM album")
    suspend fun albumKeys(): List<KeyedId>

    @Query("SELECT id, searchKey FROM artist")
    suspend fun artistKeys(): List<KeyedId>

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

    // Which library album the songs of these source albums went into, and
    // how many, from any source: for following an album that merged into
    // another.
    @Query(
        """
        SELECT st.albumId AS serverRowId, t.albumId AS libraryId, COUNT(*) AS songs
        FROM source_track st JOIN track t ON t.id = st.mergedId
        WHERE st.albumId IN (:ids)
        GROUP BY st.albumId, t.albumId
        """,
    )
    suspend fun albumCopyCounts(ids: List<String>): List<CopyCount>

    // The same for source artists.
    @Query(
        """
        SELECT st.artistId AS serverRowId, t.artistId AS libraryId, COUNT(*) AS songs
        FROM source_track st JOIN track t ON t.id = st.mergedId
        WHERE st.artistId IN (:ids)
        GROUP BY st.artistId, t.artistId
        """,
    )
    suspend fun artistCopyCounts(ids: List<String>): List<CopyCount>

    // Pins

    @Query("SELECT * FROM pinned_item ORDER BY position")
    suspend fun pins(): List<PinnedItemEntity>

    // Pins whose album, artist or playlist the library still has, in row order.
    @Query(
        """
        SELECT * FROM pinned_item p
        WHERE (p.kind = 'album' AND p.itemId IN (SELECT id FROM album))
           OR (p.kind = 'artist' AND p.itemId IN (SELECT id FROM artist))
           OR (p.kind = 'playlist' AND p.itemId IN (SELECT id FROM playlist))
        ORDER BY p.position
        """,
    )
    fun shownPins(): Flow<List<PinnedItemEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM pinned_item p
        WHERE (p.kind = 'album' AND p.itemId IN (SELECT id FROM album))
           OR (p.kind = 'artist' AND p.itemId IN (SELECT id FROM artist))
           OR (p.kind = 'playlist' AND p.itemId IN (SELECT id FROM playlist))
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

    // Moving rows to a new id

    @Query("UPDATE OR IGNORE liked_album SET albumId = :to WHERE albumId = :from")
    suspend fun moveLikedAlbum(from: String, to: String)

    @Query("UPDATE OR IGNORE liked_artist SET artistId = :to WHERE artistId = :from")
    suspend fun moveLikedArtist(from: String, to: String)

    @Query("UPDATE OR IGNORE pinned_item SET itemId = :to WHERE kind = :kind AND itemId = :from")
    suspend fun movePin(kind: String, from: String, to: String)

    // After the library is rebuilt: favourites and pins whose album or
    // artist id vanished follow the one their songs merged into, or failing
    // that the one now filed under the same key.
    @Transaction
    suspend fun relinkAll() {
        val pins = pins()
        val likedAlbums = likedAlbumRows()
        val likedArtists = likedArtistRows()
        if (pins.isEmpty() && likedAlbums.isEmpty() && likedArtists.isEmpty()) return
        val albums = albumKeys()
        val albumIds = albums.mapTo(HashSet()) { it.id }
        val albumsByKey = albums.groupBy({ it.searchKey }, { it.id })
        val artists = artistKeys()
        val artistIds = artists.mapTo(HashSet()) { it.id }
        val artistsByKey = artists.groupBy({ it.searchKey }, { it.id })

        fun held(kind: PinKind) = pins.filter { it.kind == kind.id }.map { Held(it.itemId, it.relinkKey) }
        val heldAlbums = likedAlbums.map { Held(it.albumId, it.relinkKey) }
        val heldArtists = likedArtists.map { Held(it.artistId, it.relinkKey) }
        val pinnedAlbums = held(PinKind.Album)
        val pinnedArtists = held(PinKind.Artist)

        // Only the vanished ones are looked up, a few at a time.
        fun vanished(rows: List<Held>, present: Set<String>) = rows.map { it.id }.filter { it !in present }.distinct()
        val albumsMerged = mergedInto(vanished(heldAlbums + pinnedAlbums, albumIds).chunked(LOOKUP_CHUNK).flatMap { albumCopyCounts(it) })
        val artistsMerged = mergedInto(vanished(heldArtists + pinnedArtists, artistIds).chunked(LOOKUP_CHUNK).flatMap { artistCopyCounts(it) })

        relinks(heldAlbums, albumIds, albumsByKey, albumsMerged).forEach { moveLikedAlbum(it.from, it.to) }
        relinks(heldArtists, artistIds, artistsByKey, artistsMerged).forEach { moveLikedArtist(it.from, it.to) }
        relinks(pinnedAlbums, albumIds, albumsByKey, albumsMerged).forEach { movePin(PinKind.Album.id, it.from, it.to) }
        relinks(pinnedArtists, artistIds, artistsByKey, artistsMerged).forEach { movePin(PinKind.Artist.id, it.from, it.to) }
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
