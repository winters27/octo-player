package app.winters.octo.catalog

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.winters.octo.device.DEVICE
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.withContext
import java.io.Closeable

// What a save applies: the sources in the library in rank order, and each
// copy it claimed, by its source, with the copy's generation. A skipped
// merge has none (null): the library stands as it is, and the find finds
// only what follows its ids.
class SaveClaim(val sources: List<String>, val staged: Map<String, Long>)

// The find's label on its writes.
internal const val SAVE_FIND = "save find"

// Points a test can watch the find at. Each does nothing in the app.
internal open class SaveFindHooks {
    // Each write transaction of the find, once it committed: which of its
    // steps wrote it and what it was planned to cost, which the bound holds.
    open fun wrote(what: String, cost: SaveCost) {}

    // Each pair of ids the find puts in one set, and why (EDGE_*).
    open fun joined(why: String, a: SaveNodeKey, b: SaveNodeKey) {}

    // Each walk of the find as it begins, by name; the next one's start
    // ends it, and the find's answer ends the last.
    open fun walking(walk: String) {}
}

// Why two ids move together: a gone id and where one of its references
// goes, or where it goes itself (its rename); a gone song and a new song
// with its relink key; a source row's song before and after; a song or an
// album and its album or artist that comes or goes.
internal const val EDGE_REFERENCE = "reference"
internal const val EDGE_RENAME = "rename"
internal const val EDGE_KEY = "key"
internal const val EDGE_FOLLOW = "follow"
internal const val EDGE_ALBUM = "album"
internal const val EDGE_ARTIST = "artist"

// What the find found and how the apply runs it: the found rows, the
// references, the sets, the groups planned, and how many of them are runs
// (a set over the bound, which the apply writes in pages).
data class SaveFound(val found: Long, val refs: Long, val sets: Long, val groups: Long, val runs: Long)

