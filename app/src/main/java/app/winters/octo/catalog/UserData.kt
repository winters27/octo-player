package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Things the listener made: likes, plays, playlists and the saved queue.
//
// None of these point at the catalog with a foreign key. The catalog is
// rebuilt from its sources on every scan, and a cascading delete would wipe
// them. Each row keeps a relinkKey instead, so a song whose id changed (a
// file moved) can be found again.

@Entity(tableName = "liked_track", indices = [Index("likedAt", "trackId", name = "index_liked_track_likedAt")])
data class LikedTrackEntity(
    @PrimaryKey val trackId: String,
    val relinkKey: String,
    val likedAt: Long,
)

// A song's rating made on the phone, 1 to 5 stars. A song with no rating
// has no row.
@Entity(tableName = "track_rating", indices = [Index("ratedAt", "trackId", name = "index_track_rating_ratedAt")])
data class TrackRatingEntity(
    @PrimaryKey val trackId: String,
    val relinkKey: String,
    val rating: Int,
    val ratedAt: Long,
)

// A song's plays are indexed by when they started, so its latest is one
// read and they move to another id oldest first.
@Entity(tableName = "play_event", indices = [Index("trackId", "startedAt"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val relinkKey: String,
    val startedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    // The server playlist this one is kept in step with: its id there, and
    // which server. One waiting to be made on the server has the server but
    // no id yet. Both are null for a playlist only on the phone.
    val serverId: String? = null,
    val sourceId: String? = null,
    // What updatedAt was when both sides last agreed. A later change has not
    // reached the server yet.
    val syncedAt: Long? = null,
    // The name both sides agreed on then, and the server's changed time and
    // song count as they were, to tell whether the server changed since.
    val syncedName: String? = null,
    val serverStamp: String? = null,
    // Which of Octo's notice lists the server copy is ("review",
    // "duplicates"), so its cover carries that list's glyph.
    val octoNotice: String? = null,
    // A family playlist: anyone in the family may add and take out songs,
    // and its order is the server's.
    @ColumnInfo(defaultValue = "0") val family: Boolean = false,
    // Where an older Octo kept a family playlist's base, one id a line. The
    // upgrade moved every one into playlist_synced_song; nothing writes it.
    @ColumnInfo(name = "syncedSongs") val retiredSyncedSongs: String? = null,
    // What the listener may do with this family playlist, as the last sync found.
    @ColumnInfo(defaultValue = "0") val familyMayAdd: Boolean = false,
    @ColumnInfo(defaultValue = "0") val familyMayRemove: Boolean = false,
    @ColumnInfo(defaultValue = "0") val familyMayRename: Boolean = false,
    @ColumnInfo(defaultValue = "0") val familyMayDelete: Boolean = false,
    // Which item set holds the playlist's songs (playlist_item rows with
    // this number), and which base set its base (playlist_synced_song). A
    // whole new list of songs is written as a new set, then these move to
    // it in one step. -1 while the playlist is being deleted.
    @ColumnInfo(defaultValue = "0") val itemSet: Long = 0,
    @ColumnInfo(defaultValue = "0") val baseSet: Long = 0,
    // Whether a base is kept in playlist_synced_song, even an empty one.
    @ColumnInfo(defaultValue = "0") val syncedSongsKept: Boolean = false,
    // One of Octo's library upkeep lists, as the server marks its copy
    // (octoUpkeep), and which kind (octoUpkeepKind: "action", "notice" or
    // "request"; null when the server names none).
    @ColumnInfo(defaultValue = "0") val upkeep: Boolean = false,
    val upkeepKind: String? = null,
    // An imported playlist's read-only copy (octoImport): it only follows
    // the server, takes no songs here, and is never renamed or deleted here.
    @ColumnInfo(defaultValue = "0") val octoImport: Boolean = false,
)

@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId", "itemSet", "ord", "itemKey", name = "index_playlist_item_set"),
        Index("playlistId", "itemSet", "itemKey", unique = true, name = "index_playlist_item_set_key"),
        Index("playlistId", "itemSet", "trackId", name = "index_playlist_item_set_track"),
        Index("trackId", name = "index_playlist_item_trackId"),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    // The playlist's item set the row is in; the playlist's own itemSet
    // names its songs.
    @ColumnInfo(defaultValue = "0") val itemSet: Long = 0,
    val trackId: String,
    val relinkKey: String,
    // Where the row comes in play order, ORD_STEP apart, ties by itemKey.
    val ord: Long,
    // The row's own key within its playlist, from playlist_meta; 0 until
    // the row is written and given one.
    val itemKey: Long = 0,
    // The server's id for this song when it came from the server's copy of
    // the playlist, so it goes back as the same song.
    val serverSongId: String? = null,
)

// A family playlist's base: the server's songs as both sides last agreed,
// each song once with how many times the playlist holds it, so the
// playlist's own changes can be told from everyone else's. Written in sets
// like the playlist's songs; the playlist's baseSet names the current one.
@Entity(
    tableName = "playlist_synced_song",
    primaryKeys = ["playlistId", "baseSet", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistSyncedSongEntity(val playlistId: String, val songId: String, val copies: Int, val baseSet: Long)

// The key the next new playlist row takes. One row, id 0. It only grows,
// so no key is handed out twice, even across a restart.
@Entity(tableName = "playlist_meta")
data class PlaylistMetaEntity(@PrimaryKey val id: Int, val nextItemKey: Long)

