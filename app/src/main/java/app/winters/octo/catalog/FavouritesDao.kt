package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    // Which library album each server album's songs went into, and how
    // many. A server album split over several library albums has a row
    // for each.
    @Query(
        """
        SELECT sa.id AS serverRowId, t.albumId AS libraryId, COUNT(*) AS songs
        FROM source_album sa JOIN source_track st ON st.albumId = sa.id JOIN track t ON t.id = st.mergedId
        WHERE sa.sourceId = :sourceId
        GROUP BY sa.id, t.albumId
        """,
    )
    suspend fun serverAlbumCounts(sourceId: String): List<CopyCount>

    // The same for a server's artists (a song's artist is its album
    // artist), plus server artists the library kept under their own id.
    @Query(
        """
        SELECT sr.id AS serverRowId, t.artistId AS libraryId, COUNT(*) AS songs
        FROM source_artist sr JOIN source_track st ON st.artistId = sr.id JOIN track t ON t.id = st.mergedId
        WHERE sr.sourceId = :sourceId
        GROUP BY sr.id, t.artistId
        UNION ALL
        SELECT sr.id, sr.id, 0 FROM source_artist sr JOIN artist r ON r.id = sr.id
        WHERE sr.sourceId = :sourceId
        """,
    )
    suspend fun serverArtistCounts(sourceId: String): List<CopyCount>

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
    // artist id vanished follow the one now filed under the same key.
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

        relinks(likedAlbums.map { Held(it.albumId, it.relinkKey) }, albumIds, albumsByKey)
            .forEach { moveLikedAlbum(it.from, it.to) }
        relinks(likedArtists.map { Held(it.artistId, it.relinkKey) }, artistIds, artistsByKey)
            .forEach { moveLikedArtist(it.from, it.to) }

        fun held(kind: PinKind) = pins.filter { it.kind == kind.id }.map { Held(it.itemId, it.relinkKey) }
        relinks(held(PinKind.Album), albumIds, albumsByKey).forEach { movePin(PinKind.Album.id, it.from, it.to) }
        relinks(held(PinKind.Artist), artistIds, artistsByKey).forEach { movePin(PinKind.Artist.id, it.from, it.to) }
    }
}