// The find of a save (the merge's build is done, under the source-write
// gate): what today's swap would write, worked out by reads alone, outside
// every transaction and the merge lock, a page of at most MERGE_PAGE keys at
// a time, and written to save_found and save_ref in short transactions of at
// most `bound` each (SaveCosts). Then the sets of ids that must move
// together (SaveSets), each set's cost, and the groups the apply runs in.
//
// It finds, as the swap's statements would write them:
// - each claimed copy's rows against its source's (stage_* against
//   source_*): new, changed, placed elsewhere, gone, byte for byte, with the
//   rating each copy was seen with;
// - the library's songs, albums and artists against the merge's output,
//   with today's year and rating, a song's rating after the phone's ratings
//   and the ratings noted since the claim show on it;
// - each source row whose library song changes, and copies of sources out
//   of the library, which belong to no song;
// - every row that follows a library id and points at one the library will
//   not have, gone now or before the save, with where today's relinks move
//   it (none: it stays as it is), and a rename for every song, album and
//   artist that goes and has somewhere to go;
// - every one of the listener's rows (likes, ratings, plays, playlist rows
//   and their edits, favorite albums and artists, pins) on a song, album or
//   artist the library keeps, or moving to one, whose relink key neither it
//   nor any of its copies is filed under then: it takes that key
//   (RenameTarget.keyFollows);
// - rating taps whose server copy goes, and the songs whose play totals are
//   counted again.
//
// The skipped merge finds only the rows that follow ids and the phone's
// ratings, against the library as it is.
internal class SaveFind(
    private val db: CatalogDatabase,
    bound: Long = SAVE_GROUP_ENTRIES,
    private val hooks: SaveFindHooks = SaveFindHooks(),
) {
    val bound: Long = saveBound(bound)

    // How many rows one read of the find takes, of each table: the app's
    // read page. The bound sizes only its write transactions.
    private val readPage: Int = MERGE_PAGE

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    suspend fun find(claim: SaveClaim?): SaveFound = withContext(WriteLabels.of(SAVE_FIND)) {
        val costs = SaveCosts.of(sql, SAVE_COST_TABLES)
        clearWith(costs)
        Find(claim, costs).run()
    }

    // The find's rows go, a page a transaction (the renames stay).
    suspend fun clear() = withContext(WriteLabels.of(SAVE_FIND)) { clearWith(SaveCosts.of(sql, SAVE_COST_TABLES)) }

    private suspend fun clearWith(costs: SaveCosts) {
        val writer = Writer(SaveNodeNumbers(0), "clear")
        for ((table, key) in listOf("save_found" to "seq", "save_ref" to "seq", "save_node" to "node", "save_set" to "setId")) {
            val rows = (bound / (costs.whole(table) + 1)).coerceAtLeast(1)
            while (true) {
                val end = long("SELECT `$key` FROM `$table` ORDER BY `$key` LIMIT 1 OFFSET ?1", rows - 1)
                if (end == null) {
                    if (long("SELECT EXISTS (SELECT 1 FROM `$table`)") == 1L) {
                        writer.add(costs.cleared(table, rows)) { it.run("DELETE FROM `$table`") }
                        writer.flush()
                    }
                    break
                }
                writer.add(costs.cleared(table, rows)) { it.run("DELETE FROM `$table` WHERE `$key` <= ?1", end) }
                writer.flush()
            }
        }
    }

    // Write transactions of the find: each piece of work goes in with what
    // it costs, and a transaction commits before the next piece would take
    // it past the bound.
    private inner class Writer(private val numbers: SaveNodeNumbers, var what: String) {
        private val ops = ArrayList<(Tx) -> Unit>()
        private var cost = SaveCost.NONE

        suspend fun add(piece: SaveCost, op: (Tx) -> Unit) {
            check(piece.total <= bound) { "one piece of the find costs ${piece.total}, over the bound $bound" }
            if (ops.isNotEmpty() && (cost + piece).total > bound) flush()
            ops += op
            cost += piece
        }

        suspend fun flush() {
            if (ops.isEmpty()) return
            val batch = ArrayList(ops)
            val planned = cost
            ops.clear()
            cost = SaveCost.NONE
            db.withTransaction { Tx(db.openHelper.writableDatabase, numbers).use { tx -> batch.forEach { it(tx) } } }
            hooks.wrote(what, planned)
        }
    }

    // One write transaction's statements, each compiled once.
    private class Tx(private val sql: SupportSQLiteDatabase, private val numbers: SaveNodeNumbers) : Closeable {
        private val statements = HashMap<String, SupportSQLiteStatement>()
        private var opened: SaveSets? = null

        val sets: SaveSets get() = opened ?: SaveSets(sql, numbers).also { opened = it }

        fun run(statement: String, vararg args: Any?): Int = compiled(statement).let {
            it.bindAll(*args)
            it.executeUpdateDelete()
        }

        private fun compiled(statement: String) = statements.getOrPut(statement) { sql.compileStatement(statement) }

        override fun close() {
            statements.values.forEach { it.close() }
            opened?.close()
        }
    }

    // The keys of a page: those past `after` up to `end`, or below it
    // (`below`, for the end of a source's ids), or all past `after` when
    // `end` is null. A statement reads `after` as ?1 and `end` as ?2, and
    // its own arguments from ?3 on.
    private class Range(val after: String, val end: String?, val below: Boolean = false) {
        fun where(column: String): String = when {
            end == null -> "$column > ?1 AND ?2 IS NULL"
            below -> "$column > ?1 AND $column < ?2"
            else -> "$column > ?1 AND $column <= ?2"
        }

        fun args(vararg more: Any?): Array<Any?> = arrayOf(after, end, *more)
    }

    private fun <T> rows(query: String, args: Array<out Any?>, row: (Cursor) -> T): List<T> =
        sql.query(query, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    private fun long(query: String, vararg args: Any?): Long? = sql.query(query, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun text(query: String, vararg args: Any?): String? = sql.query(query, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    // Walks the keys of `tables` together, in SQLite's order, from past
    // `from` to below `until` (null: to the last), a page of at most
    // `readPage` keys of each table at a time.
    //
    // A source's rows are walked by the id range "$sourceId:" to
    // "$sourceId;", since every source row's id is "$sourceId:" followed by
    // the source's own id for it (ServerCatalog and DeviceCatalogBuilder
    // build them so, SourceIdFormTest holds them to it). One source's
    // id can begin another's ("server:host" and "server:host:8443"), so every
    // read of such a range also keeps to `+sourceId = ?3`.
    private suspend fun walk(tables: List<String>, column: String, from: String, until: String?, each: suspend (Range) -> Unit) {
        var after = from
        while (true) {
            val bounds = Range(after, until, below = true)
            val ends = tables.mapNotNull { table ->
                text("SELECT `$column` FROM `$table` WHERE ${bounds.where("`$column`")} ORDER BY `$column` LIMIT 1 OFFSET ?3", *bounds.args(readPage - 1))
            }
            if (ends.isEmpty()) {
                each(bounds)
                return
            }
            val end = ends.minWithOrNull(SqlOrder::text)!!
            each(Range(after, end))
            after = end
        }
    }

    // Walks a table's integer key, a page of `rows` keys at a time: each
    // page is (after, end], the last one open.
    private suspend fun walkSeq(table: String, key: String, rows: Long, each: suspend (after: Long, end: Long) -> Unit) {
        var after = Long.MIN_VALUE
        while (true) {
            val end = long("SELECT `$key` FROM `$table` WHERE `$key` > ?1 ORDER BY `$key` LIMIT 1 OFFSET ?2", after, rows - 1)
            if (end == null) {
                if (long("SELECT EXISTS (SELECT 1 FROM `$table` WHERE `$key` > ?1)", after) == 1L) each(after, Long.MAX_VALUE)
                return
            }
            each(after, end)
            after = end
        }
    }

    private inner class Find(private val claim: SaveClaim?, private val costs: SaveCosts) {
        private val claimed = claim != null
        private val staged: Map<String, Long> = claim?.staged.orEmpty()

        // The library as the save leaves it: the merge's output, or for a
        // skipped merge the library as it is.
        private val songs = if (claimed) "merge_candidate" else "track"
        private val albums = if (claimed) "album_next" else "album"
        private val artists = if (claimed) "artist_next" else "artist"

        private val numbers = SaveNodeNumbers(long(LAST_NODE_SQL) ?: 0)
        private val writer = Writer(numbers, "found")

        // Every id that can become a node is a row of one of these, so their
        // rows bound how deep a set's tree grows.
        private val depth = SaveSets.depth(
            (listOf("track", "album", "artist", "source_track", "stage_track") +
                (if (claimed) listOf("merge_candidate", "album_next", "artist_next") else emptyList()) +
                SAVE_RENAME_TARGETS.map { it.table }.distinct())
                .sumOf { long("SELECT COUNT(*) FROM `$it`") ?: 0 } + 1,
        )

        // How many copies one song can have: one a source.
        private val copies = (claim?.sources?.size ?: 2).toLong().coerceAtLeast(1)

        // Gone or vanished albums and artists of a claimed copy's source,
        // whose copies' songs are counted as the copy is read.
        private val wanted = mapOf(RENAME_ALBUM to HashSet<String>(), RENAME_ARTIST to HashSet<String>())
        private val stagedCounts = mapOf(RENAME_ALBUM to HashMap<String, HashMap<String, Int>>(), RENAME_ARTIST to HashMap<String, HashMap<String, Int>>())
        private val mergedCache = HashMap<SaveNodeKey, String?>()

        // Ratings moved onto a song with a rating row whose id it took.
        private val movedRatings = ArrayList<Pair<String, Int>>()

        suspend fun run(): SaveFound {
            if (claim != null) {
                hooks.walking("artists")
                library(RENAME_ARTIST)
                writer.flush()
                hooks.walking("albums")
                library(RENAME_ALBUM)
                writer.flush()
                hooks.walking("wanted")
                wantedHeld()
                hooks.walking("copies")
                for ((source, generation) in staged) copy(source, generation)
                writer.flush()
                hooks.walking("songs")
                librarySongs()
                writer.flush()
                hooks.walking("merged ids")
                mergedIds(claim)
                hooks.walking("unmerges")
                unmerges(claim)
                writer.flush()
                hooks.walking("renames")
                renames()
                writer.flush()
            }
            writer.what = "refs"
            hooks.walking("references")
            references()
            artistRekeys()
            writer.flush()
            writer.what = "found"
            hooks.walking("ratings")
            ratings()
            if (staged.keys.any { it != DEVICE }) {
                hooks.walking("taps")
                taps()
            }
            writer.flush()
            hooks.walking("roots")
            roots()
            hooks.walking("set ids")
            setIds()
            hooks.walking("set costs")
            setCosts()
            hooks.walking("plan")
            return plan()
        }

        // Pieces of work

        private suspend fun found(tbl: String, op: Int, id: String, cost: Long, oldId: String? = null, newId: String? = null, seen: Int? = null, new: Int? = null) =
            writer.add(costs.foundIn()) { it.run(FOUND_IN_SQL, tbl, op, id, oldId, newId, seen, new, cost) }

        private suspend fun edge(why: String, a: SaveNodeKey, b: SaveNodeKey) {
            if (a == b) return touch(a)
            hooks.joined(why, a, b)
            writer.add(costs.edge(depth)) { it.sets.union(a, b) }
        }

        private suspend fun touch(a: SaveNodeKey) = writer.add(costs.touch()) { it.sets.touch(a) }

        // A song whose play total is counted again, in its set.
        private suspend fun totals(song: String) {
            if (song.isEmpty()) return
            writer.add(costs.totalsIn()) { it.run(TOTALS_IN_SQL, FOUND_TOTALS, FOUND_CHANGED, song, costs.applyTotals(copies)) }
            touch(SaveNodeKey(RENAME_SONG, song))
        }

        private suspend fun ref(t: String, kind: String, keyText: String, keyNum: Long, keyNum2: Long, oldId: String, relinkKey: String, target: String) {
            writer.add(costs.refIn()) { it.run(REF_IN_SQL, t, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target) }
            edge(if (t == REF_RENAME) EDGE_RENAME else EDGE_REFERENCE, SaveNodeKey(kind, oldId), SaveNodeKey(kind, target))
        }

        private fun song(id: String) = SaveNodeKey(RENAME_SONG, id)

        // The library and the sources

        private fun finalOf(kind: String) = when (kind) {
            RENAME_ALBUM -> albums
            RENAME_ARTIST -> artists
            else -> songs
        }

        private fun liveOf(kind: String) = when (kind) {
            RENAME_ALBUM -> "album"
            RENAME_ARTIST -> "artist"
            else -> "track"
        }

        private fun isFinal(kind: String, id: String): Boolean = long("SELECT EXISTS (SELECT 1 FROM `${finalOf(kind)}` WHERE id = ?1)", id) == 1L

        // The claimed copy whose source an id is of, if any.
        private fun stagedSourceOf(id: String): String? = staged.keys.firstOrNull { id.startsWith("$it:") }

        private fun librarySourceOf(id: String): String? = claim?.sources?.firstOrNull { id.startsWith("$it:") }

        // What the find found of a library album or artist, if anything.
        private fun opOf(kind: String, id: String): Int? = long(FOUND_OP_SQL, liveOf(kind), id)?.toInt()

        // The library's albums or artists against the merge's.
        private suspend fun library(kind: String) {
            val live = liveOf(kind)
            val next = finalOf(kind)
            val columns = columnsOf(sql, live) - "id"
            val album = kind == RENAME_ALBUM
            val artistCol = if (album) ", c.artistId" else ""
            walk(listOf(next, live), "id", "", null) { r ->
                rows("SELECT c.id$artistCol FROM `$next` c WHERE ${r.where("c.id")} AND NOT EXISTS (SELECT 1 FROM `$live` t WHERE t.id = c.id)", r.args()) { c ->
                    c.getString(0) to if (album) c.getString(1) else null
                }.forEach { (id, artistId) ->
                    found(live, FOUND_NEW, id, costs.applyFound(costs.whole(live)))
                    if (artistId != null && opOf(RENAME_ARTIST, artistId) == FOUND_NEW) edge(EDGE_ARTIST, SaveNodeKey(kind, id), SaveNodeKey(RENAME_ARTIST, artistId))
                }
                rows("SELECT t.id${artistCol.replace("c.", "t.")} FROM `$live` t WHERE ${r.where("t.id")} AND NOT EXISTS (SELECT 1 FROM `$next` c WHERE c.id = t.id)", r.args()) { c ->
                    c.getString(0) to if (album) c.getString(1) else null
                }.forEach { (id, artistId) ->
                    found(live, FOUND_GONE, id, costs.applyFound(costs.whole(live)))
                    if (stagedSourceOf(id) != null) wanted.getValue(kind) += id
                    if (artistId != null && opOf(RENAME_ARTIST, artistId) == FOUND_GONE) edge(EDGE_ARTIST, SaveNodeKey(kind, id), SaveNodeKey(RENAME_ARTIST, artistId))
                }
                val ids = if (album) ", t.artistId, c.artistId" else ""
                rows(
                    "SELECT t.id$ids, ${differs(columns, "t") { "c.`$it`" }} FROM `$next` c CROSS JOIN `$live` t ON t.id = c.id " +
                        "WHERE ${r.where("c.id")} AND NOT (${same(columns, "t") { "c.`$it`" }})",
                    r.args(),
                ) { c ->
                    val at = if (album) 3 else 1
                    Triple(c.getString(0), if (album) c.getString(1) to c.getString(2) else null, columns.filterIndexed { i, _ -> c.getLong(at + i) != 0L })
                }.forEach { (id, moved, changed) ->
                    found(live, FOUND_CHANGED, id, costs.applyFound(costs.changed(live, changed)))
                    if (moved != null && moved.first != moved.second) {
                        if (opOf(RENAME_ARTIST, moved.first) == FOUND_GONE) edge(EDGE_ARTIST, SaveNodeKey(kind, id), SaveNodeKey(RENAME_ARTIST, moved.first))
                        if (opOf(RENAME_ARTIST, moved.second) == FOUND_NEW) edge(EDGE_ARTIST, SaveNodeKey(kind, id), SaveNodeKey(RENAME_ARTIST, moved.second))
                    }
                }
            }
        }

        // Albums and artists a favorite or a pin holds that the library will
        // not have, of a claimed copy's source: their copies' songs are
        // counted as the copy is read.
        private suspend fun wantedHeld() {
            for (t in SAVE_RENAME_TARGETS.filter { it.kind != RENAME_SONG }) {
                val kinds = if (t.kind == null) listOf(RENAME_ALBUM, RENAME_ARTIST) else listOf(t.kind)
                for (kind in kinds) {
                    danglingIds(t, kind) { id -> if (stagedSourceOf(id) != null) wanted.getValue(kind) += id }
                }
            }
        }

        // One claimed copy's rows against its source's, as the swap's
        // statements for a copy compare them.
        private suspend fun copy(source: String, generation: Long) {
            val moved = sql.query("SELECT movedTracks, movedAlbums, movedArtists FROM source_meta WHERE sourceId = ?1", arrayOf<Any?>(source)).use { c ->
                if (c.moveToFirst()) listOf(c.getInt(0), c.getInt(1), c.getInt(2)) else listOf(0, 0, 0)
            }
            sourceRows("source_track", "stage_track", RENAME_SONG, moved[0], source, generation)
            sourceRows("source_album", "stage_album", RENAME_ALBUM, moved[1], source, generation)
            sourceRows("source_artist", "stage_artist", RENAME_ARTIST, moved[2], source, generation)
        }

        private suspend fun sourceRows(live: String, stage: String, kind: String, moved: Int, source: String, generation: Long) {
            val columns = columnsOf(sql, live) - "mergedId"
            val set = columns - "id" - "seq"
            val tracks = kind == RENAME_SONG
            val wantsCounts = tracks && (wanted.getValue(RENAME_ALBUM) + wanted.getValue(RENAME_ARTIST)).any { it.startsWith("$source:") }
            walk(listOf(stage, live), "id", "$source:", "$source;") { r ->
                // New rows: the copy's rows its source lacks.
                val newCols = if (tracks) ", g.rating, COALESCE(n.mergedId, g.id), ${played("g")}" else ""
                val newJoin = if (tracks) " LEFT JOIN merged_id_next n ON n.sourceRowId = g.id" else ""
                rows(
                    "SELECT g.id$newCols FROM `$stage` g$newJoin WHERE ${r.where("g.id")} AND +g.sourceId = ?3 " +
                        "AND NOT EXISTS (SELECT 1 FROM `$live` t WHERE t.id = g.id)",
                    r.args(source),
                ) { c -> listOf<Any?>(c.getString(0)) + if (tracks) listOf(c.int(1), c.getString(2), c.getLong(3) != 0L) else emptyList() }
                    .forEach { row ->
                        val id = row[0] as String
                        if (!tracks) return@forEach found(live, FOUND_NEW, id, costs.applyFound(costs.whole(live)))
                        val songId = row[2] as String
                        found(live, FOUND_NEW, id, costs.applyFound(costs.whole(live)), newId = songId, new = row[1] as Int?)
                        touch(song(songId))
                        if (row[3] == true) totals(songId)
                    }
                // Changed rows, column by column, byte for byte.
                val oldCols = if (tracks) ", l.rating, g.rating, l.mergedId, ${played("l")}, ${played("g")}" else ""
                val first = if (tracks) 6 else 1
                rows(
                    "SELECT l.id$oldCols, ${differs(set, "l") { "g.`$it`" }} FROM `$stage` g CROSS JOIN `$live` l ON l.id = g.id " +
                        "WHERE ${r.where("g.id")} AND +l.sourceId = ?3 AND NOT (${same(set, "l") { "g.`$it`" }})",
                    r.args(source),
                ) { c ->
                    val changed = set.filterIndexed { i, _ -> c.getLong(first + i) != 0L }
                    listOf(c.getString(0), changed) + if (tracks) listOf(c.int(1), c.int(2), c.getString(3), c.getLong(4) != 0L || c.getLong(5) != 0L) else emptyList()
                }.forEach { row ->
                    val id = row[0] as String
                    @Suppress("UNCHECKED_CAST") val changed = row[1] as List<String>
                    val cost = costs.applyFound(costs.changed(live, changed))
                    if (!tracks) return@forEach found(live, FOUND_CHANGED, id, cost)
                    val songId = row[4] as String
                    found(live, FOUND_CHANGED, id, cost, oldId = songId, seen = row[2] as Int?, new = row[3] as Int?)
                    if (row[5] == true && changed.any { it in PLAY_COLUMNS }) totals(songId)
                }
                // Rows placed elsewhere, written only when the copy placed
                // rows of this table other than where they were.
                if (moved > 0) {
                    val songCol = if (tracks) ", l.mergedId" else ""
                    rows(
                        "SELECT l.id$songCol FROM `$stage` g CROSS JOIN `$live` l ON l.id = g.id " +
                            "WHERE ${r.where("g.id")} AND +l.sourceId = ?3 AND g.seq IS NOT l.seq",
                        r.args(source),
                    ) { c -> c.getString(0) to if (tracks) c.getString(1) else null }
                        .forEach { (id, songId) -> found(live, FOUND_PLACED, id, costs.applyFound(costs.changed(live, listOf("seq"))), oldId = songId) }
                }
                // Gone rows: the source's that the copy lacks, but for rows
                // taken in while the copy was built.
                val goneCols = if (tracks) ", l.mergedId, ${played("l")}" else ""
                rows(
                    "SELECT l.id$goneCols FROM `$live` l WHERE ${r.where("l.id")} AND +l.sourceId = ?3 " +
                        "AND NOT EXISTS (SELECT 1 FROM `$stage` g WHERE g.id = l.id) " +
                        "AND NOT EXISTS (SELECT 1 FROM take_in_log k WHERE k.generation = ?4 AND k.kind = '$kind' AND k.id = l.id)",
                    r.args(source, generation),
                ) { c -> Triple(c.getString(0), if (tracks) c.getString(1) else null, tracks && c.getLong(2) != 0L) }
                    .forEach { (id, songId, played) ->
                        found(live, FOUND_GONE, id, costs.applyFound(costs.whole(live)), oldId = songId)
                        if (!songId.isNullOrEmpty()) touch(song(songId))
                        if (played && songId != null) totals(songId)
                    }
                // The copy's songs of an album or artist a find wants counted.
                if (wantsCounts) {
                    rows(
                        "SELECT g.albumId, g.artistId, c.albumId, c.artistId FROM stage_track g CROSS JOIN merged_id_next n ON n.sourceRowId = g.id " +
                            "CROSS JOIN merge_candidate c ON c.id = n.mergedId WHERE ${r.where("g.id")} AND +g.sourceId = ?3",
                        r.args(source),
                    ) { c -> listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) }
                        .forEach { (album, artist, libraryAlbum, libraryArtist) ->
                            count(RENAME_ALBUM, album, libraryAlbum)
                            count(RENAME_ARTIST, artist, libraryArtist)
                        }
                }
            }
        }

        private fun count(kind: String, sourceId: String, libraryId: String) {
            if (sourceId !in wanted.getValue(kind)) return
            val counts = stagedCounts.getValue(kind).getOrPut(sourceId) { HashMap() }
            counts[libraryId] = (counts[libraryId] ?: 0) + 1
        }

        // The library's songs against the merge's.
        private suspend fun librarySongs() {
            val columns = columnsOf(sql, "track") - "id"
            val others = columns - "rating"
            val value = { col: String -> if (col == "year") "COALESCE(c.originalYear, c.year)" else "c.`$col`" }
            walk(listOf("merge_candidate", "track"), "id", "", null) { r ->
                val ops = HashMap<SaveNodeKey, Int?>()
                fun opAt(kind: String, id: String) = ops.getOrPut(SaveNodeKey(kind, id)) { opOf(kind, id) }
                rows(
                    "SELECT c.id, $SONG_RATING, c.albumId, c.artistId FROM merge_candidate c WHERE ${r.where("c.id")} " +
                        "AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = c.id)",
                    r.args(),
                ) { c -> listOf(c.getString(0), c.getInt(1), c.getString(2), c.getString(3)) }
                    .forEach { (id, rating, albumId, artistId) ->
                        id as String
                        found("track", FOUND_NEW, id, costs.applyFound(costs.whole("track")), new = rating as Int)
                        if (opAt(RENAME_ALBUM, albumId as String) == FOUND_NEW) edge(EDGE_ALBUM, song(id), SaveNodeKey(RENAME_ALBUM, albumId))
                        if (opAt(RENAME_ARTIST, artistId as String) == FOUND_NEW) edge(EDGE_ARTIST, song(id), SaveNodeKey(RENAME_ARTIST, artistId))
                    }
                rows(
                    "SELECT t.id, t.rating, t.albumId, t.artistId, EXISTS (SELECT 1 FROM play_total p WHERE p.trackId = t.id) FROM track t " +
                        "WHERE ${r.where("t.id")} AND NOT EXISTS (SELECT 1 FROM merge_candidate c WHERE c.id = t.id)",
                    r.args(),
                ) { c -> listOf(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4) != 0L) }
                    .forEach { (id, rating, albumId, artistId, total) ->
                        id as String
                        found("track", FOUND_GONE, id, costs.applyFound(costs.whole("track")), seen = rating as Int)
                        if (opAt(RENAME_ALBUM, albumId as String) == FOUND_GONE) edge(EDGE_ALBUM, song(id), SaveNodeKey(RENAME_ALBUM, albumId))
                        if (opAt(RENAME_ARTIST, artistId as String) == FOUND_GONE) edge(EDGE_ARTIST, song(id), SaveNodeKey(RENAME_ARTIST, artistId))
                        if (total == true) totals(id)
                    }
                rows(
                    "SELECT t.id, t.rating, $SONG_RATING, t.albumId, c.albumId, t.artistId, c.artistId, " +
                        "EXISTS (SELECT 1 FROM play_total p WHERE p.trackId = t.id), ${differs(others, "t", value)} " +
                        "FROM merge_candidate c CROSS JOIN track t ON t.id = c.id " +
                        "WHERE ${r.where("c.id")} AND NOT (${same(others, "t", value)} AND $SONG_RATING IS t.rating)",
                    r.args(),
                ) { c ->
                    SongChange(
                        c.getString(0), c.getInt(1), c.getInt(2), c.getString(3) to c.getString(4), c.getString(5) to c.getString(6),
                        c.getLong(7) != 0L, others.filterIndexed { i, _ -> c.getLong(8 + i) != 0L },
                    )
                }.forEach { s ->
                    val rated = s.newRating != s.seenRating
                    val written = if (rated) s.changed + "rating" else s.changed
                    found("track", if (s.changed.isEmpty()) FOUND_RATED else FOUND_CHANGED, s.id, costs.applyFound(costs.changed("track", written)), seen = s.seenRating, new = s.newRating)
                    for ((kind, ids) in listOf(RENAME_ALBUM to s.albums, RENAME_ARTIST to s.artists)) {
                        if (ids.first == ids.second) continue
                        val why = if (kind == RENAME_ALBUM) EDGE_ALBUM else EDGE_ARTIST
                        if (opAt(kind, ids.first) == FOUND_GONE) edge(why, song(s.id), SaveNodeKey(kind, ids.first))
                        if (opAt(kind, ids.second) == FOUND_NEW) edge(why, song(s.id), SaveNodeKey(kind, ids.second))
                    }
                    if (s.total && s.changed.any { it in TOTAL_KEY_COLUMNS }) totals(s.id)
                }
            }
        }

        // Each source row of a source in the library whose library song
        // changes, as the swap's merged ids statement writes them: of a
        // claimed copy's source, the copy's rows and the source's own rows
        // taken in while it was built that the copy lacks.
        private suspend fun mergedIds(claim: SaveClaim) {
            for (source in claim.sources) {
                val generation = staged[source]
                val tables = if (generation != null) listOf("stage_track", "source_track") else listOf("source_track")
                walk(tables, "id", "$source:", "$source;") { r ->
                    val queries = if (generation != null) {
                        listOf(
                            "SELECT g.id, COALESCE(l.mergedId, ''), COALESCE(n.mergedId, g.id), ${played("g")} OR ${played("l")} " +
                                "FROM stage_track g LEFT JOIN source_track l ON l.id = g.id LEFT JOIN merged_id_next n ON n.sourceRowId = g.id " +
                                "WHERE ${r.where("g.id")} AND +g.sourceId = ?3 AND ?4 = ?4 AND COALESCE(l.mergedId, '') IS NOT COALESCE(n.mergedId, g.id)",
                            "SELECT l.id, l.mergedId, COALESCE(n.mergedId, l.id), ${played("l")} " +
                                "FROM source_track l LEFT JOIN merged_id_next n ON n.sourceRowId = l.id " +
                                "WHERE ${r.where("l.id")} AND +l.sourceId = ?3 AND NOT EXISTS (SELECT 1 FROM stage_track g WHERE g.id = l.id) " +
                                "AND EXISTS (SELECT 1 FROM take_in_log k WHERE k.generation = ?4 AND k.kind = 'track' AND k.id = l.id) " +
                                "AND l.mergedId IS NOT COALESCE(n.mergedId, l.id)",
                        )
                    } else {
                        listOf(
                            "SELECT s.id, s.mergedId, COALESCE(n.mergedId, s.id), ${played("s")} " +
                                "FROM source_track s LEFT JOIN merged_id_next n ON n.sourceRowId = s.id " +
                                "WHERE ${r.where("s.id")} AND +s.sourceId = ?3 AND s.mergedId IS NOT COALESCE(n.mergedId, s.id)",
                        )
                    }
                    val args = if (generation != null) r.args(source, generation) else r.args(source)
                    queries.flatMap { query -> rows(query, args) { c -> listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3) != 0L) } }
                        .forEach { (id, old, new, played) ->
                            id as String
                            old as String
                            new as String
                            found(FOUND_MERGED_ID, FOUND_CHANGED, id, costs.applyFound(costs.changed("source_track", listOf("mergedId"))), oldId = old, newId = new)
                            if (old.isEmpty()) touch(song(new)) else edge(EDGE_FOLLOW, song(old), song(new))
                            if (played == true) {
                                totals(old)
                                totals(new)
                            }
                        }
                }
            }
        }

        // Copies of sources out of the library are no library song's. Only
        // the rows still merged are read: each such source's folders in the
        // order of its folder index (sourceId, storageVolume, folder,
        // mergedId, id), the next one found by seek (a server's rows have
        // neither, so it is one folder), and in each folder its merged rows
        // by seek past mergedId '', a page at a time. A kept server whose
        // rows are no song's already costs a seek per folder.
        private suspend fun unmerges(claim: SaveClaim) {
            suspend fun <T> read(block: () -> T): T = withContext(WriteLabels.of(SAVE_UNMERGES)) { block() }
            val first = "SELECT storageVolume, folder FROM source_track WHERE sourceId = ?1 ORDER BY storageVolume, folder LIMIT 1"
            val nextFolder = "SELECT folder FROM source_track WHERE sourceId = ?1 AND storageVolume IS ?2 AND folder > ?3 ORDER BY folder LIMIT 1"
            val firstNamedFolder = "SELECT folder FROM source_track WHERE sourceId = ?1 AND storageVolume IS ?2 AND folder >= '' ORDER BY folder LIMIT 1"
            val nextVolume = "SELECT storageVolume, folder FROM source_track WHERE sourceId = ?1 AND storageVolume > ?2 ORDER BY storageVolume, folder LIMIT 1"
            val firstNamedVolume = "SELECT storageVolume, folder FROM source_track WHERE sourceId = ?1 AND storageVolume >= '' ORDER BY storageVolume, folder LIMIT 1"
            val inFolder = "SELECT s.id, s.mergedId, ${played("s")} FROM source_track s WHERE s.sourceId = ?1 AND s.storageVolume IS ?2 AND s.folder IS ?3"
            val firstMerged = "$inFolder AND s.mergedId > '' ORDER BY s.mergedId, s.id LIMIT ?4"
            val nextMerged = "$inFolder AND s.mergedId >= ?4 AND (s.mergedId > ?4 OR s.id > ?5) ORDER BY s.mergedId, s.id LIMIT ?6"
            fun pair(query: String, vararg args: Any?): Pair<String?, String?>? =
                sql.query(query, args).use { c -> if (c.moveToFirst()) c.str(0) to c.str(1) else null }
            var after = ""
            while (true) {
                val source = read { text("SELECT sourceId FROM source_track WHERE sourceId > ?1 ORDER BY sourceId LIMIT 1", after) } ?: break
                after = source
                if (source in claim.sources) continue
                var folder = read { pair(first, source) }
                while (folder != null) {
                    val (volume, name) = folder
                    var last: Pair<String, String>? = null
                    while (true) {
                        val rows = read {
                            val args = if (last == null) arrayOf<Any?>(source, volume, name, readPage) else arrayOf<Any?>(source, volume, name, last!!.first, last!!.second, readPage)
                            rows(if (last == null) firstMerged else nextMerged, args) { c -> Triple(c.getString(0), c.getString(1), c.getLong(2) != 0L) }
                        }
                        rows.forEach { (id, old, played) ->
                            found(FOUND_UNMERGE, FOUND_CHANGED, id, costs.applyFound(costs.changed("source_track", listOf("mergedId"))), oldId = old)
                            touch(song(old))
                            if (played) totals(old)
                        }
                        if (rows.size < readPage) break
                        last = rows.last().second to rows.last().first
                    }
                    folder = read {
                        val sameVolume = if (name == null) text(firstNamedFolder, source, volume) else text(nextFolder, source, volume, name)
                        when {
                            sameVolume != null -> volume to sameVolume
                            volume == null -> pair(firstNamedVolume, source)
                            else -> pair(nextVolume, source, volume)
                        }
                    }
                }
            }
        }

        // Every song, album and artist that goes gets a rename to where it
        // goes, if it goes anywhere: the song its source row became, else
        // the lowest song filed under its own relink key; the album or
        // artist most of its copies' songs are in, else the first filed
        // under its own search key.
        private suspend fun renames() {
            gone("track") { id ->
                val key = text("SELECT relinkKey FROM track WHERE id = ?1", id).orEmpty()
                val follow = followOf(id)
                when {
                    follow != null -> rename(RENAME_SONG, id, follow, byKey = false, key = "")
                    key.isNotEmpty() -> keyTarget(key)?.let { rename(RENAME_SONG, id, it, byKey = true, key = key) }
                }
                // A song gone with the same key as new songs moves with them.
                if (key.isNotEmpty()) {
                    var past = ""
                    while (true) {
                        val news = rows(
                            "SELECT c.id FROM merge_candidate c WHERE c.relinkKey = ?1 AND c.id > ?2 " +
                                "AND NOT EXISTS (SELECT 1 FROM track t WHERE t.id = c.id) ORDER BY c.id LIMIT ?3",
                            arrayOf<Any?>(key, past, readPage),
                        ) { c -> c.getString(0) }
                        news.forEach { edge(EDGE_KEY, song(id), song(it)) }
                        if (news.size < readPage) break
                        past = news.last()
                    }
                }
            }
            for (kind in listOf(RENAME_ALBUM, RENAME_ARTIST)) {
                gone(liveOf(kind)) { id ->
                    val merged = mergedInto(kind, id)?.takeIf { isFinal(kind, it) }
                    if (merged != null) return@gone rename(kind, id, merged, byKey = false, key = "")
                    val key = text("SELECT searchKey FROM `${liveOf(kind)}` WHERE id = ?1", id).orEmpty()
                    if (key.isNotEmpty()) keyIds(kind, key).minOrNull()?.let { rename(kind, id, it, byKey = true, key = key) }
                }
            }
        }

        private suspend fun gone(tbl: String, each: suspend (String) -> Unit) {
            var after = ""
            while (true) {
                val ids = rows(
                    "SELECT id FROM save_found WHERE groupId IS NULL AND tbl = ?1 AND op = ?2 AND id > ?3 ORDER BY id LIMIT ?4",
                    arrayOf<Any?>(tbl, FOUND_GONE, after, readPage),
                ) { c -> c.getString(0) }
                ids.forEach { each(it) }
                if (ids.size < readPage) return
                after = ids.last()
            }
        }

        private suspend fun rename(kind: String, old: String, new: String, byKey: Boolean, key: String) =
            ref(REF_RENAME, kind, old, if (byKey) 1 else 0, 0, old, key, new)

        // The song a source row is a copy of once the save is in, if that
        // source row is in the library then.
        private fun followOf(id: String): String? = if (claimed) {
            text("SELECT mergedId FROM merged_id_next WHERE sourceRowId = ?1", id)
        } else {
            text("SELECT s.mergedId FROM source_track s WHERE s.id = ?1 AND EXISTS (SELECT 1 FROM track t WHERE t.id = s.mergedId)", id)
        }

        // The lowest song filed under a relink key once the save is in.
        private fun keyTarget(key: String): String? = text("SELECT id FROM `$songs` WHERE relinkKey = ?1 ORDER BY id LIMIT 1", key)

        // Every album or artist filed under a search key once the save is in.
        private fun keyIds(kind: String, key: String): List<String> =
            rows("SELECT id FROM `${finalOf(kind)}` WHERE searchKey = ?1", arrayOf(key)) { c -> c.getString(0) }

        // The library album (or artist) most of a source album's (or
        // artist's) copies' songs are in once the save is in, the first by
        // id on a tie; none for one with no copy of a song.
        private fun mergedInto(kind: String, id: String): String? = mergedCache.getOrPut(SaveNodeKey(kind, id)) {
            val counts = if (stagedSourceOf(id) != null) {
                stagedCounts.getValue(kind)[id].orEmpty()
            } else if (!claimed || librarySourceOf(id) != null) {
                countsOf(kind, id)
            } else {
                emptyMap()
            }
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
        }

        private fun countsOf(kind: String, id: String): Map<String, Int> {
            val column = if (kind == RENAME_ALBUM) "albumId" else "artistId"
            val query = if (claimed) {
                "SELECT c.$column, st.rowid FROM source_track st CROSS JOIN merged_id_next n ON n.sourceRowId = st.id " +
                    "CROSS JOIN merge_candidate c ON c.id = n.mergedId WHERE st.$column = ?1 AND st.rowid > ?2 ORDER BY st.rowid LIMIT ?3"
            } else {
                "SELECT t.$column, st.rowid FROM source_track st CROSS JOIN track t ON t.id = st.mergedId " +
                    "WHERE st.$column = ?1 AND st.rowid > ?2 ORDER BY st.rowid LIMIT ?3"
            }
            val counts = HashMap<String, Int>()
            var after = Long.MIN_VALUE
            while (true) {
                val copies = rows(query, arrayOf<Any?>(id, after, readPage)) { c -> c.getString(0) to c.getLong(1) }
                copies.forEach { (library, _) -> counts[library] = (counts[library] ?: 0) + 1 }
                if (copies.size < readPage) return counts
                after = copies.last().second
            }
        }

        // Rows that follow library ids

        // Each of the target's ids the library will not have, a page of its
        // ids at a time ("" among them); for a pin, its ids of one kind.
        // Where the table's keys follow what they name (RenameTarget.keyFollows),
        // the same read finds each song, album or artist the library keeps
        // that has a row under a key neither it nor any of its copies is
        // filed under then; those rows take its key (rekeyed).
        private suspend fun danglingIds(t: RenameTarget, kind: String, rekeys: Boolean = false, each: suspend (String) -> Unit) {
            val col = "`${t.column}`"
            val pin = if (t.kind == null) " AND `kind` = ?3" else ""
            val follow = rekeys && t.keyFollows
            val key = "`${libraryKeyColumn(kind)}`"
            // A claimed save's artists have no read of their copies by key
            // here (artistCopyKeys reads them); each differing row is weighed
            // there.
            val copies = carried(kind, "d.v", "o.relinkKey")?.let { " AND NOT $it" }.orEmpty()
            val stale = if (follow) {
                ", EXISTS (SELECT 1 FROM `${finalOf(kind)}` c CROSS JOIN `${t.table}` o ON o.$col = c.id WHERE c.id = d.v AND c.$key != ''" +
                    "${pin.replace("`kind`", "o.`kind`")} AND o.relinkKey IS NOT c.$key$copies)"
            } else {
                ""
            }
            var after: String? = null
            while (true) {
                val from = if (after == null) ">=" else ">"
                val args = if (t.kind == null) arrayOf<Any?>(after.orEmpty(), readPage, kind) else arrayOf<Any?>(after.orEmpty(), readPage)
                val ids = rows(
                    "SELECT d.v, EXISTS (SELECT 1 FROM `${finalOf(kind)}` c WHERE c.id = d.v)$stale " +
                        "FROM (SELECT DISTINCT $col AS v FROM `${t.table}` WHERE $col $from ?1$pin ORDER BY $col LIMIT ?2) d",
                    args,
                ) { c -> Triple(c.getString(0), c.getLong(1) != 0L, follow && c.getLong(2) != 0L) }
                ids.forEach { (id, present, keys) ->
                    when {
                        !present -> each(id)
                        keys -> heldBy(t, id, kind, emptyList()) { row -> rekeyed(t, kind, id, row) }
                    }
                }
                if (ids.size < readPage) return
                after = ids.last().first
            }
        }

        // Whether one of the copies of the song, album or artist `id` is filed
        // under `key` once the save is in (each an SQL value): a claimed
        // copy's row as its stage holds it, any other source's as it is. A
        // song's copies are its source rows; an album's (an artist's) are the
        // source albums (artists) of its songs' copies. Null for a claimed
        // save's artist, whose songs have no index by artist to read them by
        // (artistCopyKeys reads them a page of songs at a time).
        private fun carried(kind: String, id: String, key: String): String? {
            if (kind == RENAME_SONG) {
                return if (claimed) {
                    "EXISTS (SELECT 1 FROM merged_id_next n WHERE n.mergedId = $id AND COALESCE(" +
                        "(SELECT g.relinkKey FROM stage_track g WHERE g.id = n.sourceRowId " +
                        "AND EXISTS (SELECT 1 FROM source_meta m WHERE m.sourceId = g.sourceId AND m.stage = '$STAGE_CLAIMED')), " +
                        "(SELECT s.relinkKey FROM source_track s WHERE s.id = n.sourceRowId)) = $key)"
                } else {
                    "EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = $id AND s.relinkKey = $key)"
                }
            }
            val copy = when {
                kind == RENAME_ALBUM && claimed -> ALBUM_COPY_KEY_NEXT_SQL
                kind == RENAME_ALBUM -> ALBUM_COPY_KEY_SQL
                claimed -> return null
                else -> ARTIST_COPY_KEY_SQL
            }
            return "EXISTS (${copy.replace("?1", id).replace("?2", key)})"
        }

        // Rows of a claimed save's favorite or pinned artists under a key
        // their artist is not filed under, by the artist they name once the
        // save is in: weighed against the artist's copies once the walk of
        // the references is done (artistRekeys).
        private val artistRows = LinkedHashMap<String, MutableList<Pair<RenameTarget, Held>>>()

        // A row of a table whose keys follow what they name, on (or moving
        // to) the song, album or artist `id` once the save is in: when that
        // is filed under a key and neither it nor any of its copies is filed
        // under the row's, a reference that gives the row its key, applied
        // with its own set after any move of the row.
        private suspend fun rekeyed(t: RenameTarget, kind: String, id: String, row: Held) {
            if (!t.keyFollows) return
            val key = "`${libraryKeyColumn(kind)}`"
            val copies = carried(kind, "?1", "?2")
            val stale = long(
                "SELECT EXISTS (SELECT 1 FROM `${finalOf(kind)}` c WHERE c.id = ?1 AND c.$key != '' AND c.$key IS NOT ?2${copies?.let { " AND NOT $it" }.orEmpty()})",
                id, row.relinkKey,
            ) == 1L
            if (!stale) return
            if (copies == null) {
                artistRows.getOrPut(id) { ArrayList() } += t to row
                return
            }
            rekeyRef(t, kind, id, row)
        }

        private suspend fun rekeyRef(t: RenameTarget, kind: String, id: String, row: Held) {
            // A table keyed by the id it holds names the row by the id it moves to.
            val keyText = if (t.keyText == t.column) id else row.keyText
            writer.add(costs.refIn()) { it.run(REF_IN_SQL, t.table, kind, keyText, row.keyNum, row.keyNum2, id, row.relinkKey, id) }
            touch(SaveNodeKey(kind, id))
        }

        // The rows artistRows holds, against the keys their artists' copies
        // are filed under once the save is in: the merge's songs read a page
        // at a time by id, and for each song of one of those artists its
        // copies' artists' keys; a row under none of them takes its artist's
        // key, after every move the walk of the references planned.
        private suspend fun artistRekeys() {
            if (artistRows.isEmpty()) return
            val keys = HashMap<String, HashSet<String>>()
            var after = ""
            while (true) {
                val page = rows("SELECT id, artistId FROM merge_candidate WHERE id > ?1 ORDER BY id LIMIT ?2", arrayOf<Any?>(after, readPage)) { c ->
                    c.getString(0) to c.getString(1)
                }
                for ((song, artist) in page) {
                    if (artist !in artistRows) continue
                    rows(ARTIST_COPY_KEYS_NEXT_SQL, arrayOf<Any?>(song)) { c -> c.str(0) }.forEach { key -> if (key != null) keys.getOrPut(artist) { HashSet() } += key }
                }
                if (page.size < readPage) break
                after = page.last().first
            }
            for ((artist, held) in artistRows) {
                for ((t, row) in held) if (row.relinkKey !in keys[artist].orEmpty()) rekeyRef(t, RENAME_ARTIST, artist, row)
            }
            artistRows.clear()
        }

        // One row of a target table, by what its reference needs.
        private inner class Held(val rowid: Long, val keyText: String, val keyNum: Long, val keyNum2: Long, val relinkKey: String, val values: Map<String, Any?>)

        // The rows holding an id (of a pin's `kind`, for a pin), a page at a
        // time in the order of the table's index that leads with them.
        private suspend fun heldBy(t: RenameTarget, id: String, kind: String, extra: List<String>, each: suspend (Held) -> Unit) {
            val lead = if (t.kind == null) listOf("kind", t.column) else listOf(t.column)
            val index = costs.indexesOf(t.table).filter { it.take(lead.size) == lead }.minByOrNull { it.size }
                ?: error("no index leads with ${t.table}.${t.column}")
            val order = index.drop(lead.size) + "rowid"
            val wanted = (listOf("rowid") + listOfNotNull(t.keyText) + t.keyNums + (if (t.keyed) listOf("relinkKey") else emptyList()) +
                listOfNotNull(t.only?.first) + extra + order).distinct()
            val select = wanted.joinToString { if (it == "rowid") "rowid" else "`$it`" }
            val ordered = order.joinToString { if (it == "rowid") "rowid" else "`$it`" }
            val pin = if (t.kind == null) " AND `kind` = ?3" else ""
            val from = if (t.kind == null) 4 else 3
            var last: List<Any?>? = null
            while (true) {
                val past = last?.let {
                    if (order.size == 1) " AND rowid > ?$from" else " AND ($ordered) > (${order.indices.joinToString { "?${it + from}" }})"
                }.orEmpty()
                val args = (listOf<Any?>(id, readPage) + (if (t.kind == null) listOf<Any?>(kind) else emptyList()) + last.orEmpty()).toTypedArray()
                val held = rows("SELECT $select FROM `${t.table}` WHERE `${t.column}` = ?1$pin$past ORDER BY $ordered LIMIT ?2", args) { c ->
                    val values = wanted.withIndex().associate { (i, name) ->
                        name to when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            else -> c.getString(i)
                        }
                    }
                    Held(
                        rowid = values["rowid"] as Long,
                        keyText = t.keyText?.let { values[it]?.toString() }.orEmpty(),
                        keyNum = t.keyNums.getOrNull(0)?.let { values[it] as Long } ?: 0,
                        keyNum2 = t.keyNums.getOrNull(1)?.let { values[it] as Long } ?: 0,
                        relinkKey = if (t.keyed) values["relinkKey"]?.toString().orEmpty() else "",
                        values = values,
                    )
                }
                held.forEach { each(it) }
                if (held.size < readPage) return
                last = order.map { held.last().values[it] }
            }
        }

        private suspend fun references() {
            for (t in SAVE_RENAME_TARGETS) {
                when (t.table) {
                    "liked_track", "track_rating" -> takenSongs(t)
                    "liked_album", "liked_artist" -> favourites(t, t.kind!!)
                    "pinned_item" -> {
                        favourites(t, RENAME_ALBUM)
                        favourites(t, RENAME_ARTIST)
                    }
                    // Queued songs and adopted finds follow only the merge's
                    // own renames, which a skipped merge has none of.
                    "queue_entry" -> if (claimed) queued(t)
                    "online_song" -> if (claimed) plain(t)
                    else -> plain(t)
                }
            }
        }

        // A table no guard holds back: each row follows its id's song, or
        // failing that (where it keeps a relink key) the lowest song filed
        // under its own key.
        private suspend fun plain(t: RenameTarget) {
            val kind = t.kind!!
            val keys = HashMap<String, String?>()
            danglingIds(t, kind, rekeys = true) { id ->
                val follow = followOf(id)
                heldBy(t, id, kind, emptyList()) { row ->
                    val only = t.only
                    if (only != null && row.values[only.first] != only.second.toLong()) return@heldBy
                    val byKey = if (t.keyed && !t.followOnly && row.relinkKey.isNotEmpty()) keys.getOrPut(row.relinkKey) { keyTarget(row.relinkKey) } else null
                    val target = follow ?: byKey ?: return@heldBy
                    ref(t.table, kind, row.keyText, row.keyNum, row.keyNum2, id, row.relinkKey, target)
                    rekeyed(t, kind, target, row)
                    if (t.table == "play_event") totals(target)
                }
                if (keys.size > readPage) keys.clear()
            }
        }

        // Queued songs follow the merge's renames one at a time, in the
        // order of their old ids; a row whose list already holds the song it
        // becomes, or takes it from a rename before it, stays.
        private suspend fun queued(t: RenameTarget) {
            val co = HashMap<String, List<String>>()
            danglingIds(t, RENAME_SONG) { id ->
                val target = followOf(id)?.takeIf { it != id } ?: return@danglingIds
                val renamed = co.getOrPut(target) {
                    rows("SELECT sourceRowId FROM merged_id_next WHERE mergedId = ?1", arrayOf(target)) { c -> c.getString(0) }
                }.filter { it != target && it != id && SqlOrder.text(it, id) < 0 && !isFinal(RENAME_SONG, it) }
                heldBy(t, id, RENAME_SONG, listOf("listId")) { row ->
                    val queueId = row.keyNum
                    val listId = row.values["listId"] as Long
                    if (inList(target, queueId, listId)) return@heldBy
                    if (renamed.any { inList(it, queueId, listId) }) return@heldBy
                    ref(t.table, RENAME_SONG, row.keyText, row.keyNum, row.keyNum2, id, row.relinkKey, target)
                }
                if (co.size > readPage) co.clear()
            }
        }

        private fun inList(song: String, queueId: Long, listId: Long): Boolean =
            long("SELECT EXISTS (SELECT 1 FROM queue_entry WHERE trackId = ?1 AND +queueId = ?2 AND +listId = ?3)", song, queueId, listId) == 1L

        // Likes and ratings of songs, one row a song: those that vanished
        // follow their song first, and then those left go to the lowest song
        // with their relink key, each time in the order the rows were
        // written (the relinks' own walk of the table); a row whose song is
        // held already stays.
        private suspend fun takenSongs(t: RenameTarget) {
            val rating = t.table == "track_rating"
            val dangling = ArrayList<Pair<String, Held>>()
            danglingIds(t, RENAME_SONG, rekeys = true) { id ->
                heldBy(t, id, RENAME_SONG, if (rating) listOf("rating") else emptyList()) { dangling += id to it }
            }
            if (dangling.isEmpty()) return
            val moved = HashSet<String>()
            val targets = HashMap<Long, String>()
            fun held(song: String) = song in moved || long("SELECT EXISTS (SELECT 1 FROM `${t.table}` WHERE trackId = ?1)", song) == 1L
            dangling.sortBy { it.second.rowid }
            for ((id, row) in dangling) {
                val follow = followOf(id) ?: continue
                if (held(follow)) continue
                moved += follow
                targets[row.rowid] = follow
            }
            for ((_, row) in dangling) {
                if (row.rowid in targets || row.relinkKey.isEmpty()) continue
                val target = keyTarget(row.relinkKey) ?: continue
                if (held(target)) continue
                moved += target
                targets[row.rowid] = target
            }
            for ((id, row) in dangling) {
                val target = targets[row.rowid] ?: continue
                ref(t.table, RENAME_SONG, row.keyText, row.keyNum, row.keyNum2, id, row.relinkKey, target)
                rekeyed(t, RENAME_SONG, target, row)
                if (rating) movedRatings += target to (row.values["rating"] as Long).toInt()
            }
        }

        // Favorite albums and artists and pins, a list at a time, as the
        // library's relinks of them move them: in the order of their ids,
        // each that vanished follows the album (or artist) most of its songs
        // went into, unless one of the list holds that already, or failing
        // that goes to the first not held of those filed under its key.
        private suspend fun favourites(t: RenameTarget, kind: String) {
            val vanished = ArrayList<Pair<String, Held>>()
            danglingIds(t, kind, rekeys = true) { id -> heldBy(t, id, kind, emptyList()) { vanished += id to it } }
            if (vanished.isEmpty()) return
            val pin = t.kind == null
            val moved = HashSet<String>()
            fun held(id: String) = id in moved || if (pin) {
                long("SELECT EXISTS (SELECT 1 FROM pinned_item WHERE kind = ?1 AND itemId = ?2)", kind, id) == 1L
            } else {
                long("SELECT EXISTS (SELECT 1 FROM `${t.table}` WHERE `${t.column}` = ?1)", id) == 1L
            }
            for ((id, row) in vanished.sortedBy { it.first }) {
                val followed = mergedInto(kind, id)?.takeIf { isFinal(kind, it) }
                val target = when {
                    followed != null -> followed.takeIf { !held(it) }
                    row.relinkKey.isEmpty() -> null
                    else -> keyIds(kind, row.relinkKey).sorted().firstOrNull { !held(it) }
                } ?: continue
                moved += target
                ref(t.table, kind, row.keyText, row.keyNum, row.keyNum2, id, row.relinkKey, target)
                rekeyed(t, kind, target, row)
            }
        }

        // Ratings

        // A song's rating as the save leaves it: the merge's (with the
        // phone's where no server rated it), the phone's rating whose row
        // moved onto it where it shows none, and a rating noted since the
        // claim over both.
        private suspend fun ratings() {
            val phone = LinkedHashMap<String, Int>()
            for ((song, rating) in movedRatings) {
                val shown = songRow(song)?.newRating ?: text("SELECT rating FROM track WHERE id = ?1", song)?.toInt() ?: 0
                if (shown == 0) phone[song] = rating
            }
            walk(listOf("track_rating"), "trackId", "", null) { r ->
                rows(
                    "SELECT r.trackId, r.rating FROM track_rating r CROSS JOIN track t ON t.id = r.trackId " +
                        "WHERE ${r.where("r.trackId")} AND t.rating = 0 AND EXISTS (SELECT 1 FROM `$songs` c WHERE c.id = r.trackId)",
                    r.args(),
                ) { c -> c.getString(0) to c.getInt(1) }.forEach { (song, rating) -> if (songRow(song) == null) phone[song] = rating }
            }
            val noted = LinkedHashMap<String, Int>()
            if (claimed) {
                var after = Long.MIN_VALUE
                while (true) {
                    val notes = rows(
                        "SELECT n.rowid, m.mergedId, n.rating FROM rating_note n LEFT JOIN merged_id_next m ON m.sourceRowId = n.sourceRowId " +
                            "WHERE n.rowid > ?1 ORDER BY n.rowid LIMIT ?2",
                        arrayOf<Any?>(after, readPage),
                    ) { c -> Triple(c.getLong(0), c.str(1), c.getInt(2)) }
                    notes.forEach { (_, song, rating) -> if (song != null) noted[song] = rating }
                    if (notes.size < readPage) break
                    after = notes.last().first
                }
            }
            for (song in (phone.keys + noted.keys).distinct()) {
                val row = songRow(song)
                val live = if (row == null) text("SELECT rating FROM track WHERE id = ?1", song)?.toInt() else null
                val made = row?.newRating ?: live ?: continue
                val shown = noted[song] ?: (if (made == 0) (phone[song] ?: 0) else made)
                if (row == null) {
                    if (shown != live) found("track", FOUND_RATED, song, costs.applyFound(costs.changed("track", listOf("rating"))), seen = live, new = shown)
                    continue
                }
                if (shown == row.newRating) continue
                if (row.op == FOUND_RATED && shown == row.seenRating) {
                    writer.add(costs.foundOut()) { it.run(FOUND_OUT_SQL, row.seq) }
                    continue
                }
                // The rating's own indexes join a row's cost once it writes one.
                val cost = if (row.newRating == row.seenRating && row.op != FOUND_NEW) row.cost + costs.changed("track", listOf("rating")) - 1 - TRACKER_PROBE else row.cost
                writer.add(costs.foundRated()) { it.run(FOUND_RATED_SQL, row.seq, row.op, shown, cost) }
            }
        }

        private inner class SongRow(val seq: Long, val op: Int, val seenRating: Int?, val newRating: Int?, val cost: Long)

        private fun songRow(id: String): SongRow? = sql.query(FOUND_SONG_SQL, arrayOf<Any?>(id)).use { c ->
            if (c.moveToFirst()) SongRow(c.getLong(0), c.getInt(1), c.int(2), c.int(3), c.getLong(4)) else null
        }

        // Rating taps whose server copy the save takes away: a claimed
        // copy's source keeps the copy's rows and its rows taken in while
        // the copy was built; any other source keeps its rows.
        private suspend fun taps() {
            walk(listOf("rating_tap"), "sourceRowId", "", null) { r ->
                rows(
                    "SELECT r.sourceRowId, s.mergedId, s.id IS NOT NULL, EXISTS (SELECT 1 FROM stage_track g WHERE g.id = r.sourceRowId) " +
                        "FROM rating_tap r LEFT JOIN source_track s ON s.id = r.sourceRowId WHERE ${r.where("r.sourceRowId")}",
                    r.args(),
                ) { c -> listOf(c.getString(0), c.str(1), c.getLong(2) != 0L, c.getLong(3) != 0L) }
                    .forEach { (id, songId, live, inStage) ->
                        id as String
                        val generation = stagedSourceOf(id)?.let { staged.getValue(it) }
                        val stays = when {
                            generation == null -> live == true
                            inStage == true -> true
                            else -> live == true && long("SELECT EXISTS (SELECT 1 FROM take_in_log WHERE generation = ?1 AND kind = 'track' AND id = ?2)", generation, id) == 1L
                        }
                        if (stays) return@forEach
                        found(FOUND_TAP, FOUND_GONE, id, costs.applyFound(costs.whole("rating_tap")), oldId = songId as String?)
                        if (!songId.isNullOrEmpty()) touch(song(songId))
                    }
            }
        }

        // Sets and groups

        // Every node points straight at its root.
        private suspend fun roots() {
            writer.what = "roots"
            var after = Long.MIN_VALUE
            while (true) {
                val nodes = rows("SELECT node, parent FROM save_node WHERE node > ?1 ORDER BY node LIMIT ?2", arrayOf<Any?>(after, readPage)) { c -> c.getLong(0) to c.getLong(1) }
                for ((node, parent) in nodes) {
                    if (node == parent) continue
                    writer.add(costs.pointAtRoot(depth)) { tx ->
                        val root = tx.sets.rootOfNode(node, parent)
                        if (root != parent) tx.sets.pointAt(node, root)
                    }
                }
                writer.flush()
                if (nodes.size < readPage) return
                after = nodes.last().first
            }
        }

        // Each found row and reference takes its id's set.
        private suspend fun setIds() {
            writer.what = "set ids"
            val found = (bound / costs.foundSet(1).total).coerceAtLeast(1)
            walkSeq("save_found", "seq", found) { after, end -> writer.add(costs.foundSet(found)) { it.run(FOUND_SET_SQL, after, end) } }
            writer.flush()
            val refs = (bound / costs.refSet(1).total).coerceAtLeast(1)
            walkSeq("save_ref", "seq", refs) { after, end -> writer.add(costs.refSet(refs)) { it.run(REF_SET_SQL, after, end) } }
            writer.flush()
        }

        // Each set's cost: what applying its found rows and references costs.
        private suspend fun setCosts() {
            writer.what = "set costs"
            // By table, and whether the reference only sets a row's key.
            val refCosts = SAVE_RENAME_TARGETS.associate { (it.table to false) to costs.applyRef(it.table, it.column, triggered = true) } +
                SAVE_RENAME_TARGETS.filter { it.keyFollows }.associate { (it.table to true) to costs.applyRekey(it.table) } +
                ((REF_RENAME to false) to costs.applyRename())
            var after = Long.MIN_VALUE
            while (true) {
                val nodes = rows("SELECT node, parent FROM save_node WHERE node > ?1 ORDER BY node LIMIT ?2", arrayOf<Any?>(after, readPage)) { c -> c.getLong(0) to c.getLong(1) }
                for ((node, parent) in nodes) {
                    if (node != parent) continue
                    val cost = (long("SELECT SUM(cost) FROM save_found WHERE setId = ?1", node) ?: 0) +
                        rows("SELECT tbl, target IS oldId, COUNT(*) FROM save_ref WHERE setId = ?1 GROUP BY tbl, target IS oldId", arrayOf(node)) { c ->
                            refCosts.getValue(c.getString(0) to (c.getLong(1) != 0L)) * c.getLong(2)
                        }.sum()
                    if (cost > 0) writer.add(costs.setIn()) { it.run(SET_IN_SQL, node, cost) }
                }
                if (nodes.size < readPage) break
                after = nodes.last().first
            }
            writer.flush()
        }

        // Sets in order, several to a group up to the bound, and a set over
        // it a run of its own; then the rows in no set, by their cost.
        private suspend fun plan(): SaveFound {
            writer.what = "groups"
            val packer = GroupPacker(bound - costs.groupOverhead())
            var after = Long.MIN_VALUE
            while (true) {
                val sets = rows("SELECT setId, cost FROM save_set WHERE setId > ?1 ORDER BY setId LIMIT ?2", arrayOf<Any?>(after, readPage)) { c -> c.getLong(0) to c.getLong(1) }
                for ((setId, cost) in sets) {
                    val group = packer.add(cost)
                    writer.add(costs.setGroup()) { it.run(SET_GROUP_SQL, setId, group) }
                }
                if (sets.size < readPage) break
                after = sets.last().first
            }
            writer.flush()
            val found = (bound / costs.foundGroup(1).total).coerceAtLeast(1)
            walkSeq("save_found", "seq", found) { from, end -> writer.add(costs.foundGroup(found)) { it.run(FOUND_GROUP_SQL, from, end) } }
            writer.flush()
            val refs = (bound / costs.refGroup(1).total).coerceAtLeast(1)
            walkSeq("save_ref", "seq", refs) { from, end -> writer.add(costs.refGroup(refs)) { it.run(REF_GROUP_SQL, from, end) } }
            writer.flush()
            // The rows in no set, a run of them in seq order a group.
            var group = 0L
            var first = 0L
            var last = 0L
            var rowsIn = 0L
            suspend fun close() {
                if (rowsIn == 0L) return
                val g = group
                val (lo, hi) = first - 1 to last
                writer.add(costs.foundGroup(rowsIn)) { it.run(LOOSE_GROUP_SQL, g, lo, hi) }
                rowsIn = 0
            }
            var past = Long.MIN_VALUE
            while (true) {
                val loose = rows("SELECT seq, cost FROM save_found WHERE setId IS NULL AND seq > ?1 ORDER BY seq LIMIT ?2", arrayOf<Any?>(past, readPage)) { c ->
                    c.getLong(0) to c.getLong(1)
                }
                for ((seq, cost) in loose) {
                    val g = packer.add(cost)
                    if (g != group) {
                        close()
                        group = g
                        first = seq
                    }
                    last = seq
                    rowsIn++
                }
                if (loose.size < readPage) break
                past = loose.last().first
            }
            close()
            writer.flush()
            val dao = db.saveDao()
            return SaveFound(dao.foundCount(), dao.refCount(), dao.setCount(), packer.last, packer.runs)
        }
    }

    private class SongChange(
        val id: String,
        val seenRating: Int,
        val newRating: Int,
        val albums: Pair<String, String>,
        val artists: Pair<String, String>,
        val total: Boolean,
        val changed: List<String>,
    )

    private companion object {
        // A song's rating as the swap writes it: the merge's, or where no
        // server rated it the phone's.
        const val SONG_RATING = "CASE WHEN c.rating = 0 THEN COALESCE((SELECT r.rating FROM track_rating r WHERE r.trackId = c.id), 0) ELSE c.rating END"

        // A source row's columns its song's play total reads.
        val PLAY_COLUMNS = setOf("playCount", "lastPlayedAt", "nativeId")

        // A song's columns its play total keeps.
        val TOTAL_KEY_COLUMNS = setOf("albumId", "artistId", "sortKey", "artistSortKey")

        // Whether a source row is a server copy with a play record, which
        // its song's play total counts.
        fun played(alias: String) = "($alias.sourceId LIKE 'server:%' AND ($alias.playCount > 0 OR $alias.lastPlayedAt IS NOT NULL))"

        // For each column, whether the value differs from the row `live`
        // holds, byte for byte; and whether every one is the same.
        fun differs(columns: List<String>, live: String, value: (String) -> String): String =
            columns.joinToString { "(${value(it)} IS NOT $live.`$it` COLLATE BINARY)" }

        fun same(columns: List<String>, live: String, value: (String) -> String): String =
            columns.joinToString(" AND ") { "${value(it)} IS $live.`$it` COLLATE BINARY" }
    }
}

