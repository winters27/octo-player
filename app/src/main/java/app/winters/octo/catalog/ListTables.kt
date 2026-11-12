package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

// Rows a list screen or a queue fill orders its songs by when no library
// index holds the order. Each owner (a screen's view model, a car read, a
// queue list) has its own rows and clears them when it is done; every open
// of the database empties them too.

// A phone folder's songs in the folder page's order: its keys as bytes the
// database orders the way the page does.
@Entity(
    tableName = "lists_folder_order",
    primaryKeys = ["owner", "trackId"],
    indices = [Index("owner", "lead", "ties", name = "index_lists_folder_order")],
)
class ListsFolderOrderEntity(val owner: String, val lead: ByteArray, val ties: ByteArray, val trackId: String)

// A library album as its server lists it, for an album too big to hold:
// each listed song in the server's order (`position`), the song it shows
// (`shownId`: the library song, the library song a find became, or the
// find), and whether that is a find, numbered as the album numbers it.
// A queue's copy also holds the album's library songs the listing leaves
// out, after the listing, with their place in the album (`albumOrder`).
// A library song's rows are found by its id, every owner's at once, when
// the song's id moves.
@Entity(
    tableName = "lists_album_entry",
    primaryKeys = ["owner", "position"],
    indices = [
        Index("owner", "shownId", "position", name = "index_lists_album_entry_shown"),
        Index("owner", "albumOrder", "shownId", name = "index_lists_album_entry_lib"),
        Index("shownId"),
    ],
)
class ListsAlbumEntryEntity(
    val owner: String,
    val position: Long,
    val songId: String,
    val shownId: String,
    val isFind: Boolean,
    val albumOrder: Long?,
    val trackNo: Int?,
    val discNo: Int?,
)

// One level of a server's folders as the server lists it, for the folder
// page: each folder, then each song, at its place in the answer
// (`position`), `kind` 0 for a folder and 1 for a song, the server's id for
// it, the name shown (a folder's name, a song's title), a folder's cover
// and song count as the server gives them, and the song a song resolved to
// (`trackId`: the library's song, the one a find became, or the find; null
// where the library's song is gone) with its relink key, so a swap's
// relinks follow it. A song's `lead` and `ties` are its keys in the order
// the page shows the songs in, as a phone folder's copy keys them; a
// folder's are empty, so the order index walks the folders first, in the
// server's order, then the songs in theirs. The song index finds a song's
// first row, so a song listed twice shows once; the id index finds every
// owner's rows of a song when its id moves.
@Entity(
    tableName = "lists_server_dir",
    primaryKeys = ["owner", "position"],
    indices = [
        Index("owner", "trackId", "position", name = "index_lists_server_dir_track"),
        Index("owner", "kind", "lead", "ties", "position", name = "index_lists_server_dir_order"),
        Index("trackId"),
    ],
)
class ListsServerDirEntity(
    val owner: String,
    val position: Long,
    val kind: Int,
    val id: String,
    val name: String,
    val coverArt: String?,
    val songCount: Int?,
    val trackId: String?,
    val lead: ByteArray,
    val ties: ByteArray,
    @ColumnInfo(defaultValue = "") val relinkKey: String = "",
)

// A list whose order reads likes, ratings or downloads, copied with those
// values as they were when each row was read, so a like or a finished
// download during a long walk moves nothing. The key is the list's order.
@Entity(
    tableName = "lists_sort_copy",
    primaryKeys = ["owner", "segment", "leadKey", "tieKey", "sortKey", "titleKey", "trackId", "sourceId", "serverId"],
    indices = [Index("owner", "trackId", name = "index_lists_sort_copy_track")],
)
class ListsSortCopyEntity(
    val owner: String,
    val segment: Int,
    // The order's first value (a rating, when it was liked, a size), and
    // the same negated when the list runs high to low.
    val lead: Long,
    val leadKey: Long,
    val liked: Boolean,
    val tieKey: Int,
    val sortKey: String,
    val trackId: String,
    // A download's title key and its row; empty for a list of songs.
    val titleKey: ByteArray,
    val sourceId: String,
    val serverId: String,
)
