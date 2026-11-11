package app.winters.octo.catalog

// A row written with a library id that a rename in force (save_rename) has
// moved lands on the id it became, whoever writes it: each table that
// holds such an id has a trigger after every insert and after every change
// of its id column, which moves the row when a rename reaches it and the
// table's guard lets it. The guards are the library's relinks' own, so a
// write that finds the new id already held leaves its row where it was,
// whatever conflict clause the write names. The library's own id columns
// have none: only a save writes them.
//
// Room turns recursive triggers on, so a trigger's own update fires the
// table's update trigger once more; a rename's new id is final, so that
// one finds no rename and stops.

// What keeps a row on its old id although a rename reaches it.
enum class RenameGuard {
    None,

    // Another row already holds the new id: the id is the table's key.
    Taken,

    // The row's queue list already holds the new id.
    InList,
}

// A table whose `column` holds a library id of `kind` (null: the row's own
// `kind` column names it, as a pin's does). A rename by key reaches a row
// only where the table keeps a relink key (`keyed`) and the row's is the
// rename's; `followOnly` tables take renames by follow alone. `only` is a
// value the row must hold to move. `keyText` and `keyNums` are the table's
// primary key as save_ref keeps it: its text part, then its number parts.
// `keyFollows` is for the listener's own rows: a save leaves each one that
// names a library song, album or artist with a key it is filed under, its
// own or one of its copies', so a later save that takes the id away finds
// it again by that key.
class RenameTarget(
    val table: String,
    val column: String,
    val kind: String?,
    val keyed: Boolean = false,
    val followOnly: Boolean = false,
    val keyFollows: Boolean = false,
    val guard: RenameGuard = RenameGuard.None,
    val only: Pair<String, Int>? = null,
    val keyText: String? = null,
    val keyNums: List<String> = emptyList(),
) {
    // The trigger's condition and its one statement, with each value of
    // the written row given by `row` (NEW.<column> in the trigger, a bound
    // argument when a test plans them on their own).
    fun condition(row: (String) -> String): String = buildString {
        only?.let { (name, value) -> append(row(name)).append(" = ").append(value).append(" AND ") }
        append("EXISTS (SELECT 1 FROM save_rename r WHERE ").append(reaches(row))
        when (guard) {
            RenameGuard.None -> Unit
            RenameGuard.Taken -> {
                append(" AND NOT EXISTS (SELECT 1 FROM `").append(table).append("` o WHERE o.`").append(column).append("` = r.`new`")
                if (kind == null) append(" AND o.`kind` = ").append(row("kind"))
                append(")")
            }
            RenameGuard.InList -> append(" AND NOT EXISTS (SELECT 1 FROM `").append(table).append("` o WHERE o.`").append(column)
                .append("` = r.`new` AND +o.`queueId` = ").append(row("queueId")).append(" AND +o.`listId` = ").append(row("listId")).append(")")
        }
        append(")")
    }

    fun body(row: (String) -> String): String =
        "UPDATE `$table` SET `$column` = (SELECT r.`new` FROM save_rename r WHERE r.kind = ${kindOf(row)} AND r.`old` = ${row(column)}) " +
            "WHERE rowid = ${row("rowid")}"

    // The rename that reaches a row: of its id and kind, and by follow, or
    // by key for the row's own relink key where the table keeps one.
    private fun reaches(row: (String) -> String): String = buildString {
        append("r.kind = ").append(kindOf(row)).append(" AND r.`old` = ").append(row(column))
        when {
            followOnly -> append(" AND r.byKey = 0")
            keyed -> append(" AND (r.byKey = 0 OR ").append(row("relinkKey")).append(" = r.`key`)")
        }
    }

    private fun kindOf(row: (String) -> String): String = if (kind != null) "'$kind'" else row("kind")

    // The table's two triggers: after an insert, and after its id changes.
    val triggers: List<String>
        get() = listOf("insert" to "INSERT", "update" to "UPDATE OF `$column`").map { (name, event) ->
            "CREATE TRIGGER IF NOT EXISTS `save_rename_${table}_$name` AFTER $event ON `$table` " +
                "WHEN ${condition(::written)} BEGIN ${body(::written)}; END"
        }
}

