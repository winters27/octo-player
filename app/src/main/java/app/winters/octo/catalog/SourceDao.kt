package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

// How many rows one write or lookup takes at a time.
private const val WRITE_CHUNK = 500

// One source song's row id and its id on the source.
data class SourceRowId(val id: String, val nativeId: String)

// The rows a take-in wrote, by row id.
data class TakenIn(val tracks: List<String>, val albums: List<String>, val artists: List<String>) {
    val isEmpty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

// What each source has, before merging. Only sources and the merge step use
// this; the screens read the merged library.
//
// Every write keeps each row's place in its source's order (seq) and its
// song keys, and raises the source's stamp in the same transaction when a
// row changed, so the merge can tell when nothing did.
@Dao
interface SourceDao {
    @Query("SELECT DISTINCT sourceId FROM source_track UNION SELECT DISTINCT sourceId FROM source_album")
    suspend fun sourceIds(): List<String>

    // Every row one source has, whole. Only readers not yet paged call it.
    @Query("SELECT * FROM source_track WHERE sourceId = :sourceId")
    suspend fun tracks(sourceId: String): List<SourceTrackEntity>

    // Whether a source marks any song as in a family member's own library.
    @Query("SELECT EXISTS(SELECT 1 FROM source_track WHERE sourceId = :sourceId AND personal = 1)")
    suspend fun marksPersonal(sourceId: String): Boolean

    // Every copy of one library song, from every source that has it.
    @Query("SELECT * FROM source_track WHERE mergedId = :trackId")
    suspend fun copies(trackId: String): List<SourceTrackEntity>

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

    // A source album's own id on its source, by the id the library keeps.
    @Query("SELECT nativeId FROM source_album WHERE id = :id")
    suspend fun albumNativeId(id: String): String?

    // Which of these server album ids are in the library.
    @Query("SELECT nativeId FROM source_album WHERE sourceId = :sourceId AND nativeId IN (:serverIds)")
    suspend fun knownAlbums(sourceId: String, serverIds: List<String>): List<String>

    // How many songs the server listed in each of these albums the library has.
    @Query("SELECT nativeId AS serverId, songCount FROM source_album WHERE sourceId = :sourceId AND nativeId IN (:serverIds)")
    suspend fun albumSongCounts(sourceId: String, serverIds: List<String>): List<ServerAlbumCount>

    // One source's artists filed under this name.
    @Query("SELECT * FROM source_artist WHERE sourceId = :sourceId AND searchKey = :key")
    suspend fun artistsNamed(sourceId: String, key: String): List<SourceArtistEntity>

    // Which of these artist rows, by "<source>:<server id>", are in the library.
    @Query("SELECT id FROM source_artist WHERE id IN (:ids)")
    suspend fun knownArtists(ids: List<String>): List<String>

    // Rows by id, for comparing what a write brings with what is there.

    @Query("SELECT * FROM source_track WHERE id IN (:ids)")
    suspend fun tracksById(ids: List<String>): List<SourceTrackEntity>

    @Query("SELECT * FROM source_album WHERE id IN (:ids)")
    suspend fun albumsById(ids: List<String>): List<SourceAlbumEntity>

    // One source's songs, a page at a time in the order of their ids there.
    @Query("SELECT id, nativeId FROM source_track WHERE sourceId = :sourceId AND nativeId > :after ORDER BY nativeId LIMIT :limit")
    suspend fun trackIdPage(sourceId: String, after: String, limit: Int): List<SourceRowId>

    @Query("SELECT id FROM source_album WHERE sourceId = :sourceId")
    suspend fun albumIds(sourceId: String): List<String>

    @Query("SELECT id FROM source_artist WHERE sourceId = :sourceId")
    suspend fun artistIds(sourceId: String): List<String>

    // The last place taken in each table of a source's order.

    @Query("SELECT MAX(seq) FROM source_track WHERE sourceId = :sourceId")
    suspend fun lastTrackSeq(sourceId: String): Long?

    @Query("SELECT MAX(seq) FROM source_album WHERE sourceId = :sourceId")
    suspend fun lastAlbumSeq(sourceId: String): Long?

    @Query("SELECT MAX(seq) FROM source_artist WHERE sourceId = :sourceId")
    suspend fun lastArtistSeq(sourceId: String): Long?

    @Query("DELETE FROM source_track WHERE id IN (:ids)")
    suspend fun deleteTracksById(ids: List<String>)

