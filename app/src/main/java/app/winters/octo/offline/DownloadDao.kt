package app.winters.octo.offline

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.sortKey
import app.winters.octo.ui.paging.Utf16Key
import kotlinx.coroutines.flow.Flow

// Where a download is.
enum class DownloadStatus { Queued, Downloading, Done, Failed }

// A server song kept on the phone as a real file, so it plays with no
// connection. It is held for one or more reasons (see Reasons); once none
// is left, the file goes. Keyed by the server's song, which never changes;
// `trackId` is the library song it is, kept up to date after each sync.
// Each order of the Downloads page has its index: downloads not yet done
// newest first, then done ones by title, size or when they were added,
// each ending in the row's own key so no two rows tie.
@Entity(
    tableName = "download",
    primaryKeys = ["sourceId", "serverId"],
    indices = [
        Index("trackId"), Index("state"),
        Index("sourceId", "state", "addedAt", "title", "serverId", name = "index_download_active"),
        Index("sourceId", "state", "titleKey", "trackId", "serverId", name = "index_download_done_title"),
        Index("sourceId", "state", "sizeBytes", "titleKey", "trackId", "serverId", name = "index_download_done_size"),
        Index("sourceId", "state", "addedAt", "titleKey", "trackId", "serverId", name = "index_download_done_added"),
    ],
)
data class DownloadEntity(
    val trackId: String,
    val sourceId: String,
    val serverId: String,
    // The finished file, or empty until there is one.
    val path: String,
    val sizeBytes: Long,
    // What the file is: the server's type, or MP3 when a smaller one was asked for.
    val format: String?,
    val state: DownloadStatus,
    // From 0 to 1 while it downloads.
    val progress: Float,
    val addedAt: Long,
    // Why it is kept: see Reasons.
    val reason: String,
    // The song's name, for the Downloads page and the file.
    val title: String,
    val artist: String,
    // The server's size for the file the download was made from, so a new
    // file under the same id is told apart. Downloads made before it was
    // noted have none until the first check after a sync.
    val fileSize: Long? = null,
) {
    // The title as the page sorts it, in an order the database keeps. It
    // follows from the title, so it takes no part in comparing two rows.
    var titleKey: ByteArray = Utf16Key(sortKey(title))
}

// A finished download beside its server song as the last sync saw it.
data class DownloadedFile(
    val sourceId: String,
    val serverId: String,
    val format: String?,
    val sizeBytes: Long,
    val fileSize: Long?,
    val serverSize: Long?,
    val serverType: String?,
    // Where the file is: its name says whether it was made smaller.
    val path: String = "",
)

// A download with the reasons it is kept, split out.
val DownloadEntity.reasons: Set<String> get() = Reasons.parse(reason)

// A download as the Downloads page shows it: with its artwork and length.
data class DownloadRow(
    val trackId: String,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val sizeBytes: Long,
    val state: DownloadStatus,
    val progress: Float,
    val reason: String,
    // When it was asked for, for ordering the list.
    val addedAt: Long = 0,
    // Whether the song is marked explicit, while the library has it.
    val explicit: Boolean? = null,
)

// A playlist song, for keeping playlists downloaded.
data class PlaylistMember(val playlistId: String, val trackId: String)

// A library song the downloads of one server hold, with every reason they
// are kept for, comma separated.
data class HeldSong(val trackId: String, val reasons: String)

// One download's library song and why it is kept.
data class SongReason(val trackId: String, val reason: String)

// A playlist and the item set it shows.
data class PlaylistSet(val id: String, val itemSet: Long)

// One of an album's songs, where it comes in the album.
data class AlbumSong(val albumOrder: Int, val id: String)

// A download with where it stands in the order rows were written.
data class NumberedDownload(@Embedded val row: DownloadEntity, val written: Long)

// The songs one server's downloads hold, a page at a time in song id
// order from just past `after`, each with its reasons. The server is no
// index's lead here (+), so the songs' index leads and gives the order.
internal const val HELD_PAGE_SQL = """
    SELECT trackId, group_concat(reason, ',') AS reasons FROM download
    WHERE +sourceId = :sourceId AND trackId > :after
    GROUP BY trackId ORDER BY trackId LIMIT :limit
"""

