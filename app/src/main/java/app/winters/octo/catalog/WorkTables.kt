package app.winters.octo.catalog

import androidx.room.ColumnInfo
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

// A listed album, `seq` its place in the server's list, `place` its place
// in the source once the copy's walk of the list has given it one.
@Entity(tableName = "sync_album", indices = [Index("seq")])
data class SyncAlbumEntity(@PrimaryKey val id: String, val seq: Long, val json: String, val place: Long? = null)

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
// A save's find reads the lowest song filed under a relink key here.
@Entity(
    tableName = "merge_candidate",
    primaryKeys = ["id"],
    indices = [
        Index("albumId", "titleKey", "lastSourceRank", "seq", "durationMs"),
        Index("matchKey", "titleKey", "lastSourceRank", "seq", "durationMs"),
        Index("albumId", "discKey", "trackNo", "seq"),
        Index("albumId", "seq"),
        Index("seq"),
        Index("relinkKey", "id"),
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

// The merge's albums and artists, before the swap. A save's find reads
// those filed under a search key.
@Entity(tableName = "album_next", primaryKeys = ["id"], indices = [Index("searchKey", "id")])
data class AlbumNextEntity(@Embedded val album: AlbumEntity)

@Entity(tableName = "artist_next", primaryKeys = ["id"], indices = [Index("searchKey", "id")])
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
    // While the copy swapped in last is leaving the stage: the source's
    // next copy waits for it before staging anything.
    @ColumnInfo(defaultValue = "0") val stageClearing: Boolean = false,
    // How many of the stage's songs, albums and artists were placed other
    // than where their source rows are, or more: the swap writes a table's
    // places only when its count is above 0.
    @ColumnInfo(defaultValue = "0") val movedTracks: Int = 0,
    @ColumnInfo(defaultValue = "0") val movedAlbums: Int = 0,
    @ColumnInfo(defaultValue = "0") val movedArtists: Int = 0,
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
// between its claim and its swap, the server the library was last merged
// for ("" for none, null before any merge recorded it), and how many swaps
// have changed the library's rows, which only grows.
//
// While a save is part applied, from its first group's commit to its
// last's, it keeps what it needs to finish here: the claim it applies (its
// sources with their ranks and whether each is a staged copy, the copies it
// claimed with their generations and counts, and the server), the skip key
// it ends with, the account whose copy it saves, whether that is the
// account's first copy, and the server's scan the copy read. `applyClaim`
// is null when no save is part applied. A save stopped part way keeps the
// stop's words in `applyStopped` until it resumes.
//
// Also kept: the account the play totals were last counted for, and the
// mark of the launch that last opened the library, which every work owner
// minted in that launch leads with.
@Entity(tableName = "merge_state")
data class MergeStateEntity(
    @PrimaryKey val id: Int,
    val skipKey: String?,
    val claimActive: Boolean,
    val server: String? = null,
    @ColumnInfo(defaultValue = "0") val swapCount: Long = 0,
    val applyClaim: String? = null,
    val applySkipKey: String? = null,
    val applyAccount: String? = null,
    @ColumnInfo(defaultValue = "0") val applyFirstCopy: Boolean = false,
    val applyScan: String? = null,
    val applyStopped: String? = null,
    val totalsAccount: String? = null,
    @ColumnInfo(defaultValue = "0") val launchMark: Long = 0,
)