    @Query("DELETE FROM source_album WHERE id IN (:ids)")
    suspend fun deleteAlbumsById(ids: List<String>)

    @Query("DELETE FROM source_artist WHERE id IN (:ids)")
    suspend fun deleteArtistsById(ids: List<String>)

    @Query("DELETE FROM source_track WHERE sourceId = :sourceId")
    suspend fun deleteTracks(sourceId: String)

    @Query("DELETE FROM source_album WHERE sourceId = :sourceId")
    suspend fun deleteAlbums(sourceId: String)

    @Query("DELETE FROM source_artist WHERE sourceId = :sourceId")
    suspend fun deleteArtists(sourceId: String)

    @Query("DELETE FROM stage_track WHERE sourceId = :sourceId")
    suspend fun deleteStagedTracks(sourceId: String)

    @Query("DELETE FROM stage_album WHERE sourceId = :sourceId")
    suspend fun deleteStagedAlbums(sourceId: String)

    @Query("DELETE FROM stage_artist WHERE sourceId = :sourceId")
    suspend fun deleteStagedArtists(sourceId: String)

    @Query("DELETE FROM source_meta WHERE sourceId = :sourceId")
    suspend fun forgetMeta(sourceId: String)

    @Query(
        """
        INSERT OR IGNORE INTO source_meta (sourceId, copyOf, stageOwner, stamp, stage, generation, stagedTracks, stagedAlbums, stagedArtists)
        VALUES (:sourceId, NULL, NULL, 0, 'none', 0, 0, 0, 0)
        """,
    )
    suspend fun ensureMeta(sourceId: String)

    @Query("UPDATE source_meta SET stamp = stamp + 1 WHERE sourceId = :sourceId")
    suspend fun raiseStamp(sourceId: String)

