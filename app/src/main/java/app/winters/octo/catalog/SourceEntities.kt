package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// What each source has, as it has it: the phone's files, or a server's
// library. Each source replaces its own rows. The library the screens read
// (track, album, artist) is merged from these, so a song on the phone and
// on a server shows once. The columns match the library's, plus what only
// a source knows.

@Entity(tableName = "source_track", indices = [Index("sourceId"), Index("mergedId")])
data class SourceTrackEntity(
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
    val uri: String?,
    val albumOrder: Int,
    val relinkKey: String,
    val genre: String,
    // How good this copy is, where the source says: bits per second,
    // samples per second and bits per sample.
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    // The library song this copy was merged into.
    @ColumnInfo(defaultValue = "") val mergedId: String = "",
)

@Entity(tableName = "source_album", indices = [Index("sourceId")])
data class SourceAlbumEntity(
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

@Entity(tableName = "source_artist", indices = [Index("sourceId")])
data class SourceArtistEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val searchKey: String,
    val sortKey: String,
    val albumCount: Int,
    val songCount: Int,
    val artwork: String?,
)

fun TrackEntity.toSource() = SourceTrackEntity(
    id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId,
    trackNo, discNo, year, durationMs, addedAt, mimeType, sizeBytes, artwork, uri, albumOrder, relinkKey, genre,
)

fun AlbumEntity.toSource() = SourceAlbumEntity(
    id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, year, songCount, durationMs, addedAt, artwork,
)

fun ArtistEntity.toSource() = SourceArtistEntity(id, sourceId, name, searchKey, sortKey, albumCount, songCount, artwork)
