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
    // A server's own listening record for this copy: how often and when it
    // was last played, and when it was starred, if it was.
    val playCount: Int? = null,
    val lastPlayedAt: Long? = null,
    val starredAt: Long? = null,
    // How loud the song is, where the source says: gains in decibels, peaks
    // where 1 is full scale. The base gain is one the file already applies;
    // the fallback is what the server suggests for a song with no gain.
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null,
    // The listener's rating on the server, 1 to 5 stars, or 0 for none.
    val rating: Int? = null,
    // The library song this copy was merged into.
    @ColumnInfo(defaultValue = "") val mergedId: String = "",
    // What this copy says beyond the basics, where it says it. `year` above
    // is this edition's year; the original is the first release's.
    val originalYear: Int? = null,
    // Every genre and every credited artist, one per line, main one first.
    @ColumnInfo(defaultValue = "") val genres: String = "",
    @ColumnInfo(defaultValue = "") val artists: String = "",
    val composer: String? = null,
    // Beats per minute.
    val bpm: Int? = null,
    val comment: String? = null,
    // True when marked explicit, false when marked clean, null when unmarked.
    val explicit: Boolean? = null,
    // The name of this song's disc, like "Live at Wembley".
    val discTitle: String? = null,
    // MusicBrainz ids: the recording, the release (album), its release
    // group, and the artists (one per line).
    val mbRecordingId: String? = null,
    val mbAlbumId: String? = null,
    val mbReleaseGroupId: String? = null,
    @ColumnInfo(defaultValue = "") val mbArtistIds: String = "",
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

fun AlbumEntity.toSource() = SourceAlbumEntity(
    id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, year, songCount, durationMs, addedAt, artwork,
)

fun ArtistEntity.toSource() = SourceArtistEntity(id, sourceId, name, searchKey, sortKey, albumCount, songCount, artwork)