// Whether a copy of album ?1 is filed under key ?2 once a claimed save is
// in: a song of the merge's in the album whose copy's source album (the
// claimed copy's as its stage holds it) has that search key.
internal const val ALBUM_COPY_KEY_NEXT_SQL =
    "SELECT 1 FROM merge_candidate c CROSS JOIN merged_id_next n ON n.mergedId = c.id WHERE c.albumId = ?1 AND COALESCE(" +
        "(SELECT a.searchKey FROM stage_track g CROSS JOIN stage_album a ON a.id = g.albumId WHERE g.id = n.sourceRowId " +
        "AND EXISTS (SELECT 1 FROM source_meta m WHERE m.sourceId = g.sourceId AND m.stage = '$STAGE_CLAIMED')), " +
        "(SELECT a.searchKey FROM source_track s CROSS JOIN source_album a ON a.id = s.albumId WHERE s.id = n.sourceRowId)) = ?2"

// The same of the library as it is (a skipped merge's), for an album and
// for an artist.
internal const val ALBUM_COPY_KEY_SQL =
    "SELECT 1 FROM track t CROSS JOIN source_track s ON s.mergedId = t.id CROSS JOIN source_album a ON a.id = s.albumId WHERE t.albumId = ?1 AND a.searchKey = ?2"
internal const val ARTIST_COPY_KEY_SQL =
    "SELECT 1 FROM track t CROSS JOIN source_track s ON s.mergedId = t.id CROSS JOIN source_artist a ON a.id = s.artistId WHERE t.artistId = ?1 AND a.searchKey = ?2"

// The search keys of the artists of song ?1's copies once a claimed save is
// in (the claimed copy's as its stage holds it).
internal const val ARTIST_COPY_KEYS_NEXT_SQL =
    "SELECT COALESCE(" +
        "(SELECT a.searchKey FROM stage_track g CROSS JOIN stage_artist a ON a.id = g.artistId WHERE g.id = n.sourceRowId " +
        "AND EXISTS (SELECT 1 FROM source_meta m WHERE m.sourceId = g.sourceId AND m.stage = '$STAGE_CLAIMED')), " +
        "(SELECT a.searchKey FROM source_track s CROSS JOIN source_artist a ON a.id = s.artistId WHERE s.id = n.sourceRowId)) " +
        "FROM merged_id_next n WHERE n.mergedId = ?1"

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
