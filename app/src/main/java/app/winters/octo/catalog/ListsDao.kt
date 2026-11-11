package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import app.winters.octo.ui.paging.SqlValue

// A library song as a list reads it, with up to three numbers and two
// texts the list's order takes from beside the song (a like, plays, an
// album's year and name) and the part of the list it was read in.
data class KeyedTrack(
    @Embedded val track: TrackEntity,
    val k0: Long? = null,
    val k1: Long? = null,
    val k2: Long? = null,
    val t0: String? = null,
    val t1: String? = null,
    val seg: Long? = null,
)

// A row of a playlist's set as its page reads it: its place and key, the
// song it names (a library song, or a find shaped like one), and whether
// the song is in the library.
data class PlaylistRowView(
    val ord: Long,
    val itemKey: Long,
    val itemId: Long,
    val inLibrary: Boolean,
    @Embedded val track: TrackEntity,
)

// A download as the Downloads page reads it, with the keys of its order.
data class DownloadRowView(
    val trackId: String,
    val sourceId: String,
    val serverId: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val sizeBytes: Long,
    val state: String,
    val progress: Float,
    val reason: String,
    val addedAt: Long,
    val explicit: Boolean?,
    val titleKey: ByteArray,
    val seg: Long? = null,
)

// A phone folder's song in the folder page's order.
data class FolderRowView(
    @Embedded val track: TrackEntity,
    val lead: ByteArray,
    val ties: ByteArray,
)

// A row of an album's listing as the page shows it: its place, the song
// it shows (a library song, or a find), whether it is a find, and for a
// library song with no place in the listing its place in the album.
data class AlbumEntryView(
    val position: Long,
    val shownId: String,
    val isFind: Boolean,
    val entryOrder: Long?,
    @Embedded val track: TrackEntity,
)

// A row of a server folder level with what it shows: a folder's own
// columns, its keys in the level's order, and a song's as the library or
// its find has it (null for a folder, which reads none).
data class ServerDirView(
    val position: Long,
    val kind: Int,
    val dirId: String,
    val name: String,
    val coverArt: String?,
    val songCount: Int?,
    val lead: ByteArray,
    val ties: ByteArray,
    @Embedded(prefix = "s_") val song: TrackEntity?,
)

// A row of a list's sort copy.
data class SortCopyRow(
    val segment: Int,
    val lead: Long,
    val leadKey: Long,
    val liked: Boolean,
    val tieKey: Int,
    val sortKey: String,
    val titleKey: ByteArray,
    val trackId: String,
    val sourceId: String,
    val serverId: String,
)

// The statements the list loaders build from fixed pieces: each walks one
// index in its order a page at a time, or counts or probes it. Nothing the
// listener types or the library holds is written into their text; values
// go to ? marks.
@Dao
interface ListsDao {
    @RawQuery
    suspend fun tracks(query: RoomRawQuery): List<KeyedTrack>

    @RawQuery
    suspend fun count(query: RoomRawQuery): Int

    @RawQuery
    suspend fun ids(query: RoomRawQuery): List<String>

    @RawQuery
    suspend fun numbers(query: RoomRawQuery): List<Long>

    @RawQuery
    suspend fun playlistRows(query: RoomRawQuery): List<PlaylistRowView>

    @RawQuery
    suspend fun downloadRows(query: RoomRawQuery): List<DownloadRowView>

    @RawQuery
    suspend fun folderRows(query: RoomRawQuery): List<FolderRowView>

    @RawQuery
    suspend fun albumRows(query: RoomRawQuery): List<AlbumEntryView>

    @RawQuery
    suspend fun sortCopyRows(query: RoomRawQuery): List<SortCopyRow>

    @RawQuery
    suspend fun serverDirRows(query: RoomRawQuery): List<ServerDirView>

    @RawQuery
    suspend fun albums(query: RoomRawQuery): List<AlbumEntity>

    // How many own files each phone folder holds, along the folder index.
    @Query(FOLDER_COUNTS_SQL)
    suspend fun folderCounts(): List<FolderCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderRows(rows: List<ListsFolderOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSortCopyRows(rows: List<ListsSortCopyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumEntries(rows: List<ListsAlbumEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerDirRows(rows: List<ListsServerDirEntity>)

    // A page of an album copy's library rows, which a restart writes again.
    @Query(
        "DELETE FROM lists_album_entry WHERE rowid IN " +
            "(SELECT rowid FROM lists_album_entry WHERE owner = :owner AND albumOrder IS NOT NULL LIMIT 500)",
    )
    suspend fun deleteAlbumLibraryRows(owner: String): Int

    @RawQuery
    suspend fun albumVotes(query: RoomRawQuery): List<AlbumVote>

    @RawQuery
    suspend fun listening(query: RoomRawQuery): List<GroupListening>
}

// An album's or an artist's plays, by its id.
data class GroupListening(val id: String, val plays: Int, val lastPlayedAt: Long)

// How many of an album's songs one source album holds.
data class AlbumVote(val albumId: String, val n: Int)

// A phone folder and how many own files it holds.
data class FolderCount(val storageVolume: String?, val folder: String?, val n: Int)

internal const val FOLDER_COUNTS_SQL =
    "SELECT storageVolume, folder, COUNT(*) AS n FROM source_track WHERE sourceId = 'device' AND id = mergedId GROUP BY storageVolume, folder"

internal const val GENRES_SQL =
    "SELECT MIN(genre COLLATE BINARY) AS name, COUNT(*) AS songCount, MIN(artwork) AS artwork FROM track " +
        "WHERE genre > '' GROUP BY genre ORDER BY genre"

internal const val GENRE_ALBUMS_SQL =
    "SELECT a.* FROM album a WHERE EXISTS (SELECT 1 FROM track t WHERE t.albumId = a.id AND t.genre = :name) ORDER BY a.sortKey"

// A statement with its values bound in order.
fun rawQuery(sql: String, args: List<SqlValue>): RoomRawQuery =
    RoomRawQuery(sql) { statement -> args.forEachIndexed { i, value -> statement.bind(i + 1, value) } }

fun SQLiteStatement.bind(index: Int, value: SqlValue) {
    when (value) {
        SqlValue.Null -> bindNull(index)
        is SqlValue.Int64 -> bindLong(index, value.v)
        is SqlValue.Real -> bindDouble(index, value.v)
        is SqlValue.Text -> bindText(index, value.v)
        is SqlValue.Blob -> bindBlob(index, value.v.toByteArray())
    }
}