internal const val LIKED_PAGE_SQL = "SELECT trackId FROM liked_track WHERE trackId > :after ORDER BY trackId LIMIT :limit"

// The songs of one item set of a playlist, each once, in song id order.
internal const val SET_SONG_PAGE_SQL = """
    SELECT trackId FROM playlist_item
    WHERE playlistId = :playlistId AND itemSet = :itemSet AND trackId > :after
    GROUP BY trackId ORDER BY trackId LIMIT :limit
"""

internal const val REASONS_OF_SQL = "SELECT trackId, reason FROM download WHERE +sourceId = :sourceId AND trackId IN (:trackIds)"

// Playlists still shown among these, with the item set each shows.
internal const val PLAYLIST_SETS_SQL = "SELECT id, itemSet FROM playlist WHERE id IN (:ids) AND itemSet >= 0"

// Which of these songs the library has. At most 900 at a time.
internal const val LIBRARY_AMONG_SQL = "SELECT id FROM track WHERE id IN (:trackIds)"

// An album's songs in the album's order, then by id, from just past the
// song at (`afterOrder`, `afterId`).
internal const val ALBUM_SONG_PAGE_SQL = """
    SELECT albumOrder, id FROM track
    WHERE albumId = :albumId AND albumOrder >= :afterOrder AND CASE WHEN albumOrder = :afterOrder THEN id > :afterId ELSE 1 END
    ORDER BY albumOrder, id LIMIT :limit
"""

// One server's finished downloads, each with its server song, a page at a
// time in the server's id order from just past `after`.
internal const val DONE_FILES_PAGE_SQL = """
    SELECT d.sourceId, d.serverId, d.format, d.sizeBytes, d.fileSize, s.sizeBytes AS serverSize, s.mimeType AS serverType, d.path
    FROM download d CROSS JOIN source_track s ON s.sourceId = d.sourceId AND s.nativeId = d.serverId
    WHERE +d.state = 'Done' AND d.sourceId = :sourceId AND d.serverId > :after
    ORDER BY d.sourceId, d.serverId LIMIT :limit
"""

// Downloads written from just past `after` up to `last`, in the order
// they were written.
internal const val NUMBERED_PAGE_SQL = """
    SELECT *, rowid AS written FROM download WHERE rowid > :after AND rowid <= :last ORDER BY rowid LIMIT :limit
"""

// One server's downloads in its id order, from just past `after`.
internal const val SOURCE_PAGE_SQL = """
    SELECT * FROM download WHERE sourceId = :sourceId AND serverId > :after ORDER BY sourceId, serverId LIMIT :limit
"""

// Whether the download under this key has finished. The whole key is
// asked for, so the key's own index answers it with one row; a list of
// ids would let a server's other indexes walk all its downloads.
internal const val KEY_DONE_SQL = """
    SELECT EXISTS(SELECT 1 FROM download WHERE sourceId = :sourceId AND serverId = :serverId AND +state = 'Done')
"""

// The library song a download's server song is part of now; null when the
// last sync no longer has it.
private const val FOLLOWED_SONG = """
    (SELECT CASE WHEN s.mergedId = '' THEN s.id ELSE s.mergedId END FROM source_track s
     WHERE s.sourceId = download.sourceId AND s.nativeId = download.serverId LIMIT 1)
"""

// Each download follows its server song to the library song it is part of
// now. Only a row whose song changed is written, so with nothing to
// follow nothing is.
internal const val RELINK_SQL = """
    UPDATE download SET trackId = COALESCE($FOLLOWED_SONG, trackId)
    WHERE trackId IS NOT COALESCE($FOLLOWED_SONG, trackId)
"""

// Which of some library songs have a finished download, from any server.
// The state is no index's lead here (+), so the songs lead, never a walk of
// every finished download.
internal const val DONE_AMONG_SQL = "SELECT trackId FROM download WHERE +state = 'Done' AND trackId IN (:trackIds)"

internal const val DONE_FOR_SQL = "SELECT * FROM download WHERE +state = 'Done' AND trackId IN (:trackIds)"

internal const val IS_DONE_SQL = "SELECT EXISTS(SELECT 1 FROM download WHERE trackId = :trackId AND +state = 'Done')"

