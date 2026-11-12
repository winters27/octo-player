package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// A server's list (an album, an Octo list, a station, a folder, its play
// queue) as one read gave it, resolved against the library, for a page, a
// car read or a queue fill, each under its own owner. The shapes are a
// playlist sync's read rows with the owner in place of the read. Every open
// of the database empties them. A library song's rows are found by its id
// too, every owner's at once, when the song's id moves.

@Entity(
    tableName = "server_entry",
    primaryKeys = ["owner", "position"],
    indices = [
        Index("owner", "songId", "position"), Index("owner", "trackId", "position"), Index("owner", "kind", "songId", "position"),
        Index("trackId"),
    ],
)
data class ServerEntryEntity(
    val owner: String,
    val position: Long,
    val songId: String,
    val external: Boolean,
    val title: String,
    val artist: String,
    val lengthMs: Long,
    val album: String,
    val albumId: String?,
    val artistId: String?,
    val coverId: String?,
    val mimeType: String?,
    val bitrate: Int?,
    val explicit: Boolean?,
    val trackId: String?,
    @ColumnInfo(defaultValue = "0") val kind: Int = 0,
    val groupId: Long?,
    @ColumnInfo(defaultValue = "0") val firstFind: Boolean = false,
    @ColumnInfo(defaultValue = "0") val held: Boolean = false,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
)

@Entity(tableName = "server_key", primaryKeys = ["owner", "key"])
data class ServerKeyEntity(val owner: String, val key: String)

@Entity(tableName = "server_match", indices = [Index("owner", "title", "artist", "lengthMs", unique = true), Index("trackId")])
data class ServerMatchEntity(
    @PrimaryKey val id: Long,
    val owner: String,
    val title: String,
    val artist: String,
    val lengthMs: Long,
    val trackId: String?,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
)

@Entity(tableName = "server_lookup", primaryKeys = ["owner", "lookupKey", "groupId"])
data class ServerLookupEntity(val owner: String, val lookupKey: String, val groupId: Long)

// One owner's read of a server list: what it is (`kind`: Album, List,
// Station, Folder or PlayQueue, and the server's id for it), its heading,
// whether a folder's gather stopped at its limit (`cut`), whether the read
// is whole and resolved (`complete`), the read's number (newer reads have
// bigger ones) and the swap count its songs were resolved at. A play
// queue's also says which entry it was on and how far into it.
@Entity(
    tableName = "server_head",
    indices = [Index(value = ["kind", "listId", "readSeq"], name = "index_server_head_list")],
)
data class ServerHeadEntity(
    @PrimaryKey val owner: String,
    val kind: String,
    val listId: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
    val songCount: Int?,
    val cover: String?,
    @ColumnInfo(defaultValue = "0") val cut: Boolean = false,
    @ColumnInfo(defaultValue = "0") val complete: Boolean = false,
    val readSeq: Long,
    val swapCount: Long,
    val currentAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0,
)
