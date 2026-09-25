package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

// What each source has, before merging. Only sources and the merge step use
// this; the screens read the merged library.
@Dao
interface SourceDao {
    @Query("SELECT DISTINCT sourceId FROM source_track UNION SELECT DISTINCT sourceId FROM source_album")
    suspend fun sourceIds(): List<String>

    @Query("SELECT * FROM source_track WHERE sourceId = :sourceId")
    suspend fun tracks(sourceId: String): List<SourceTrackEntity>

    @Query("SELECT * FROM source_album WHERE sourceId = :sourceId")
    suspend fun albums(sourceId: String): List<SourceAlbumEntity>

    @Query("SELECT * FROM source_artist WHERE sourceId = :sourceId")
    suspend fun artists(sourceId: String): List<SourceArtistEntity>

    // Every copy of one library song, from every source that has it.
    @Query("SELECT * FROM source_track WHERE mergedId = :trackId")
    suspend fun copies(trackId: String): List<SourceTrackEntity>

    // Every server copy already merged into a library song.
    @Query(
        """
        SELECT mergedId AS trackId, nativeId AS serverId, lastPlayedAt
        FROM source_track WHERE sourceId LIKE 'server:%' AND mergedId != ''
        """,
    )
    suspend fun serverCopies(): List<ServerCopy>
    // Every copy of many library songs at once, in no particular order.
    @Query("SELECT * FROM source_track WHERE mergedId IN (:trackIds)")
    suspend fun copiesOf(trackIds: List<String>): List<SourceTrackEntity>

    // Which of these server song ids are in the library, and the library
    // song each one is.
    @Query(
        """
        SELECT nativeId AS serverId, CASE WHEN mergedId = '' THEN id ELSE mergedId END AS trackId
        FROM source_track WHERE sourceId = :sourceId AND nativeId IN (:serverIds)
        """,
    )
    suspend fun libraryLinks(sourceId: String, serverIds: List<String>): List<ServerLink>

    // Which of these server album ids are in the library.
    @Query("SELECT nativeId FROM source_album WHERE sourceId = :sourceId AND nativeId IN (:serverIds)")
    suspend fun knownAlbums(sourceId: String, serverIds: List<String>): List<String>

    // Which of these artist rows, by "<source>:<server id>", are in the library.
    @Query("SELECT id FROM source_artist WHERE id IN (:ids)")
    suspend fun knownArtists(ids: List<String>): List<String>

    @Query("DELETE FROM source_track WHERE sourceId = :sourceId")
    suspend fun deleteTracks(sourceId: String)

    @Query("DELETE FROM source_album WHERE sourceId = :sourceId")
    suspend fun deleteAlbums(sourceId: String)

    @Query("DELETE FROM source_artist WHERE sourceId = :sourceId")
    suspend fun deleteArtists(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(rows: List<SourceTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(rows: List<SourceAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(rows: List<SourceArtistEntity>)

    // Swaps in everything one source has, in one go.
    @Transaction
    suspend fun replaceSource(
        sourceId: String,
        tracks: List<SourceTrackEntity>,
        albums: List<SourceAlbumEntity>,
        artists: List<SourceArtistEntity>,
    ) {
        deleteSource(sourceId)
        artists.chunked(500).forEach { insertArtists(it) }
        albums.chunked(500).forEach { insertAlbums(it) }
        tracks.chunked(500).forEach { insertTracks(it) }
    }

    @Transaction
    suspend fun deleteSource(sourceId: String) {
        deleteTracks(sourceId)
        deleteAlbums(sourceId)
        deleteArtists(sourceId)
    }
}