    // Notes that a source's rows changed.
    @Transaction
    suspend fun bumpStamp(sourceId: String) {
        ensureMeta(sourceId)
        raiseStamp(sourceId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTracks(rows: List<SourceTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlbums(rows: List<SourceAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtists(rows: List<SourceArtistEntity>)

    // Each answer is the new row's number, or -1 where the row was there already.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTracks(rows: List<SourceTrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addArtists(rows: List<SourceArtistEntity>): List<Long>

    // Makes one source's rows what it has now, in one go: rows it no longer
    // has go, changed ones are written again, unchanged ones are left alone,
    // each in the place the lists give it. A changed row keeps the library
    // song it was merged into until the next merge says otherwise.
    @Transaction
    suspend fun replaceSource(
        sourceId: String,
        tracks: List<SourceTrackEntity>,
        albums: List<SourceAlbumEntity>,
        artists: List<SourceArtistEntity>,
    ) {
        var changed = false
        // What goes, first, while every row is still where it was.
        val keepTracks = tracks.mapTo(HashSet()) { it.id }
        var after = ""
        while (true) {
            val page = trackIdPage(sourceId, after, WRITE_CHUNK)
            if (page.isEmpty()) break
            val gone = page.filter { it.id !in keepTracks }.map { it.id }
            if (gone.isNotEmpty()) {
                deleteTracksById(gone)
                changed = true
            }
            after = page.last().nativeId
        }
        val keepAlbums = albums.mapTo(HashSet()) { it.id }
        albumIds(sourceId).filter { it !in keepAlbums }.chunked(WRITE_CHUNK).forEach {
            deleteAlbumsById(it)
            changed = true
        }
        val keepArtists = artists.mapTo(HashSet()) { it.id }
        artistIds(sourceId).filter { it !in keepArtists }.chunked(WRITE_CHUNK).forEach {
            deleteArtistsById(it)
            changed = true
        }

        tracks.mapIndexed { index, track -> track.copy(seq = index.toLong(), mergedId = "").withKeys() }
            .chunked(WRITE_CHUNK)
            .forEach { chunk ->
                val before = tracksById(chunk.map { it.id }).associateBy { it.id }
                val write = chunk.mapNotNull { row ->
                    val old = before[row.id]
                    if (old != null && old.copy(mergedId = "") == row) null else row.copy(mergedId = old?.mergedId.orEmpty())
                }
                if (write.isNotEmpty()) {
                    putTracks(write)
                    changed = true
                }
            }
        albums.mapIndexed { index, album -> album.copy(seq = index.toLong()) }
            .chunked(WRITE_CHUNK)
            .forEach { chunk ->
                val before = albumsById(chunk.map { it.id }).associateBy { it.id }
                val write = chunk.filter { before[it.id] != it }
                if (write.isNotEmpty()) {
                    putAlbums(write)
                    changed = true
                }
            }
        artists.mapIndexed { index, artist -> artist.copy(seq = index.toLong()) }
            .chunked(WRITE_CHUNK)
            .forEach { chunk ->
                val before = artistsById(chunk.map { it.id }).associateBy { it.id }
                val write = chunk.filter { before[it.id] != it }
                if (write.isNotEmpty()) {
                    putArtists(write)
                    changed = true
                }
            }
        if (changed) bumpStamp(sourceId)
    }

    @Query("SELECT * FROM source_artist WHERE id IN (:ids)")
    suspend fun artistsById(ids: List<String>): List<SourceArtistEntity>

    // Puts in rows taken in ahead of a full copy: songs and artists the
    // source lacks, after its last ones, and albums it lacks. An album in
    // `replaceAlbums` (by its id on the source) was read whole just now, so
    // its kept row takes the new details in its old place. Answers what was
    // written.
    @Transaction
    suspend fun takeInRows(
        sourceId: String,
        tracks: List<SourceTrackEntity>,
        albums: List<SourceAlbumEntity>,
        replaceAlbums: Set<String>,
        artists: List<SourceArtistEntity>,
    ): TakenIn {
        var trackSeq = lastTrackSeq(sourceId) ?: -1
        val trackRows = tracks.map { it.copy(seq = ++trackSeq, mergedId = "").withKeys() }
        val tookTracks = addTracks(trackRows).zip(trackRows).filter { (rowId, _) -> rowId != -1L }.map { it.second.id }

        var artistSeq = lastArtistSeq(sourceId) ?: -1
        val artistRows = artists.map { it.copy(seq = ++artistSeq) }
        val tookArtists = addArtists(artistRows).zip(artistRows).filter { (rowId, _) -> rowId != -1L }.map { it.second.id }

        var albumSeq = lastAlbumSeq(sourceId) ?: -1
        val kept = albums.map { it.id }.chunked(WRITE_CHUNK).flatMap { albumsById(it) }.associateBy { it.id }
        val tookAlbums = ArrayList<String>()
        for (album in albums) {
            val old = kept[album.id]
            val row = when {
                old == null -> album.copy(seq = ++albumSeq)
                album.nativeId in replaceAlbums && old != album.copy(seq = old.seq) -> album.copy(seq = old.seq)
                else -> continue
            }
            putAlbums(listOf(row))
            tookAlbums += album.id
        }

        val taken = TakenIn(tookTracks, tookAlbums, tookArtists)
        if (!taken.isEmpty) bumpStamp(sourceId)
        return taken
    }

    // Puts in rows taken in ahead of a full copy, a table at a time, as
    // takeInRows does; an album given here replaces the kept one.
    @Transaction
    suspend fun insertTracks(rows: List<SourceTrackEntity>) {
        rows.groupBy { it.sourceId }.forEach { (source, group) -> takeInRows(source, group, emptyList(), emptySet(), emptyList()) }
    }

    @Transaction
    suspend fun insertAlbums(rows: List<SourceAlbumEntity>) {
        rows.groupBy { it.sourceId }.forEach { (source, group) ->
            takeInRows(source, emptyList(), group, group.mapTo(HashSet()) { it.nativeId }, emptyList())
        }
    }

    @Transaction
    suspend fun insertArtists(rows: List<SourceArtistEntity>) {
        rows.groupBy { it.sourceId }.forEach { (source, group) -> takeInRows(source, emptyList(), emptyList(), emptySet(), group) }
    }

    @Query("DELETE FROM rating_tap WHERE sourceRowId IN (SELECT id FROM source_track WHERE sourceId = :sourceId)")
    suspend fun deleteTaps(sourceId: String)

    // Takes a source away: its rows, its taps, any copy of it being built,
    // and what was known of it.
    @Transaction
    suspend fun deleteSource(sourceId: String) {
        deleteTaps(sourceId)
        deleteTracks(sourceId)
        deleteAlbums(sourceId)
        deleteArtists(sourceId)
        deleteStagedTracks(sourceId)
        deleteStagedAlbums(sourceId)
        deleteStagedArtists(sourceId)
        forgetMeta(sourceId)
    }
}