// A value of the row a trigger fired for.
private fun written(column: String): String = if (column == "rowid") "NEW.rowid" else "NEW.`$column`"

// Every table a user or a job writes library ids to, outside the library's
// own rows.
val SAVE_RENAME_TARGETS: List<RenameTarget> = listOf(
    RenameTarget("liked_track", "trackId", RENAME_SONG, keyed = true, keyFollows = true, guard = RenameGuard.Taken, keyText = "trackId"),
    RenameTarget("track_rating", "trackId", RENAME_SONG, keyed = true, keyFollows = true, guard = RenameGuard.Taken, keyText = "trackId"),
    RenameTarget("play_event", "trackId", RENAME_SONG, keyed = true, keyFollows = true, keyNums = listOf("id")),
    RenameTarget("playlist_item", "trackId", RENAME_SONG, keyed = true, keyFollows = true, keyNums = listOf("id")),
    // A walk reads a playlist's rows from here as they stood, so they keep
    // the keys the playlist's own rows have.
    RenameTarget("playlist_edit_log", "trackId", RENAME_SONG, keyed = true, keyFollows = true, keyNums = listOf("seq")),
    RenameTarget("playlist_entry", "trackId", RENAME_SONG, keyed = true, keyNums = listOf("read", "position")),
    RenameTarget("playlist_match", "trackId", RENAME_SONG, keyed = true, keyNums = listOf("id")),
    RenameTarget("playlist_local", "trackId", RENAME_SONG, keyed = true, keyNums = listOf("itemPosition", "itemKey")),
    RenameTarget("playlist_next", "trackId", RENAME_SONG, keyed = true, keyNums = listOf("position")),
    RenameTarget("server_entry", "trackId", RENAME_SONG, keyed = true, keyText = "owner", keyNums = listOf("position")),
    RenameTarget("server_match", "trackId", RENAME_SONG, keyed = true, keyNums = listOf("id")),
    // An album's listing moves only the library songs it shows, never a find.
    RenameTarget("lists_album_entry", "shownId", RENAME_SONG, followOnly = true, only = "isFind" to 0, keyText = "owner", keyNums = listOf("position")),
    // A folder level moves only its songs (kind 1), never a folder.
    RenameTarget("lists_server_dir", "trackId", RENAME_SONG, keyed = true, only = "kind" to 1, keyText = "owner", keyNums = listOf("position")),
    RenameTarget("queue_entry", "trackId", RENAME_SONG, followOnly = true, guard = RenameGuard.InList, keyNums = listOf("queueId", "entry")),
    // The library song a downloaded find became.
    RenameTarget("online_song", "adoptedId", RENAME_SONG, followOnly = true, keyText = "id"),
    RenameTarget("liked_album", "albumId", RENAME_ALBUM, keyed = true, keyFollows = true, guard = RenameGuard.Taken, keyText = "albumId"),
    RenameTarget("liked_artist", "artistId", RENAME_ARTIST, keyed = true, keyFollows = true, guard = RenameGuard.Taken, keyText = "artistId"),
    // A pin's kind is the rename's kind: an album's or an artist's, and no
    // rename names a playlist.
    RenameTarget("pinned_item", "itemId", kind = null, keyed = true, keyFollows = true, guard = RenameGuard.Taken, keyText = "itemId"),
)

// The key a library row of `kind` is filed under, which the rows that
// follow it keep: a song's relink key, an album's or artist's search key.
fun libraryKeyColumn(kind: String): String = if (kind == RENAME_SONG) "relinkKey" else "searchKey"

// The triggers, made once a new library's tables are, and last of all in
// the upgrade to version 19, after every table it copies.
val SAVE_RENAME_TRIGGERS: List<String> = SAVE_RENAME_TARGETS.flatMap { it.triggers }
