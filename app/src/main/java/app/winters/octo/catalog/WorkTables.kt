package app.winters.octo.catalog

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Rows for copying a server's library and merging the sources without
// holding either in memory. No screen reads them.
//
// Working rows (everything here but source_meta, account_sync and
// merge_state) last only as long as the copy or merge that wrote them:
// each open of the database empties them (CatalogCallback), so a copy or
// merge cut off by the app closing starts again from the beginning.

// What a source's copy is doing: nothing, being built, built and waiting
// for the merge, or taken by a merge that is swapping it in.
const val STAGE_NONE = "none"
const val STAGE_BUILDING = "building"
const val STAGE_READY = "ready"
const val STAGE_CLAIMED = "claimed"

// A source's rows as a copy builds them, before a merge swaps them in.
@Entity(
    tableName = "stage_track",
    primaryKeys = ["id"],
    indices = [Index("sourceId", "albumOrder", "seq", "id"), Index("sourceId", "seq"), Index("sourceId", "nativeId")],
)
data class StageTrackEntity(@Embedded val row: SourceTrackEntity)

@Entity(tableName = "stage_album", primaryKeys = ["id"], indices = [Index("sourceId", "seq")])
data class StageAlbumEntity(@Embedded val row: SourceAlbumEntity)

@Entity(tableName = "stage_artist", primaryKeys = ["id"], indices = [Index("sourceId", "seq")])
data class StageArtistEntity(@Embedded val row: SourceArtistEntity)

// The server's answers as a copy reads them, one row per song, album and
// artist, each as the server sent it.
@Entity(tableName = "sync_song", indices = [Index("songId", unique = true), Index("albumKey", "seq")])
data class SyncSongEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val songId: String,
    val albumKey: String,
    val json: String,
)

@Entity(tableName = "sync_album", indices = [Index("seq")])
data class SyncAlbumEntity(@PrimaryKey val id: String, val seq: Long, val json: String)

@Entity(tableName = "sync_artist", indices = [Index("seq")])
data class SyncArtistEntity(@PrimaryKey val id: String, val seq: Long, val json: String)

// The songs of an album too big to sort in memory, in the order a copy
// files them: disc, track number, then title.
@Entity(
    tableName = "sync_order",
    primaryKeys = ["owner", "seq"],
    indices = [Index("owner", "discKey", "trackKey", "sortKeyHex", "seq")],
)
data class SyncOrderEntity(
    val owner: String,
    val discKey: Int,
    val trackKey: Int,
    val sortKeyHex: String,
    val seq: Long,
    val songId: String,
)

// The server's starred songs, albums and artists, as one read of them got
// them. A read is whole only once its "end" row is in.
@Entity(tableName = "sync_starred", primaryKeys = ["generation", "kind", "id"])
data class SyncStarredEntity(val generation: Long, val kind: String, val id: String, val starredAt: String?)

// One library song while a merge gathers its copies: the base copy's
// columns with the merge's rules applied so far. `track.year` is the
// edition's year; the library shows the original year when there is one.
@Entity(
    tableName = "merge_candidate",
    primaryKeys = ["id"],
    indices = [
        Index("albumId", "titleKey", "lastSourceRank", "seq", "durationMs"),
        Index("matchKey", "titleKey", "lastSourceRank", "seq", "durationMs"),
        Index("albumId", "discKey", "trackNo", "seq"),
        Index("albumId", "seq"),
        Index("seq"),
    ],
)
data class MergeCandidateEntity(
    @Embedded val track: TrackEntity,
    // The order songs were first met in.
    val seq: Long,
    // The base copy's song keys.
    val titleKey: String,
    val matchKey: String,
    val originalYear: Int?,
    // How many artists the credit kept so far names.
    val credits: Int,
    // The disc number, 1 when unknown.
    val discKey: Int,
    // The rank of the last source to add a copy, and of the one that
    // brought the song.
    val lastSourceRank: Int,
    val createdRank: Int,
    // Whether a copy from another source joined.
    val mixed: Boolean,
)

// Which library song each source row becomes in the merge being built.
@Entity(tableName = "merged_id_next", indices = [Index("mergedId")])
data class MergedIdNextEntity(@PrimaryKey val sourceRowId: String, val mergedId: String)

// The merge's albums and artists, before the swap.
@Entity(tableName = "album_next", primaryKeys = ["id"])
data class AlbumNextEntity(@Embedded val album: AlbumEntity)

@Entity(tableName = "artist_next", primaryKeys = ["id"])
data class ArtistNextEntity(@Embedded val artist: ArtistEntity)

// Rows taken in while a copy was being built, so the copy keeps them.
@Entity(tableName = "take_in_log", primaryKeys = ["generation", "kind", "id"])
data class TakeInLogEntity(val generation: Long, val kind: String, val id: String)

// Ratings made while a merge runs, put back on its result.
@Entity(tableName = "rating_note")
data class RatingNoteEntity(@PrimaryKey val sourceRowId: String, val rating: Int)

// A rating tapped on the phone that its server copy shows but the server
// has not yet confirmed taking, by server copy, until a send of it lands.
@Entity(tableName = "rating_tap")
data class RatingTapEntity(@PrimaryKey val sourceRowId: String, val rating: Int)

// What the app knows of each source's rows: whose copy they are (a kept
// account, for a server), a stamp raised whenever they change, and the
// copy being built for it, if any.
@Entity(tableName = "source_meta")
data class SourceMetaEntity(
    @PrimaryKey val sourceId: String,
    val copyOf: String?,
    // The kept account whose copy is being built, while one is.
    val stageOwner: String?,
    val stamp: Long,
    val stage: String,
    val generation: Long,
    val stagedTracks: Int,
    val stagedAlbums: Int,
    val stagedArtists: Int,
)

// Each kept account's last copy: of which source, when, how much came,
// by which version of the copy, the server's last scan as it said then,
// and whether everything after the copy finished.
@Entity(tableName = "account_sync")
data class AccountSyncEntity(
    @PrimaryKey val account: String,
    val sourceId: String,
    val syncedAt: Long,
    val songs: Int,
    val albums: Int,
    val rowsVersion: Int,
    val lastScan: String?,
    val afterSyncDone: Boolean,
)

// The merge's own record: the inputs it last merged, whether a merge is
// between its claim and its swap, and the server the library was last
// merged for ("" for none, null before any merge recorded it).
@Entity(tableName = "merge_state")
data class MergeStateEntity(@PrimaryKey val id: Int, val skipKey: String?, val claimActive: Boolean, val server: String? = null)
