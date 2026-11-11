package app.winters.octo.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The rows a save works through between its find and its last group: what
// the find found to write, the references it re-points, the sets of ids
// that move together and the group each lands in, and the renames in
// force. No screen reads them but the renames, which play starts, user
// writes and the rename triggers (SaveTriggers.kt) follow.
//
// A found row and a reference are numbered by SQLite as they go in (`seq`,
// the table's rowid, left out of the insert), with no counter of its own to
// write: a save's writes are all counted, and a counter's are not.

// The kinds of id a rename, a reference and a set's node hold: a library
// song's, album's or artist's. The album and artist kinds are the names a
// pin's kind has (PinKind), so a pin's own kind picks its renames.
const val RENAME_SONG = "track"
const val RENAME_ALBUM = "album"
const val RENAME_ARTIST = "artist"

// What a found row does to the row it names (`op`).
const val FOUND_NEW = 0
const val FOUND_CHANGED = 1
const val FOUND_PLACED = 2
const val FOUND_GONE = 3

// One row the find found to write, by the table it is in (`tbl`: a source
// table, a library table, or the name of what else it found) and its key
// (`id`): what it does (`op`), the ids it held and takes where it moves
// one (a copy's merged id), the rating it was seen with and the one it
// takes, what writing it costs, and once planned, its set and group.
@Entity(
    tableName = "save_found",
    indices = [Index("groupId", "tbl", "op", "id"), Index("setId"), Index("tbl", "id")],
)
data class SaveFoundEntity(
    @PrimaryKey val seq: Long,
    val tbl: String,
    val op: Int,
    val id: String,
    val oldId: String? = null,
    val newId: String? = null,
    val seenRating: Int? = null,
    val newRating: Int? = null,
    val cost: Long = 0,
    val setId: Long? = null,
    val groupId: Long? = null,
)

// One row of a reference table (`tbl`) whose id the save moves, by the
// table's own primary key: its text part (`keyText`) and its number parts
// (`keyNum`, `keyNum2`), as each table's RenameTarget names them; the kind
// of id it holds, the id and relink key it was seen with, where it goes
// (null: it stays dangling), and once planned, its set and group.
@Entity(tableName = "save_ref", indices = [Index("groupId", "tbl", "seq"), Index("setId")])
data class SaveRefEntity(
    @PrimaryKey val seq: Long,
    val tbl: String,
    val kind: String,
    val keyText: String = "",
    val keyNum: Long = 0,
    val keyNum2: Long = 0,
    val oldId: String,
    val relinkKey: String,
    val target: String?,
    val setId: Long? = null,
    val groupId: Long? = null,
)

// One changed id of a save as a node of the union-find over the ids that
// must move together: its number, its kind and id, its parent (itself at a
// root, every node's root once the find ends) and its tree's size.
@Entity(tableName = "save_node", indices = [Index("kind", "id", unique = true), Index("parent", "node")])
data class SaveNodeEntity(
    @PrimaryKey val node: Long,
    val kind: String,
    val id: String,
    val parent: Long,
    val size: Long,
)

// One set of ids that move together, by its root node: what writing it
// costs, and the group it lands in (the first of its pages for a set over
// the bound).
@Entity(tableName = "save_set", indices = [Index("groupId", "setId")])
data class SaveSetEntity(
    @PrimaryKey val setId: Long,
    val cost: Long,
    val groupId: Long? = null,
)

// A rename in force: an old library id (`old`) and the id it became
// (`new`), which is final. A rename by key (`byKey`) was made for the
// relink key `key` and reaches only rows with that key; a rename by follow
// reaches every row. Kept until a save that publishes renames of its own
// begins, so an id a car or a widget cached still follows.
@Entity(tableName = "save_rename", primaryKeys = ["kind", "old"], indices = [Index("kind", "new")])
data class SaveRenameEntity(
    val kind: String,
    val old: String,
    val new: String,
    val byKey: Boolean,
    val key: String,
)
