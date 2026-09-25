package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM album ORDER BY addedAt DESC LIMIT :limit")
    fun recentAlbums(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomAlbums(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM artist ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomArtists(limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM album ORDER BY sortKey")
    fun albums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artist ORDER BY sortKey")
    fun artists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM track ORDER BY sortKey")
    fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM track WHERE albumId = :albumId ORDER BY discNo, trackNo, sortKey")
    fun albumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artist(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM album WHERE artistId = :artistId ORDER BY year DESC, sortKey")
    fun artistAlbums(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artist WHERE searchKey LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun searchArtists(q: String, limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM album WHERE searchKey LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun searchAlbums(q: String, limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM track WHERE searchKey LIKE '%' || :q || '%' ORDER BY sortKey LIMIT :limit")
    suspend fun searchTracks(q: String, limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM track WHERE sourceId = :sourceId")
    fun trackCount(sourceId: String): Flow<Int>

    @Query("DELETE FROM track WHERE sourceId = :sourceId")
    suspend fun deleteTracks(sourceId: String)

    @Query("DELETE FROM album WHERE sourceId = :sourceId")
    suspend fun deleteAlbums(sourceId: String)

    @Query("DELETE FROM artist WHERE sourceId = :sourceId")
    suspend fun deleteArtists(sourceId: String)

    @Insert
    suspend fun insertTracks(rows: List<TrackEntity>)

    @Insert
    suspend fun insertAlbums(rows: List<AlbumEntity>)

    @Insert
    suspend fun insertArtists(rows: List<ArtistEntity>)

    // Swaps everything one source contributed, all at once, so screens
    // never see a half-written library.
    @Transaction
    suspend fun replaceSource(
        sourceId: String,
        tracks: List<TrackEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ) {
        deleteTracks(sourceId)
        deleteAlbums(sourceId)
        deleteArtists(sourceId)
        insertArtists(artists)
        insertAlbums(albums)
        insertTracks(tracks)
    }
}