// The downloads of some library songs, a song's rows in the order they
// were written, so the last one written is the one kept for the song.
internal const val KEPT_AMONG_SQL = "SELECT * FROM download WHERE trackId IN (:trackIds) ORDER BY trackId, rowid"

// The download of one library song written last.
internal const val KEPT_ROW_SQL = "SELECT * FROM download WHERE trackId = :trackId ORDER BY rowid DESC LIMIT 1"

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM download")
    fun allFlow(): Flow<List<DownloadEntity>>

    @Query(
        """
        SELECT d.trackId, d.sourceId, d.serverId, d.title, d.artist, t.artwork, d.sizeBytes, d.state, d.progress, d.reason,
            d.addedAt, t.explicit
        FROM download d LEFT JOIN track t ON t.id = d.trackId
        ORDER BY CASE d.state WHEN 'Downloading' THEN 0 WHEN 'Queued' THEN 1 WHEN 'Failed' THEN 2 ELSE 3 END, d.addedAt DESC, d.title
        """,
    )
    fun rows(): Flow<List<DownloadRow>>

    // Finished downloads of these library songs.
    @Query(DONE_FOR_SQL)
    suspend fun doneFor(trackIds: List<String>): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE trackId = :trackId")
    suspend fun forTrack(trackId: String): List<DownloadEntity>

    // The oldest download from this server still waiting.
    @Query("SELECT * FROM download WHERE state = 'Queued' AND sourceId = :sourceId ORDER BY addedAt, title LIMIT 1")
    suspend fun nextQueued(sourceId: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun get(sourceId: String, serverId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<DownloadEntity>)

    @Query("UPDATE download SET reason = :reason WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun setReason(sourceId: String, serverId: String, reason: String)

    // A download waits again or has failed.
    @Query("UPDATE download SET state = :state, progress = :progress WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun setState(sourceId: String, serverId: String, state: DownloadStatus, progress: Float)

    // A download starts.
    @Query("UPDATE download SET state = 'Downloading', progress = 0 WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun markDownloading(sourceId: String, serverId: String)

    // How far a running download has got. A row no longer downloading keeps
    // what it has.
    @Query("UPDATE download SET progress = :progress WHERE sourceId = :sourceId AND serverId = :serverId AND state = 'Downloading'")
    suspend fun setProgress(sourceId: String, serverId: String, progress: Float)

    // A finished download whose file has gone waits to download again,
    // unless it was downloaded again or changed meanwhile. Answers whether it
    // did.
    @Query(
        """
        UPDATE download SET state = 'Queued', progress = 0
        WHERE sourceId = :sourceId AND serverId = :serverId AND state = 'Done' AND path = :path
        """,
    )
    suspend fun requeueGone(sourceId: String, serverId: String, path: String): Int

    // How far one download has got, as it goes; null once it is gone.
    @Query("SELECT progress FROM download WHERE sourceId = :sourceId AND serverId = :serverId")
    fun progressOf(sourceId: String, serverId: String): Flow<Float?>

    @Query(IS_DONE_SQL)
    suspend fun isDone(trackId: String): Boolean

    // At most 900 songs at a time, for the database's limit.
    @Query(DONE_AMONG_SQL)
    suspend fun doneAmong(trackIds: List<String>): List<String>

    // At most 900 songs at a time, for the database's limit.
    @Query(KEPT_AMONG_SQL)
    suspend fun keptAmong(trackIds: List<String>): List<DownloadEntity>

    @Query(KEPT_ROW_SQL)
    suspend fun keptRow(trackId: String): DownloadEntity?

    @Query(
        """
        UPDATE download SET state = 'Done', progress = 1, path = :path, sizeBytes = :size, format = :format, fileSize = :fileSize
        WHERE sourceId = :sourceId AND serverId = :serverId
        """,
    )
    suspend fun finish(sourceId: String, serverId: String, path: String, size: Long, format: String?, fileSize: Long?)

    // The finished downloads from this server, each with its server song,
    // a page at a time.
    @Query(DONE_FILES_PAGE_SQL)
    suspend fun doneFilesPage(sourceId: String, after: String, limit: Int): List<DownloadedFile>

    @Query("UPDATE download SET fileSize = :fileSize WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun noteFileSize(sourceId: String, serverId: String, fileSize: Long)

    // A download cut short when the app last closed waits again.
    @Query("UPDATE download SET state = 'Queued', progress = 0 WHERE state = 'Downloading'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM download WHERE sourceId = :sourceId AND serverId = :serverId")
    suspend fun delete(sourceId: String, serverId: String)

    // After the library is rebuilt: each download follows its server song to
    // the library song it is now part of. Answers how many rows it wrote.
    @Query(RELINK_SQL)
    suspend fun relink(): Int

    // The songs the server's downloads hold, a page at a time.
    @Query(HELD_PAGE_SQL)
    suspend fun heldPage(sourceId: String, after: String, limit: Int): List<HeldSong>

    // Liked songs by library id, a page at a time.
    @Query(LIKED_PAGE_SQL)
    suspend fun likedPage(after: String, limit: Int): List<String>

    // The songs of one playlist's item set, a page at a time.
    @Query(SET_SONG_PAGE_SQL)
    suspend fun setSongPage(playlistId: String, itemSet: Long, after: String, limit: Int): List<String>

    // The server's downloads of these library songs, each with its reasons.
    // At most 900 songs at a time, for the database's limit.
    @Query(REASONS_OF_SQL)
    suspend fun reasonsOf(sourceId: String, trackIds: List<String>): List<SongReason>

    // At most 900 playlists at a time, for the database's limit.
    @Query(PLAYLIST_SETS_SQL)
    suspend fun playlistSets(ids: List<String>): List<PlaylistSet>

    @Query(LIBRARY_AMONG_SQL)
    suspend fun libraryAmong(trackIds: List<String>): List<String>

    @Query(ALBUM_SONG_PAGE_SQL)
    suspend fun albumSongPage(albumId: String, afterOrder: Int, afterId: String, limit: Int): List<AlbumSong>

    // The download written last, as a place in the order rows were written.
    @Query("SELECT MAX(rowid) FROM download")
    suspend fun lastWritten(): Long?

    @Query(NUMBERED_PAGE_SQL)
    suspend fun numberedPage(after: Long, last: Long, limit: Int): List<NumberedDownload>

    @Query(SOURCE_PAGE_SQL)
    suspend fun sourcePage(sourceId: String, after: String, limit: Int): List<DownloadEntity>

    @Query(KEY_DONE_SQL)
    suspend fun keyDone(sourceId: String, serverId: String): Boolean

    // The server copies of these library songs.
    @Query("SELECT * FROM source_track WHERE mergedId IN (:trackIds) AND sourceId LIKE 'server:%'")
    suspend fun serverCopies(trackIds: List<String>): List<SourceTrackEntity>

    // One server song, as the last sync saw it.
    @Query("SELECT * FROM source_track WHERE sourceId = :sourceId AND nativeId = :serverId LIMIT 1")
    suspend fun serverCopy(sourceId: String, serverId: String): SourceTrackEntity?

    // Which of these library songs can be downloaded: on a server and not on the phone.
    @Query(
        """
        SELECT DISTINCT t.id FROM track t JOIN source_track s ON s.mergedId = t.id
        WHERE t.onPhone = 0 AND s.sourceId LIKE 'server:%' AND t.id IN (:trackIds)
        """,
    )
    suspend fun downloadable(trackIds: List<String>): List<String>

    // The server albums downloaded whole: every song of the album that is
    // only on the server has its download finished, and at least one has.
    @Query(
        """
        SELECT a.sourceId AS sourceId, a.nativeId AS albumId FROM source_album a
        WHERE a.sourceId LIKE 'server:%'
        AND EXISTS (
            SELECT 1 FROM source_track s JOIN download d ON d.sourceId = s.sourceId AND d.serverId = s.nativeId
            WHERE s.albumId = a.id AND d.state = 'Done'
        )
        AND NOT EXISTS (
            SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId
            WHERE s.albumId = a.id AND t.onPhone = 0
            AND NOT EXISTS (SELECT 1 FROM download d WHERE d.sourceId = s.sourceId AND d.serverId = s.nativeId AND d.state = 'Done')
        )
        """,
    )
    suspend fun downloadedAlbums(): List<DownloadedAlbum>
}

// A server album downloaded whole, by its id on the server.
data class DownloadedAlbum(val sourceId: String, val albumId: String)
