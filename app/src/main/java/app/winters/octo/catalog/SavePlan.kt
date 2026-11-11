package app.winters.octo.catalog

import androidx.sqlite.db.SupportSQLiteDatabase

// How much one write transaction of a save may cost (SaveCosts): every
// index entry it writes, every row it reads through an index, and a probe
// of the change tracker for every row it writes. The find's own writes, the
// unions of its sets, its clears and each group of the apply stay within
// it. Chosen from HeapProof1bTest.saveBoundsAtProofSize, a server switch
// and a mass retag of 200,000 songs at bounds from SAVE_GROUP_FLOOR to
// 8,192: a smaller bound makes the save longer and its commits many more
// (each one a sync of the file on a phone), and 8,192 doubles the largest
// transaction a write beside the save may wait out.
const val SAVE_GROUP_ENTRIES = 4_096L

// The least bound a save runs with: the largest single unit of its work,
// so every unit fits one transaction. That is one page of a run holding a
// gone song alone (SaveCosts.largestRunPage), which costs more than one
// union of the find's sets at the deepest tree any count of rows allows
// (SAVE_DEEPEST_FIND reads a find) and more than a song row written with
// all its indexes, its reads and the removal of its found row.
// SaveGroupBoundTest works each out from the schema.
const val SAVE_GROUP_FLOOR = 126L

// The copies of one song the floor counts: the phone's and the server's in
// use, the only sources in the library. A save of a claim with more sources
// runs with a bound raised to its own largest unit (CatalogMerge.saveBoundFor).
const val SAVE_MOST_COPIES = 2L

// The most reads one find of the sets takes: a set's tree is at most
// log2(n) deep under union by size, and n, a count of rows, is below 2^31.
const val SAVE_DEEPEST_FIND = 32L

// A probe of Room's change tracker for every row written, counted as if
// every table were watched.
const val TRACKER_PROBE = 1L

// What applying one found row reads besides its writes: the row itself,
// read with its group's by the group's key; for a changed row, which of its
// columns still differ (the row and the one it is written from, each by its
// key) and then the two again as it writes; and its removal by the group's
// key.
const val APPLY_READS = 6L

// What applying one reference reads: the reference row with its group's
// by the group's key, the referencing row by its table's key and the
// table's guard by the new id, and the reference's removal by the group's
// key. A table with rename triggers adds the trigger's probe of the
// renames (RENAME_PROBE).
const val REF_READS = 4L
const val RENAME_PROBE = 1L

// What a rename's catch-up reads: whether each table that can hold a
// renamed song's id holds it, one table a column of one read.
val RENAME_CATCH_UP: Long = SAVE_RENAME_TARGETS.count { it.kind == RENAME_SONG || it.kind == null }.toLong()

// What one recount of a song's play total reads, for each copy a song can
// have (one a source): the copy, its song, what was sent of its plays and
// how many of those the server holds; and five more: the song's own row,
// its stored total, its latest play, and its found row read and removed.
const val TOTALS_READS_PER_COPY = 4L
const val TOTALS_READS = 5L

// What every group reads besides its rows: its found rows and references
// read and removed by its key with none there (one read each), the account
// its totals count for, and the swap count's row.
const val GROUP_READS = 6L

// Refuses a bound below the floor, and answers it.
fun saveBound(entries: Long): Long {
    require(entries >= SAVE_GROUP_FLOOR) { "a save's bound of $entries is below the largest unit of its work, $SAVE_GROUP_FLOOR" }
    return entries
}

// The cost of one transaction or of one piece of it, split into what it
// reads and what it writes (index entries and tracker probes).
data class SaveCost(val reads: Long, val writes: Long) {
    val total: Long get() = reads + writes

    operator fun plus(other: SaveCost) = SaveCost(reads + other.reads, writes + other.writes)

    operator fun times(n: Long) = SaveCost(reads * n, writes * n)

    companion object {
        val NONE = SaveCost(0, 0)
    }
}

// What a save's writes cost, from the tables' own indexes, each named by
// the columns it holds; a table keyed by anything but its rowid keeps its
// key in an index of its own, which counts like any other.
class SaveCosts(private val indexes: Map<String, List<List<String>>>) {
    fun indexesOf(table: String): List<List<String>> = indexes[table] ?: error("no indexes read for $table")

    // A row inserted or deleted: the row, its entry in every index of its
    // table, and the tracker's probe.
    fun whole(table: String): Long = 1 + indexesOf(table).size + TRACKER_PROBE

    // A row changed: the row, its entry in each index holding one of
    // `columns`, and the tracker's probe.
    fun changed(table: String, columns: Collection<String>): Long =
        1 + indexesOf(table).count { index -> index.any { it in columns } } + TRACKER_PROBE

    // One union of two ids' sets with each find `depth` reads at most: two
    // finds, the two nodes made where they are new, the smaller root linked
    // under the larger (a read of its row by key and the write) and the
    // larger's size written (the same).
    fun edge(depth: Long): SaveCost = SaveCost(
        reads = 2 * depth + 2,
        writes = 2 * whole("save_node") + changed("save_node", listOf("parent")) + changed("save_node", listOf("size")),
    )

    // An id made a set of its own where it is not in one yet: its node read
    // by its id, and made.
    fun touch(): SaveCost = SaveCost(1, whole("save_node"))

    // A node pointed straight at its root: the walk up from its parent, and
    // its parent written (a read of its row by key and the write).
    fun pointAtRoot(depth: Long): SaveCost = SaveCost(depth + 1, changed("save_node", listOf("parent")))

    // A page of found rows or references given their set, or their group:
    // each row read by its key, the set's (or the group's) row read by key,
    // and the column written.
    fun foundSet(rows: Long): SaveCost = SaveCost(2 * rows, changed("save_found", listOf("setId")) * rows)

