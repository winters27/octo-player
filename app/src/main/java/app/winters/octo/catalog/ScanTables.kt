package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Rows a phone scan builds the phone's songs, albums and artists from,
// file by file, so no step holds every file in memory. Each open of the
// database empties them; only phone_scan_state lasts.

// One music file as the phone's media store listed it, in the order it came
// (seq), with the tags the store itself has, used where the file's own
// cannot be read.
@Entity(tableName = "scan_file", indices = [Index("seq", unique = true)])
data class ScanFileEntity(
    @PrimaryKey val mediaId: Long,
    val seq: Long,
    val uri: String,
    val fileName: String,
    val folder: String?,
    val volume: String?,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val addedAt: Long,
    val mimeType: String?,
    val fbTitle: String?,
    val fbArtist: String?,
    val fbAlbumArtist: String?,
    val fbAlbum: String?,
    val fbTrackNo: Int?,
    val fbDiscNo: Int?,
    val fbYear: Int?,
    @ColumnInfo(defaultValue = "") val fbGenres: String = "",
)

// A file the folder rules let in, with the album group it falls in and the
// keys that order it within the album.
@Entity(tableName = "scan_row", indices = [Index("groupId", "seq"), Index("groupId", "discKey", "numKey", "nameOrder", "seq")])
data class ScanRowEntity(
    @PrimaryKey val mediaId: Long,
    val seq: Long,
    val groupId: Long,
    val track: Int?,
    val fileNo: Int?,
    // The disc number, 1 when unknown, and the track number, last when
    // unknown.
    val discKey: Int,
    val numKey: Int,
    val nameOrder: String,
)

// The files that share an album name, before they are split into albums.
@Entity(tableName = "scan_cluster")
data class ScanClusterEntity(
    @PrimaryKey val nameKey: String,
    val groupId: Long,
    @ColumnInfo(defaultValue = "0") val firstSeq: Long = 0,
    @ColumnInfo(defaultValue = "0") val files: Int = 0,
    @ColumnInfo(defaultValue = "0") val mbFiles: Int = 0,
    @ColumnInfo(defaultValue = "0") val byFiles: Int = 0,
    val mbFirst: String? = null,
    val byFirst: String? = null,
    @ColumnInfo(defaultValue = "0") val mbVaries: Boolean = false,
    @ColumnInfo(defaultValue = "0") val byVaries: Boolean = false,
)

// One album as the scan splits it: its name and what tells it apart from
// another album of that name.
@Entity(tableName = "scan_group", indices = [Index("nameKey", "splitKey", unique = true), Index("clusterSeq", "firstSeq")])
data class ScanGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameKey: String,
    val splitKey: String,
    val clusterSeq: Long,
    val firstSeq: Long,
)

// How often each value comes up for one owner (an album's names, its
// artists, a genre's spellings, an artist's names), and where it was first.
@Entity(tableName = "scan_tally", primaryKeys = ["owner", "value"])
data class ScanTallyEntity(
    val owner: String,
    val value: String,
    @ColumnInfo(defaultValue = "0") val n: Int = 0,
    @ColumnInfo(defaultValue = "0") val first: Long = 0,
)

// The spelling chosen for each genre.
@Entity(tableName = "scan_genre_name")
data class ScanGenreNameEntity(@PrimaryKey val genreKey: String, val name: String)

// One artist as the scan counts it.
@Entity(tableName = "scan_artist", indices = [Index("firstSeq")])
data class ScanArtistEntity(
    @PrimaryKey val artistId: String,
    @ColumnInfo(defaultValue = "0") val firstSeq: Long = 0,
    @ColumnInfo(defaultValue = "0") val albums: Int = 0,
    @ColumnInfo(defaultValue = "0") val songs: Int = 0,
    @ColumnInfo(defaultValue = "0") val newestAt: Long = 0,
    val artwork: String? = null,
    val sortName: String? = null,
)

// What the last scan that reached the library saw, so a launch with nothing
// changed writes nothing: a digest of the phone's files (null when not
// known), and how many songs, albums and artists it made. One row, id 0.
@Entity(tableName = "phone_scan_state")
data class PhoneScanStateEntity(
    @PrimaryKey val id: Int,
    val digest: String?,
    @ColumnInfo(defaultValue = "0") val tracks: Int = 0,
    @ColumnInfo(defaultValue = "0") val albums: Int = 0,
    @ColumnInfo(defaultValue = "0") val artists: Int = 0,
)
