package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Ids are "<source>:<native id>", so one catalog can hold every source.

@Entity(
    tableName = "track",
    indices = [Index("albumId"), Index("artistId"), Index("sourceId"), Index("sortKey"), Index("searchKey")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val nativeId: String,
    val title: String,
    val searchKey: String,
    val sortKey: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val albumId: String,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val durationMs: Long,
    val addedAt: Long,
    val mimeType: String?,
    val sizeBytes: Long?,
    val artwork: String?,
    // Where the audio is: a content:// address for phone files.
    val uri: String?,
    // Position within its album, worked out once when the catalog is built.
    @ColumnInfo(defaultValue = "0") val albumOrder: Int = 0,
)

@Entity(
    tableName = "album",
    indices = [Index("artistId"), Index("sourceId"), Index("sortKey"), Index("searchKey"), Index("addedAt")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val nativeId: String,
    val title: String,
    val searchKey: String,
    val sortKey: String,
    val artist: String,
    val artistId: String,
    val year: Int?,
    val songCount: Int,
    val durationMs: Long,
    val addedAt: Long,
    val artwork: String?,
)

@Entity(tableName = "artist", indices = [Index("sourceId"), Index("sortKey"), Index("searchKey")])
data class ArtistEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val searchKey: String,
    val sortKey: String,
    val albumCount: Int,
    val songCount: Int,
    val artwork: String?,
)

// Tags read from one file on the phone, kept so a rescan only re-reads
// files that changed. Stale when the file's modified time or size moves.
@Entity(tableName = "file_tags")
data class FileTagsEntity(
    @PrimaryKey val mediaId: Long,
    val modifiedAt: Long,
    val size: Long,
    // False when the file could not be read; the phone's own tags are used then.
    val readOk: Boolean,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val compilation: Boolean,
    val mbAlbumId: String?,
)