    fun refSet(rows: Long): SaveCost = SaveCost(2 * rows, changed("save_ref", listOf("setId")) * rows)

    fun foundGroup(rows: Long): SaveCost = SaveCost(2 * rows, changed("save_found", listOf("groupId")) * rows)

    fun refGroup(rows: Long): SaveCost = SaveCost(2 * rows, changed("save_ref", listOf("groupId")) * rows)

    // A set's row, and its group written.
    fun setIn(): SaveCost = SaveCost(0, whole("save_set"))

    fun setGroup(): SaveCost = SaveCost(1, changed("save_set", listOf("groupId")))

    // A page of a work table's rows cleared, each read by its key.
    fun cleared(table: String, rows: Long): SaveCost = SaveCost(rows, whole(table) * rows)

    // A found row or a reference going in, read by nothing but its key.
    fun foundIn(): SaveCost = SaveCost(0, whole("save_found"))

    fun refIn(): SaveCost = SaveCost(0, whole("save_ref"))

    // A found row whose song is counted again, unless its song has one
    // already: the check by the found rows' (tbl, id) index, then the row.
    fun totalsIn(): SaveCost = SaveCost(1, whole("save_found"))

    // A found row's rating put right (and its op, which save_found indexes),
    // or the row taken out again: each looks the row up by its key.
    fun foundRated(): SaveCost = SaveCost(1, changed("save_found", listOf("newRating", "op")))

    fun foundOut(): SaveCost = SaveCost(1, whole("save_found"))

    // Applying a found row or a reference, its own removal by its group's
    // key included (each group deletes its rows), as the plan packs them.
    fun applyFound(write: Long): Long = APPLY_READS + write + whole("save_found")

    fun applyRef(table: String, column: String, triggered: Boolean): Long =
        REF_READS + (if (triggered) RENAME_PROBE else 0) + changed(table, listOf(column)) + whole("save_ref")

    // A row's relink key set to the key its song is filed under: as a
    // reference's reads, with the key read by the song's id in place of the
    // guard; the key written; the reference removed. Its id does not change,
    // so no rename trigger fires.
    fun applyRekey(table: String): Long = REF_READS + changed(table, listOf("relinkKey")) + whole("save_ref")

    // A rename published and caught up (see RENAME_CATCH_UP), and its row
    // removed.
    fun applyRename(): Long = REF_READS + RENAME_CATCH_UP + whole("save_rename") + whole("save_ref")

    // A song's play total counted again with `copies` copies a song can
    // have, and its row written.
    fun applyTotals(copies: Long): Long = TOTALS_READS + TOTALS_READS_PER_COPY * copies + whole("play_total") + whole("save_found")

    // What every group costs besides its rows: its reads (GROUP_READS) and
    // the swap count raised.
    fun groupOverhead(): Long = GROUP_READS + changed("merge_state", listOf("swapCount"))

    // A song a run's page touches: its mark looked up, and its play total
    // counted again.
    fun applyTouch(copies: Long): Long = 1 + applyTotals(copies)

    // The largest page of a run, a gone song alone: the read of its found
    // row, its own copy's three look-ups, the song's row gone and its own
    // copy's row gone with their indexes, the totals of the song and of the
    // one its copy moves to, and what every group costs.
    fun largestRunPage(copies: Long): Long =
        1 + 3 + applyFound(whole("track")) + applyFound(whole("source_track")) + 2 * applyTouch(copies) + groupOverhead()

    // The largest single unit of the work of a save whose songs can have
    // `copies` copies, which its bound must hold; the floor holds it at
    // SAVE_MOST_COPIES.
    fun largestUnit(copies: Long = SAVE_MOST_COPIES): Long = maxOf(edge(SAVE_DEEPEST_FIND).total, applyFound(whole("track")), largestRunPage(copies))

    companion object {
        // Every index of each table, read from the schema.
        fun of(sql: SupportSQLiteDatabase, tables: Collection<String>): SaveCosts =
            SaveCosts(tables.associateWith { indexesOf(sql, it) })

        private fun indexesOf(sql: SupportSQLiteDatabase, table: String): List<List<String>> {
            val names = sql.query("PRAGMA index_list(`$table`)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                buildList { while (c.moveToNext()) add(c.getString(name)) }
            }
            return names.map { index ->
                sql.query("PRAGMA index_info(`$index`)").use { c ->
                    val name = c.getColumnIndexOrThrow("name")
                    buildList { while (c.moveToNext()) add(c.getString(name)) }
                }
            }
        }
    }
}

// Every table a save writes or whose writes it plans, for SaveCosts.
val SAVE_COST_TABLES: List<String> = listOf(
    "source_track", "source_album", "source_artist", "track", "album", "artist", "rating_tap", "play_total", "merge_state",
    "save_found", "save_ref", "save_node", "save_set", "save_rename",
) + SAVE_RENAME_TARGETS.map { it.table }

// Packs costs, in the order they come, into numbered groups of at most
// `capacity` each: as many to a group as fit, and one over the capacity in
// a group of its own (a run, which the apply writes in pages).
class GroupPacker(private val capacity: Long, firstGroup: Long = 1) {
    private var group = firstGroup - 1
    private var fill = Long.MAX_VALUE
    var runs = 0L
        private set

    // The last group handed out, 0 before any.
    val last: Long get() = group.coerceAtLeast(0)

    fun add(cost: Long): Long {
        if (cost > capacity) {
            runs++
            group++
            fill = Long.MAX_VALUE
            return group
        }
        if (fill > capacity - cost) {
            group++
            fill = 0
        }
        fill += cost
        return group
    }
}
