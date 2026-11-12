package app.winters.octo.catalog

import androidx.annotation.VisibleForTesting
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// A constant so the query plan test reads the same text Room runs.
internal const val TRACKS_WITH_KEY_PAGE_SQL = "SELECT * FROM track WHERE searchKey = :key AND id > :after ORDER BY id LIMIT :limit"

@Dao
interface CatalogDao {
    @Query("SELECT * FROM album ORDER BY addedAt DESC LIMIT :limit")
    fun recentAlbums(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomAlbums(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM artist ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomArtists(limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM track ORDER BY sortKey")
    fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM album ORDER BY sortKey")
    fun albums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artist ORDER BY sortKey")
    fun artists(): Flow<List<ArtistEntity>>

    // Albums and artists in the order the listener chose. The queries are
    // built in sort/SortQueries.kt from fixed pieces only.

    @RawQuery(observedEntities = [AlbumEntity::class, ArtistEntity::class])
    fun sortedAlbums(query: RoomRawQuery): Flow<List<SortedAlbum>>

    @RawQuery(observedEntities = [ArtistEntity::class, TrackEntity::class])
    fun sortedArtists(query: RoomRawQuery): Flow<List<SortedArtist>>

    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM track WHERE id = :id")
    fun trackFlow(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    suspend fun tracksByIdsUnordered(ids: List<String>): List<TrackEntity>

    @Query("SELECT id FROM track WHERE albumId = :albumId ORDER BY albumOrder")
    suspend fun albumTrackIds(albumId: String): List<String>

    @Query("SELECT id FROM track ORDER BY sortKey")
    suspend fun allTrackIds(): List<String>

    @Query("SELECT * FROM track WHERE albumId = :albumId ORDER BY albumOrder")
    fun albumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artist(id: String): Flow<ArtistEntity?>

    // An artist's songs, album by album, newest album first, each in its
    // own order.
    @Query(
        """
        SELECT t.* FROM track t LEFT JOIN album a ON a.id = t.albumId
        WHERE t.artistId = :artistId
        ORDER BY COALESCE(a.year, 0) DESC, a.sortKey, t.albumId, t.albumOrder
        """,
    )
    fun artistTracks(artistId: String): Flow<List<TrackEntity>>

    // Every song's album and artist, album by album in the same order, for
    // playing whole lists of albums or artists.
    @Query(
        """
        SELECT t.id AS id, t.albumId AS albumId, t.artistId AS artistId
        FROM track t LEFT JOIN album a ON a.id = t.albumId
        ORDER BY COALESCE(a.year, 0) DESC, a.sortKey, t.albumId, t.albumOrder
        """,
    )
    suspend fun trackPlaces(): List<TrackPlace>

    @Query("SELECT * FROM album WHERE artistId = :artistId ORDER BY year DESC, sortKey")
    fun artistAlbums(artistId: String): Flow<List<AlbumEntity>>

    // Genres by name, leaving out songs with none. The genre column ignores
    // case, so spellings that differ only in case are one genre, walked in
    // the genre index's order with no sort; each shows its smallest
    // spelling.
    @Query(GENRES_SQL)
    fun genres(): Flow<List<GenreSummary>>

    // The albums holding a song of the genre, by name, each found by one
    // probe of its songs.
    @Query(GENRE_ALBUMS_SQL)
    fun genreAlbums(name: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artist WHERE searchKey LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun searchArtists(q: String, limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM album WHERE searchKey LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun searchAlbums(q: String, limit: Int): List<AlbumEntity>

    // Exact title, album and artist lookups, for telling whether something
    // the server found is already in the library. Songs come by key, then
    // by id, so the first of several alike is the same every time.
    @Query("SELECT * FROM track WHERE searchKey IN (:keys) ORDER BY searchKey, id")
    suspend fun tracksWithKeys(keys: List<String>): List<TrackEntity>

    // One page of the songs filed under one search key after id `after`,
    // in id order.
    @Query(TRACKS_WITH_KEY_PAGE_SQL)
    suspend fun tracksWithKeyPage(key: String, after: String, limit: Int): List<TrackEntity>

    @Query("SELECT * FROM album WHERE searchKey IN (:keys)")
    suspend fun albumsWithKeys(keys: List<String>): List<AlbumEntity>

    @Query("SELECT * FROM artist WHERE searchKey IN (:keys)")
    suspend fun artistsWithKeys(keys: List<String>): List<ArtistEntity>

    // Songs whose title holds the words, then those whose artist does (an
    // artist's name matched as typed, accents and all), each by title.
    @Query(
        "SELECT * FROM track WHERE searchKey LIKE '%' || :q || '%' OR lower(artist) LIKE '%' || :q || '%' " +
            "ORDER BY searchKey LIKE '%' || :q || '%' DESC, sortKey LIMIT :limit",
    )
    suspend fun searchTracks(q: String, limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM track WHERE sourceId = :sourceId")
    fun trackCount(sourceId: String): Flow<Int>

    // Every song in the library, from the phone and the server together.
    @Query("SELECT COUNT(*) FROM track")
    fun libraryTrackCount(): Flow<Int>

    @Query("DELETE FROM track")
    suspend fun deleteTracks()

    @Query("DELETE FROM album")
    suspend fun deleteAlbums()

    @Query("DELETE FROM artist")
    suspend fun deleteArtists()

    @Insert
    suspend fun insertTracks(rows: List<TrackEntity>)

    @Insert
    suspend fun insertAlbums(rows: List<AlbumEntity>)

    @Insert
    suspend fun insertArtists(rows: List<ArtistEntity>)

    // Every saved file's tags at once, for checks; a scan reads them a page
    // at a time.
    @VisibleForTesting
    @Query("SELECT * FROM file_tags")
    suspend fun fileTags(): List<FileTagsEntity>

    @Upsert
    suspend fun upsertFileTags(rows: List<FileTagsEntity>)

    @Query("DELETE FROM file_tags WHERE mediaId IN (:ids)")
    suspend fun deleteFileTags(ids: List<Long>)

    // Swaps in the whole merged library at once, so screens never see a
    // half-written one.
    @Transaction
    suspend fun replaceAll(tracks: List<TrackEntity>, albums: List<AlbumEntity>, artists: List<ArtistEntity>) {
        deleteTracks()
        deleteAlbums()
        deleteArtists()
        artists.chunked(500).forEach { insertArtists(it) }
        albums.chunked(500).forEach { insertAlbums(it) }
        tracks.chunked(500).forEach { insertTracks(it) }
    }
}
