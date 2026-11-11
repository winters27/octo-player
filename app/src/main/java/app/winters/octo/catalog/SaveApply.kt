package app.winters.octo.catalog

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.PhonePlays
import app.winters.octo.listening.SongPlays
import app.winters.octo.listening.totalOf
import kotlinx.coroutines.withContext
import java.io.Closeable

// The apply's label on its writes.
internal const val SAVE_APPLY = "save apply"

// The label on the reads of the find's walk of copies out of the library
// (SaveFind.unmerges), so a test can tell them apart.
internal const val SAVE_UNMERGES = "save find unmerges"

// A claim as the apply's last steps take it: the sources in the library in
// rank order, each claimed copy by its source and generation, and the
// server in use ("" for none).
internal class ApplyClaim(val sources: List<String>, val claimed: List<Pair<String, Long>>, val server: String)

// What the apply needs of the merge: a turn at the merge lock for each of
// its groups (and each page of a run).
internal interface ApplyTurns {
    // Runs `block` as one group: once no hold of the merge lock is inside,
    // in one Room transaction whose first act turns the swap stamp odd.
    // `first` for the apply's first group, which turns the apply's signals
    // on inside its transaction. `block` answers the songs it renamed by
    // follow (old id to new), published once the group has committed.
    suspend fun group(first: Boolean, block: suspend (SupportSQLiteDatabase) -> Map<String, String>)
}

// The apply of a save: what the find (SaveFind) left in save_found and
// save_ref, written a group at a time, each group one transaction within
// the bound, and a group over it (a run) a page at a time, each page within
// it. Every statement names its row by key and writes only while the row
// still differs, and then only the columns that do, so a rating written
// since the find stands (the rating rule of SwapSql) and a row the find saw
// that changed since is left alone.
//
// Inside a group or a page, in order: new artists and albums, new source
// rows, new songs each with its own copy pointed at it; the renames
// published; changed rows; references moved (each by its table's key, then
// any row written since the find on an old id), and the listener's rows on
// kept songs, albums and artists given the keys those are filed under;
// copies leaving
// their songs and gone source rows; gone songs, each with its own copy; gone albums and
// artists; gone rating taps; play totals. A song keeps its own copy until
// its row goes, and gains its own copy as its row comes, so between groups
// and pages every song has a copy (or a rename to one that has) and no row
// names a missing song, album or artist.
//
// A group that is no run reads its rows whole, once (the plan sized it),
// and deletes them by its group's key at its end. A run reads its rows one
// at a time, each read counted against its page, and deletes each as it is
// applied.
//
// After the groups (or one empty turn, for a claim with nothing to write),
// a claimed apply's last steps: the ratings noted since the claim shown,
// each claimed copy's bookkeeping, and the take-in log and rating notes
// cleared a page at a time; then the final transaction.
internal class SaveApply(
    private val db: CatalogDatabase,
    private val bound: Long,
    private val turns: ApplyTurns,
    private val hooks: () -> MergeHooks,
    // How many copies one song can have: one a source in the library.
    private val copies: Long,
    // The save's record as a group leaves it, `done` of `of` groups in, as
    // RECORD_RAISE_SQL's arguments, written with the group's swap count in
    // its transaction; null writes none.
    private val record: suspend (done: Long, of: Long) -> Array<Any?>? = { _, _ -> null },
    // Inside the transaction of the claimed copies' bookkeeping: the record
    // says its groups are in and its last steps begun.
    private val lastStepsBegun: suspend () -> Unit = {},
) {
    // The groups the plan being applied has.
    private var planned = 0L
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    private val costs = SaveCosts.of(sql, SAVE_COST_TABLES)

    // What a page of a run may cost besides what every group writes.
    private val capacity = bound - costs.groupOverhead()
    private val statements = SwapSql(
        source = columnsOf(sql, "source_track"),
        album = columnsOf(sql, "source_album"),
        artist = columnsOf(sql, "source_artist"),
        track = columnsOf(sql, "track"),
        libraryAlbum = columnsOf(sql, "album"),
        libraryArtist = columnsOf(sql, "artist"),
    )
    private val refs = SAVE_RENAME_TARGETS.associate { it.table to RefSql(it) }

    // The steps of a group, in order.
    private val steps: List<Step> = buildList {
        add(Step.Found(RENAME_ARTIST, FOUND_NEW))
        add(Step.Found(RENAME_ALBUM, FOUND_NEW))
        for (t in listOf("source_artist", "source_album", "source_track")) add(Step.Found(t, FOUND_NEW))
        add(Step.Found("track", FOUND_NEW))
        add(Step.Renames)
        for (t in listOf("source_track", "source_album", "source_artist")) {
            add(Step.Found(t, FOUND_CHANGED))
            add(Step.Found(t, FOUND_PLACED))
        }
        add(Step.Found("track", FOUND_CHANGED))
        add(Step.Found("track", FOUND_RATED))
        add(Step.Found(RENAME_ALBUM, FOUND_CHANGED))
        add(Step.Found(RENAME_ARTIST, FOUND_CHANGED))
        for (t in SAVE_RENAME_TARGETS) add(Step.Refs(t))
        add(Step.CatchUp)
        add(Step.Found(FOUND_MERGED_ID, FOUND_CHANGED))
        add(Step.Found(FOUND_UNMERGE, FOUND_CHANGED))
        for (t in listOf("source_track", "source_album", "source_artist")) add(Step.Found(t, FOUND_GONE))
        add(Step.Found("track", FOUND_GONE))
        add(Step.Found(RENAME_ALBUM, FOUND_GONE))
        add(Step.Found(RENAME_ARTIST, FOUND_GONE))
        add(Step.Found(FOUND_TAP, FOUND_GONE))
        add(Step.Found(FOUND_TOTALS, FOUND_CHANGED))
    }

    // Renames of an earlier save that this one does not clear: a new id
    // that one names stops following it.
    private var staleRenames = false

    // Group transactions (groups and run pages) committed so far.
    var turnsDone = 0L
        private set

    // Rows the apply has written, its triggers' writes left out.
    var rows = 0L
        private set

    // Applies what the find planned: every group, then for a claim its
    // last steps and the final transaction. `grouped` hears each group
    // done, with how many were planned. A resume (`resumed`) keeps the
    // renames in force, since its own find no longer sees the songs its
    // done groups renamed.
    suspend fun run(plan: SaveFound, claim: ApplyClaim?, resumed: Boolean = false, grouped: (done: Long, of: Long) -> Unit = { _, _ -> }) =
        withContext(WriteLabels.of(SAVE_APPLY)) {
            planned = plan.groups
            val renames = (1..plan.groups).any { long(HAS_RENAMES_SQL, it) == 1L }
            if (renames && !resumed) clearRenames() else staleRenames = long("SELECT EXISTS (SELECT 1 FROM save_rename)") == 1L
            for (g in 1..plan.groups) {
                group(g)
                grouped(g, plan.groups)
            }
            val left = sql.query(LEFT_SQL).use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)} ${c.getString(1)} ${c.getString(2)} group ${c.getString(3)}") } }
            check(left.isEmpty()) { "rows the save found are left after its groups: $left" }
            if (claim != null) {
                // A claim with nothing to write still takes its first turn
                // before its last steps mark its copies swapped in: the check
                // that they are still the ones claimed, the swap count and
                // the save's record.
                if (turnsDone == 0L) {
                    turns.group(first = true) { tx ->
                        raise(tx, 0, 0)
                        emptyMap()
                    }
                    turnsDone++
                }
                lastSteps(claim)
                finish(claim)
            } else if (turnsDone == 0L && !resumed) {
                // A skipped merge that found nothing still takes its turn, so
                // the swap stamp moves as it always did for one.
                turns.group(first = false) { emptyMap() }
            } else {
                // A skipped merge's save that wrote groups (or a resume of
                // one) is in: its record goes.
                db.withTransaction { db.workDao().endApplyRecord() }
            }
        }

    // The last steps of a claim whose groups were all in before a stop or
    // the app's end, after its copies' bookkeeping: what is left of the
    // take-in log and the rating notes cleared, and the final transaction.
    suspend fun lastStepsOnly(claim: ApplyClaim) = withContext(WriteLabels.of(SAVE_APPLY)) {
        clearLogs(claim)
        finish(claim)
    }

    // The swap count raised with the save's record (when it keeps one), in
    // a group's transaction, `done` of `of` groups in.
    private suspend fun raise(tx: SupportSQLiteDatabase, done: Long, of: Long) {
        tx.execSQL(ENSURE_MERGE_STATE_SQL)
        val args = record(done, of)
        if (args == null) tx.execSQL(RAISE_SWAP_COUNT_SQL) else tx.execSQL(RECORD_RAISE_SQL, args)
    }

    // The renames in force go, a page a transaction, right before an apply
    // that publishes renames of its own.
    private suspend fun clearRenames() {
        val rows = (bound / (costs.whole("save_rename") + 1)).coerceAtLeast(1)
        while (true) {
            val end = long("SELECT rowid FROM save_rename ORDER BY rowid LIMIT 1 OFFSET ?1", rows - 1)
            db.withTransaction {
                if (end == null) sql.execSQL("DELETE FROM save_rename") else sql.execSQL("DELETE FROM save_rename WHERE rowid <= ?", arrayOf<Any?>(end))
            }
            if (end == null) return
        }
    }

    private suspend fun group(g: Long) {
        val run = (long(GROUP_MOST_SQL, g) ?: 0) > capacity
        val cursor = GroupCursor(if (run) RunRows(g) else HeldRows(g))
        while (!cursor.done) {
            turns.group(first = turnsDone == 0L) { tx ->
                hooks().inGroup(turnsDone + 1, g)
                Page(tx, run).use { page ->
                    cursor.fill(page)
                    if (cursor.done) cursor.rows.ended(page)
                    page.finish(if (cursor.done) g else g - 1)
                    rows += page.rows
                    page.renames
                }
            }
            turnsDone++
            hooks().betweenGroups(turnsDone)
        }
    }

    // What a group writes, step by step.
    private sealed class Step {
        class Found(val tbl: String, val op: Int) : Step()

        object Renames : Step()

        class Refs(val target: RenameTarget) : Step()

        object CatchUp : Step()
    }

    // One found row, as save_found holds it.
    private class FoundRow(val seq: Long, val id: String, val oldId: String?, val newId: String?, val seen: Int?, val new: Int?, val cost: Long)

    // One reference or rename, as save_ref holds it.
    private class RefRow(
        val seq: Long,
        val kind: String,
        val keyText: String,
        val keyNum: Long,
        val keyNum2: Long,
        val oldId: String,
        val relinkKey: String,
        val target: String?,
    )

    private fun foundOf(c: Cursor) = FoundRow(c.getLong(0), c.getString(1), c.str(2), c.str(3), c.int(4), c.int(5), c.getLong(6))

    private fun refOf(c: Cursor) = RefRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6), c.str(7))

    // A group's rows: each step's next one, look-ups by id, and what is
    // done with a row once applied.
    private abstract inner class Rows {
        // The step's next row not yet applied or gone past, or null.
        abstract fun next(page: Page, step: Step): Any?

        // Past that row: applied, or (a gone song's own copy) left to its
        // song's row.
        abstract fun past(page: Page, step: Step, row: Any)

        abstract fun foundAt(page: Page, tbl: String, op: Int, id: String): FoundRow?

        abstract fun opOf(page: Page, tbl: String, id: String): Int?

        // A row applied: gone from the work rows (now, or with the group).
        abstract fun applied(page: Page, row: FoundRow)

        abstract fun applied(page: Page, row: RefRow)

        // Whether a run marked this song's total to count again, by key; a
        // group counts every marked song at its end.
        open fun marked(page: Page, song: String): Boolean = false

        // The group's last page, after its rows.
        open fun ended(page: Page) {}

        // Each list's answer to whether it takes a new id, by old id, queue
        // and list, kept across a run's pages (Page.listTakes).
        val lists = HashMap<Triple<String, Long, Long>, Boolean>()
    }

    // A group that is no run: its rows read whole into memory, once, in the
    // order the find wrote them, and deleted by the group's key at its end.
    private inner class HeldRows(private val g: Long) : Rows() {
        private var found: Map<Pair<String, Int>, List<FoundRow>>? = null
        private var byId: Map<Pair<String, Int>, Map<String, FoundRow>> = emptyMap()
        private var refRows: Map<String, List<RefRow>> = emptyMap()
        private val at = HashMap<Step, Int>()
        private val done = HashSet<Long>()

        private fun load(tx: SupportSQLiteDatabase) {
            if (found != null) return
            val rows = tx.query(GROUP_FOUND_SQL, arrayOf<Any?>(g)).use { c ->
                buildList { while (c.moveToNext()) add((c.getString(7) to c.getInt(8)) to foundOf(c)) }
            }
            val grouped = rows.groupBy({ it.first }, { it.second })
            found = grouped
            byId = grouped.mapValues { (_, list) -> list.associateBy { it.id } }
            refRows = tx.query(GROUP_REFS_SQL, arrayOf<Any?>(g)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(8) to refOf(c)) }
            }.groupBy({ it.first }, { it.second })
        }

        private fun list(tx: SupportSQLiteDatabase, step: Step): List<Any> {
            load(tx)
            return when (step) {
                is Step.Found -> found!![step.tbl to step.op].orEmpty()
                Step.Renames, Step.CatchUp -> refRows[REF_RENAME].orEmpty()
                is Step.Refs -> refRows[step.target.table].orEmpty()
            }
        }

        override fun next(page: Page, step: Step): Any? {
            val rows = list(page.tx, step)
            var i = at[step] ?: 0
            while (i < rows.size && (rows[i] as? FoundRow)?.seq in done) i++
            at[step] = i
            return rows.getOrNull(i)
        }

        override fun past(page: Page, step: Step, row: Any) {
            at[step] = (at[step] ?: 0) + 1
        }

        override fun foundAt(page: Page, tbl: String, op: Int, id: String): FoundRow? {
            load(page.tx)
            return byId[tbl to op]?.get(id)?.takeIf { it.seq !in done }
        }

        override fun opOf(page: Page, tbl: String, id: String): Int? {
            load(page.tx)
            return byId.entries.firstOrNull { (key, rows) -> key.first == tbl && id in rows }?.key?.second
        }

        override fun applied(page: Page, row: FoundRow) {
            done += row.seq
        }

        override fun applied(page: Page, row: RefRow) {}

        override fun ended(page: Page) {
            page.tx.execSQL(GROUP_FOUND_GONE_SQL, arrayOf<Any?>(g))
            page.tx.execSQL(GROUP_REFS_GONE_SQL, arrayOf<Any?>(g))
        }
    }

    // A run: its rows read one at a time by its group's key past the last
    // gone past, each read counted against the page, and each row deleted
    // as it is applied.
    private inner class RunRows(private val g: Long) : Rows() {
        private val after = HashMap<Step, String>()
        private val afterSeq = HashMap<Step, Long>()

        override fun next(page: Page, step: Step): Any? = when (step) {
            is Step.Found -> page.tx.query(FOUND_PAGE_SQL, arrayOf<Any?>(g, step.tbl, step.op, after[step] ?: "", 1)).use { c -> if (c.moveToFirst()) foundOf(c) else null }
            Step.Renames, Step.CatchUp -> refAfter(page, REF_RENAME, afterSeq[step] ?: Long.MIN_VALUE)
            is Step.Refs -> refAfter(page, step.target.table, afterSeq[step] ?: Long.MIN_VALUE)
        }

        private fun refAfter(page: Page, tbl: String, seq: Long): RefRow? =
            page.tx.query(REF_PAGE_SQL, arrayOf<Any?>(g, tbl, seq, 1)).use { c -> if (c.moveToFirst()) refOf(c) else null }

        override fun past(page: Page, step: Step, row: Any) {
            when (row) {
                is FoundRow -> after[step] = row.id
                is RefRow -> afterSeq[step] = row.seq
            }
        }

        override fun foundAt(page: Page, tbl: String, op: Int, id: String): FoundRow? =
            page.tx.query(FOUND_AT_SQL, arrayOf<Any?>(g, tbl, op, id)).use { c -> if (c.moveToFirst()) foundOf(c) else null }

        override fun opOf(page: Page, tbl: String, id: String): Int? =
            page.tx.query(FOUND_OP_SQL, arrayOf<Any?>(tbl, id)).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

        override fun applied(page: Page, row: FoundRow) = page.delete("save_found", row.seq)

        override fun applied(page: Page, row: RefRow) = page.delete("save_ref", row.seq)

        override fun marked(page: Page, song: String): Boolean = opOf(page, FOUND_TOTALS, song) != null
    }

    // Where a group has got to: its step, and in it the rows gone past.
    private inner class GroupCursor(val rows: Rows) {
        var done = false
            private set
        private var at = 0

        suspend fun fill(page: Page) {
            while (at < steps.size) {
                val step = steps[at]
                while (true) {
                    // A run's read of its next row counts against its page,
                    // and begins the row's unit.
                    page.startUnit()
                    if (!page.reads(1)) return
                    val row = rows.next(page, step) ?: break
                    val applied = when (step) {
                        is Step.Found -> page.found(rows, step, row as FoundRow)
                        Step.Renames -> page.rename(row as RefRow)
                        is Step.Refs -> page.ref(rows, step.target, row as RefRow)
                        Step.CatchUp -> page.catchUp(rows, row as RefRow)
                    }
                    if (!applied) return
                    rows.past(page, step, row)
                }
                at++
            }
            done = true
        }
    }

    // One group's transaction, or one page of a run's: its statements, what
    // it has cost so far, the songs whose play totals it counts again at its
    // end, and the renames it published.
    private inner class Page(val tx: SupportSQLiteDatabase, private val run: Boolean) : Closeable {
        private val compiled = HashMap<String, SupportSQLiteStatement>()
        private var cost = 0L
        private val recount = LinkedHashSet<String>()

        // Phone plays moved onto (or off) a song in this transaction, and a
        // new song's plays from before the save.
        private val moved = HashMap<String, Int>()
        private val base = HashMap<String, Int>()
        val renames = LinkedHashMap<String, String>()
        private val stepRows = LinkedHashMap<String, Long>()
        var rows = 0L
            private set

        // What counting one song's play total again costs.
        private val recountCost = costs.applyTotals(copies)

        // The songs whose marks a run's page has looked up: marked to be
        // counted again, or not.
        private val marks = HashMap<String, Boolean>()

        // What the page had cost when the row being applied was read. A
        // page's first row always goes in, so a run always moves on; the
        // floor holds the largest such row (SaveCosts.largestRunPage).
        private var unitStart = 0L

        fun startUnit() {
            unitStart = cost
        }

        // Whether a piece of `more` fits: a group's always (the plan sized
        // it); a run's page while it stays within its capacity.
        private fun fits(more: Long): Boolean {
            if (run && unitStart > 0 && cost + more > capacity) return false
            cost += more
            return true
        }

        // Reads a run makes before it knows what a row costs.
        fun reads(n: Long): Boolean = !run || fits(n)

        private fun statement(text: String) = compiled.getOrPut(text) { tx.compileStatement(text) }

        private fun write(step: String, text: String, vararg args: Any?): Int {
            val s = statement(text)
            s.bindAll(*args)
            val n = s.executeUpdateDelete()
            stepRows.merge(step, n.toLong(), Long::plus)
            rows += n
            return n
        }

        // A new row in, unless one with its key is there already.
        private fun insert(table: String, step: String, text: String, vararg args: Any?) {
            if (long(statements.has.getValue(table), args[0]) != 1L) write(step, text, *args)
        }

        fun delete(table: String, seq: Long) {
            val s = statement("DELETE FROM `$table` WHERE seq = ?1")
            s.bindAll(seq)
            s.executeUpdateDelete()
        }

        private fun long(query: String, vararg args: Any?): Long? = tx.query(query, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

        // The columns of a row that still differ from what the save writes,
        // by a read of its flags (`flags`, one a column of `columns`).
        private fun differing(flags: String, columns: List<String>, vararg args: Any?): List<String> =
            tx.query(flags, args).use { c -> if (!c.moveToFirst()) emptyList() else columns.filterIndexed { i, _ -> c.getLong(i) != 0L } }

        // What a run's page keeps aside to count again the songs a write
        // touches: each song's mark looked up, once a page, and a recount of
        // each one marked (or in `always`) and not counted yet. Null when the
        // look-ups do not fit. A group's songs are counted by the totals rows
        // the plan gave it.
        private fun touches(rows: Rows, songs: List<String?>, always: List<String?> = emptyList()): Long? {
            if (!run) return 0
            val ask = songs.filterNotNull().filter { it.isNotEmpty() && it !in recount && it !in marks }.distinct()
            if (ask.isNotEmpty() && !reads(ask.size.toLong())) return null
            for (song in ask) marks[song] = rows.marked(this, song)
            val counted = (songs + always).filterNotNull().filter { it.isNotEmpty() && it !in recount }.distinct()
            return counted.count { it in always || marks[it] == true } * recountCost
        }

        // A song whose play total a run's page counts again: one the find
        // marked for that. A group counts every marked song at its end.
        private fun touched(rows: Rows, song: String?) {
            if (!run || song.isNullOrEmpty() || song in recount) return
            if (marks.getOrPut(song) { rows.marked(this, song) }) recount += song
        }

        // Plays moved from one song to another, `n` of them, both counted
        // again at the end.
        private fun plays(from: String, to: String, n: Int) {
            if (n == 0) return
            moved[from] = (moved[from] ?: 0) - n
            moved[to] = (moved[to] ?: 0) + n
            recount += from
            recount += to
        }

        // One found row of a step, if it fits.
        fun found(rows: Rows, step: Step.Found, row: FoundRow): Boolean {
            val tbl = step.tbl
            when (step.op) {
                FOUND_NEW -> when (tbl) {
                    "track" -> {
                        if (!reads(2)) return false
                        val own = rows.foundAt(this, FOUND_MERGED_ID, FOUND_CHANGED, row.id)
                        val played = long(PLAYED_SQL, row.id) == 1L
                        val t = touches(rows, listOf(row.id, own?.oldId), always = if (played) listOf(row.id) else emptyList()) ?: return false
                        if (!fits(row.cost + (own?.cost ?: 0) + (if (played) 1 else 0) + t)) return false
                        insert("track", "insert track", statements.songIn, row.id, row.new)
                        unstale(RENAME_SONG, row.id)
                        // A new song's own copy points at it as it comes.
                        if (own != null) mergedId(rows, own)
                        // Plays already under its id count from now on.
                        if (played) {
                            base[row.id] = long(PLAYS_OF_SQL, row.id)?.toInt() ?: 0
                            recount += row.id
                        }
                        touched(rows, row.id)
                    }
                    RENAME_ALBUM, RENAME_ARTIST -> {
                        if (!fits(row.cost)) return false
                        insert(tbl, "insert $tbl", statements.libraryIn.getValue(tbl), row.id)
                        unstale(tbl, row.id)
                    }
                    else -> {
                        if (!fits(row.cost)) return false
                        insert(tbl, "insert $tbl", statements.sourceIn.getValue(tbl), row.id)
                    }
                }
                FOUND_CHANGED -> when (tbl) {
                    "track" -> {
                        val t = touches(rows, listOf(row.id)) ?: return false
                        if (!fits(row.cost + t)) return false
                        val columns = differing(statements.libraryDiffers.getValue(tbl), statements.libraryColumnsSet.getValue(tbl), row.id, row.seen, row.new)
                        if (columns.isNotEmpty()) {
                            val text = statements.libraryChanged(tbl, columns)
                            if ("rating" in columns) write("update track", text, row.id, row.seen, row.new) else write("update track", text, row.id)
                        }
                        touched(rows, row.id)
                    }
                    RENAME_ALBUM, RENAME_ARTIST -> {
                        if (!fits(row.cost)) return false
                        val columns = differing(statements.libraryDiffers.getValue(tbl), statements.libraryColumnsSet.getValue(tbl), row.id)
                        if (columns.isNotEmpty()) write("update $tbl", statements.libraryChanged(tbl, columns), row.id)
                    }
                    FOUND_MERGED_ID, FOUND_UNMERGE -> {
                        // A gone song's own copy leaves it with the song's row.
                        if (!reads(1)) return false
                        if (rows.opOf(this, "track", row.id) == FOUND_GONE) return true
                        val t = touches(rows, listOf(row.oldId, if (tbl == FOUND_MERGED_ID) row.newId else null)) ?: return false
                        if (!fits(row.cost + t)) return false
                        if (tbl == FOUND_MERGED_ID) mergedId(rows, row) else unmerge(rows, row)
                        return true
                    }
                    FOUND_TOTALS -> {
                        if (!fits(row.cost)) return false
                        recount += row.id
                    }
                    else -> {
                        val t = if (tbl == "source_track") touches(rows, listOf(row.oldId)) ?: return false else 0L
                        if (!fits(row.cost + t)) return false
                        val all = statements.sourceColumnsSet.getValue(tbl)
                        val columns = if (tbl == "source_track") {
                            differing(statements.sourceDiffers.getValue(tbl), all, row.id, row.seen)
                        } else {
                            differing(statements.sourceDiffers.getValue(tbl), all, row.id)
                        }
                        if (columns.isNotEmpty()) {
                            val text = statements.sourceChanged(tbl, columns)
                            if (tbl == "source_track" && "rating" in columns) write("update $tbl", text, row.id, row.seen) else write("update $tbl", text, row.id)
                        }
                        if (tbl == "source_track") touched(rows, row.oldId)
                    }
                }
                FOUND_PLACED -> {
                    if (!fits(row.cost)) return false
                    write("update seq of $tbl", statements.sourcePlaced.getValue(tbl), row.id)
                }
                FOUND_RATED -> {
                    if (!fits(row.cost)) return false
                    write("update track", statements.songRated, row.id, row.seen, row.new)
                }
                FOUND_GONE -> when (tbl) {
                    "track" -> {
                        // The song goes with its own copy: the copy's merged
                        // id moved, its merged id taken away, or its row gone.
                        if (!reads(3)) return false
                        val merged = rows.foundAt(this, FOUND_MERGED_ID, FOUND_CHANGED, row.id)
                        val unmerged = rows.foundAt(this, FOUND_UNMERGE, FOUND_CHANGED, row.id)
                        val rowGone = rows.foundAt(this, "source_track", FOUND_GONE, row.id)
                        val own = listOfNotNull(merged, unmerged, rowGone).sumOf { it.cost }
                        val t = touches(rows, listOf(row.id, merged?.oldId, merged?.newId, unmerged?.oldId, rowGone?.oldId)) ?: return false
                        if (!fits(row.cost + own + t)) return false
                        merged?.let { mergedId(rows, it) }
                        unmerged?.let { unmerge(rows, it) }
                        rowGone?.let { sourceGone(rows, "source_track", it) }
                        write("delete track", statements.libraryGone.getValue("track"), row.id)
                        touched(rows, row.id)
                    }
                    RENAME_ALBUM, RENAME_ARTIST -> {
                        if (!fits(row.cost)) return false
                        write("delete $tbl", statements.libraryGone.getValue(tbl), row.id)
                    }
                    FOUND_TAP -> {
                        if (!fits(row.cost)) return false
                        write("delete rating_tap", statements.tapGone, row.id)
                    }
                    "source_track" -> {
                        if (!reads(1)) return false
                        if (rows.opOf(this, "track", row.id) == FOUND_GONE) return true
                        val t = touches(rows, listOf(row.oldId)) ?: return false
                        if (!fits(row.cost + t)) return false
                        sourceGone(rows, tbl, row)
                        return true
                    }
                    else -> {
                        if (!fits(row.cost)) return false
                        sourceGone(rows, tbl, row)
                        return true
                    }
                }
                else -> error("no step applies a found row of $tbl with op ${step.op}")
            }
            rows.applied(this, row)
            return true
        }

        private fun mergedId(rows: Rows, row: FoundRow) {
            write("merged ids of source_track", statements.mergedId, row.id, row.newId)
            rows.applied(this, row)
            touched(rows, row.oldId)
            touched(rows, row.newId)
        }

        private fun unmerge(rows: Rows, row: FoundRow) {
            write("forget merged ids of source_track", statements.unmerge, row.id)
            rows.applied(this, row)
            touched(rows, row.oldId)
        }

        private fun sourceGone(rows: Rows, tbl: String, row: FoundRow) {
            write("delete $tbl", statements.sourceGone.getValue(tbl), row.id)
            rows.applied(this, row)
            if (tbl == "source_track") touched(rows, row.oldId)
        }

        // A new id that an earlier save's rename names no longer follows it.
        private fun unstale(kind: String, id: String) {
            if (!staleRenames) return
            write("delete save_rename", "DELETE FROM save_rename WHERE kind = ?1 AND old = ?2", kind, id)
        }

        // A rename published: in force from this transaction on, for the
        // triggers, the reads and the writers that follow it. Its row stays
        // until its catch-up.
        fun rename(row: RefRow): Boolean {
            val target = row.target ?: return true
            if (!fits(REF_READS + costs.whole("save_rename"))) return false
            write("insert save_rename", RENAME_IN_SQL, row.kind, row.oldId, target, row.keyNum != 0L, row.relinkKey)
            if (row.kind == RENAME_SONG && row.keyNum == 0L) renames[row.oldId] = target
            return true
        }

        // A reference the find saw, moved by its table's key while it still
        // holds the id and relink key it was seen with.
        fun ref(rows: Rows, t: RenameTarget, row: RefRow): Boolean {
            if (isRekey(row.oldId, row.target)) return rekey(rows, t, row)
            val plays = if (t.table == "play_event") touches(rows, emptyList(), always = listOf(row.oldId, row.target)) ?: return false else 0L
            if (!fits(costs.applyRef(t.table, t.column, triggered = true) + plays)) return false
            val target = row.target
            if (target != null && (t.guard != RenameGuard.InList || listTakes(rows, t, row, target))) {
                val q = refs.getValue(t.table)
                val n = write("update ${t.table}", q.move.text, *q.move.args(row, target))
                if (t.table == "play_event") plays(row.oldId, target, n)
            }
            rows.applied(this, row)
            return true
        }

        // A row on a song, album or artist the library keeps (or one it
        // moved to earlier in this step), while it still holds the key it was
        // seen with, takes the key that one is filed under now: the group has
        // written its own row already.
        private fun rekey(rows: Rows, t: RenameTarget, row: RefRow): Boolean {
            if (!fits(costs.applyRekey(t.table))) return false
            val key = tx.query(FILED_KEY_SQL.getValue(row.kind), arrayOf<Any?>(row.oldId)).use { c -> if (c.moveToFirst()) c.str(0) else null }
            if (!key.isNullOrEmpty() && key != row.relinkKey) {
                val q = refs.getValue(t.table).rekey ?: error("${t.table} keeps no key that follows what it names")
                write("rekey ${t.table}", q.text, *q.args(row, key))
            }
            rows.applied(this, row)
            return true
        }

        // For a table whose rows move as a list (RenameGuard.InList):
        // whether the list of the row a reference names takes the new id.
        // It is read once for all of the list's rows on the old id, before
        // any of them moved, as one update of them all reads it: the list
        // takes it unless it held the new id already. The two reads are the
        // row by its key and the guard by the new id.
        private fun listTakes(rows: Rows, t: RenameTarget, row: RefRow, target: String): Boolean {
            val (queue, entry) = t.keyNums
            val list = long("SELECT listId FROM `${t.table}` WHERE `$queue` = ?1 AND `$entry` = ?2 AND `${t.column}` = ?3", row.keyNum, row.keyNum2, row.oldId) ?: return true
            return rows.lists.getOrPut(Triple(row.oldId, row.keyNum, list)) {
                long("SELECT EXISTS (SELECT 1 FROM `${t.table}` WHERE `${t.column}` = ?1 AND +`$queue` = ?2 AND +listId = ?3)", target, row.keyNum, list) == 0L
            }
        }

        // Rows written since the find on a renamed id follow its rename, as
        // the rename triggers move a row written from now on.
        fun catchUp(rows: Rows, row: RefRow): Boolean {
            val plays = if (row.kind == RENAME_SONG) touches(rows, emptyList(), always = listOf(row.oldId, row.target)) ?: return false else 0L
            if (!fits(costs.applyRename() - REF_READS - costs.whole("save_rename") + plays)) return false
            val probe = probes.getValue(row.kind)
            val held = tx.query(probe.sql, arrayOf<Any?>(row.oldId)).use { c ->
                c.moveToFirst()
                probe.targets.filterIndexed { i, _ -> c.getLong(i) != 0L }
            }
            for (t in held) {
                val n = write("update ${t.table}", catchUps.getValue(t.table to row.kind), row.oldId)
                val target = row.target
                if (t.table == "play_event" && target != null) plays(row.oldId, target, n)
            }
            rows.applied(this, row)
            return true
        }

        // Each counted song's total again, as totalOf counts it: the phone's
        // plays from the stored row (or a new song's own) and those moved
        // here, its latest play by the play index, and its copies read again.
        // Then the swap count rises with the save's record, `done` groups in,
        // and the steps go to the hooks.
        suspend fun finish(done: Long) {
            if (recount.isNotEmpty()) {
                val account = tx.query(TOTALS_ACCOUNT_SQL).use { c -> if (c.moveToFirst()) c.str(0) else null }
                val totals = db.playTotalsDao()
                for (song in recount) {
                    val stored = totals.totalOf(song)
                    val keys = tx.query(SONG_KEYS_SQL, arrayOf<Any?>(song)).use { c ->
                        if (c.moveToFirst()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)) else null
                    }
                    if (keys == null) {
                        if (stored != null) totals.deleteTotals(listOf(song))
                        continue
                    }
                    val plays = (base[song] ?: stored?.phonePlays ?: 0) + (moved[song] ?: 0)
                    val latest = if (plays > 0) long(LATEST_PLAY_SQL, song) else null
                    val phone = if (plays > 0 && latest != null) {
                        PhonePlays(song, keys[0] as String, keys[1] as String, keys[2] as String, keys[3] as String, keys[4] as Long, plays, latest)
                    } else {
                        null
                    }
                    val now = totalOf(SongPlays(song, phone, totals.copiesOf(song, account)))
                    when {
                        now == stored -> Unit
                        now == null -> totals.deleteTotals(listOf(song))
                        else -> totals.putTotals(listOf(now))
                    }
                }
            }
            raise(tx, done, planned)
            val turn = turnsDone + 1
            for ((step, n) in stepRows) hooks().groupStep(turn, step, n)
        }

        override fun close() = compiled.values.forEach { it.close() }
    }

    // A statement on one row of a reference table and its arguments, from
    // the reference and the value it writes (a move's new id, or a row's new
    // key), in the order it numbers them.
    private class RowSql(val text: String, private val getters: List<(RefRow, String) -> Any?>) {
        fun args(row: RefRow, value: String): Array<Any?> = getters.map { it(row, value) }.toTypedArray()
    }

    // A reference's statements by its table's key: its move, and where the
    // table's keys follow their songs, the row's key set (`rekey`).
    private class RefSql(t: RenameTarget) {
        val move: RowSql = statement(t, rekey = false)
        val rekey: RowSql? = if (t.keyFollows) statement(t, rekey = true) else null

        private companion object {
            fun statement(t: RenameTarget, rekey: Boolean): RowSql {
                val getters = ArrayList<(RefRow, String) -> Any?>()
                fun arg(get: (RefRow, String) -> Any?): String {
                    getters += get
                    return "?${getters.size}"
                }
                val written = arg { _, value -> value }
                val set = if (rekey) "`relinkKey` = $written" else "`${t.column}` = $written"
                val where = ArrayList<String>()
                t.keyText?.let { where += "`$it` = ${arg { r, _ -> r.keyText }}" }
                t.keyNums.getOrNull(0)?.let { where += "`$it` = ${arg { r, _ -> r.keyNum }}" }
                t.keyNums.getOrNull(1)?.let { where += "`$it` = ${arg { r, _ -> r.keyNum2 }}" }
                if (t.kind == null) where += "`kind` = ${arg { r, _ -> r.kind }}"
                where += "`${t.column}` = ${arg { r, _ -> r.oldId }}"
                if (t.keyed) where += "`relinkKey` = ${arg { r, _ -> r.relinkKey }}"
                t.only?.let { (name, value) -> where += "`$name` = $value" }
                if (!rekey) {
                    val target = written
                    when (t.guard) {
                        RenameGuard.None -> Unit
                        RenameGuard.Taken -> where += "NOT EXISTS (SELECT 1 FROM `${t.table}` o WHERE o.`${t.column}` = $target" +
                            (if (t.kind == null) " AND o.`kind` = `${t.table}`.`kind`" else "") + ")"
                        // A list's rows move as one (Page.ref keeps the list's
                        // answer): the guard is read once for all of them.
                        RenameGuard.InList -> Unit
                    }
                }
                return RowSql("UPDATE `${t.table}` SET $set WHERE ${where.joinToString(" AND ")}", getters)
            }
        }
    }

    // For each kind of id, one read of whether any reference table holds a
    // renamed id: a column a table.
    private class Probe(val sql: String, val targets: List<RenameTarget>)

    private val probes: Map<String, Probe> = listOf(RENAME_SONG, RENAME_ALBUM, RENAME_ARTIST).associateWith { kind ->
        val targets = SAVE_RENAME_TARGETS.filter { it.kind == kind || it.kind == null }
        Probe(
            "SELECT " + targets.joinToString { t ->
                "EXISTS (SELECT 1 FROM `${t.table}` WHERE `${t.column}` = ?1" + (if (t.kind == null) " AND `kind` = '$kind'" else "") + ")"
            },
            targets,
        )
    }

    // A reference table's rows on a renamed id (?1) moved as its rename
    // trigger moves a row written from now on, by table and kind.
    private val catchUps: Map<Pair<String, String>, String> = buildMap {
        for (kind in listOf(RENAME_SONG, RENAME_ALBUM, RENAME_ARTIST)) {
            for (t in SAVE_RENAME_TARGETS.filter { it.kind == kind || it.kind == null }) {
                val row = { column: String -> "`${t.table}`.`$column`" }
                val pin = if (t.kind == null) " AND `kind` = '$kind'" else ""
                put(
                    t.table to kind,
                    "UPDATE `${t.table}` SET `${t.column}` = (SELECT r.`new` FROM save_rename r WHERE r.kind = '$kind' AND r.`old` = `${t.table}`.`${t.column}`) " +
                        "WHERE `${t.column}` = ?1$pin AND ${t.condition(row)}",
                )
            }
        }
    }

    // The last steps of a claimed apply, each a page a transaction: the
    // ratings noted since the claim shown over the copies' and the merge's,
    // each claimed copy's bookkeeping, and the take-in log and the rating
    // notes cleared.
    private suspend fun lastSteps(claim: ApplyClaim) {
        val notes = (bound / NOTE_COST).coerceAtLeast(1)
        var after = ""
        while (true) {
            val page = sql.query(NOTES_PAGE_SQL, arrayOf<Any?>(after, notes)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
            if (page.isNotEmpty()) {
                db.withTransaction {
                    sql.compileStatement(SHOW_NOTE_SQL).use { s ->
                        for ((row, rating) in page) {
                            s.bindAll(row, rating)
                            rows += s.executeUpdateDelete()
                        }
                    }
                }
            }
            if (page.size < notes) break
            after = page.last().first
        }
        // Each claimed copy's stage rows stay until the merge has let the
        // gate go; it is marked swapped in now, so nothing reads them again,
        // and marked clearing, so the source's next copy waits for them to go
        // before it stages anything. The record says so in the same
        // transaction, so a resume never does this bookkeeping twice.
        val work = db.workDao()
        db.withTransaction {
            for ((source, _) in claim.claimed) {
                work.owedSteps(source)
                work.takeStageOwner(source)
                work.stageApplied(source)
                work.markStageClearing(source)
            }
            lastStepsBegun()
        }
        clearLogs(claim)
    }

    // The claim's take-in log and, once no server copy is pending, the
    // rating notes, cleared a page a transaction.
    private suspend fun clearLogs(claim: ApplyClaim) {
        val page = (bound / CLEAR_COST).coerceAtLeast(1)
        for ((_, generation) in claim.claimed) {
            for (kind in listOf("track", "album", "artist")) {
                while (true) {
                    val end = sql.query(TAKE_IN_END_SQL, arrayOf<Any?>(generation, kind, page - 1)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    db.withTransaction {
                        if (end == null) {
                            sql.execSQL("DELETE FROM take_in_log WHERE generation = ?1 AND kind = ?2", arrayOf<Any?>(generation, kind))
                        } else {
                            sql.execSQL("DELETE FROM take_in_log WHERE generation = ?1 AND kind = ?2 AND id <= ?3", arrayOf<Any?>(generation, kind, end))
                        }
                    }
                    if (end == null) break
                }
            }
        }
        if (noServerCopyPending()) {
            while (true) {
                val end = sql.query(NOTES_END_SQL, arrayOf<Any?>(page - 1)).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                db.withTransaction {
                    if (end == null) sql.execSQL("DELETE FROM rating_note") else sql.execSQL("DELETE FROM rating_note WHERE sourceRowId <= ?1", arrayOf<Any?>(end))
                }
                if (end == null) break
            }
        }
    }

    // Rating notes last while any server's copy is being built or waits to
    // be swapped in.
    private suspend fun noServerCopyPending(): Boolean = db.workDao().metas().none { it.sourceId != DEVICE && it.stage != STAGE_NONE }

    // The final transaction: the claim is over, the skip key the record
    // keeps (or one from the stamps now) and the merged server are written,
    // and the save's record goes.
    private suspend fun finish(claim: ApplyClaim) {
        val work = db.workDao()
        db.withTransaction {
            work.setClaimActive(false)
            if (noServerCopyPending()) work.clearRatingNotes()
            val stamps = work.metas().associate { it.sourceId to it.stamp }
            work.setSkipKey(work.mergeState()?.applySkipKey ?: skipKey(claim.sources, stamps, emptyMap()))
            work.setMergedServer(claim.server)
            work.endApplyRecord()
        }
    }

    private fun long(query: String, vararg args: Any?): Long? = sql.query(query, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private companion object {
        // What showing one rating note costs (its copy and song read by
        // key, the rating written with its indexes), and clearing one row
        // of the take-in log or the notes.
        const val NOTE_COST = 8L
        const val CLEAR_COST = 4L
    }
}

// Each group's rows of one table and op past an id, in id order, by the
// group's key; and one of them by id.
internal const val FOUND_PAGE_SQL =
    "SELECT seq, id, oldId, newId, seenRating, newRating, cost, tbl, op FROM save_found WHERE groupId = ?1 AND tbl = ?2 AND op = ?3 AND id > ?4 ORDER BY id LIMIT ?5"
internal const val FOUND_AT_SQL =
    "SELECT seq, id, oldId, newId, seenRating, newRating, cost, tbl, op FROM save_found WHERE groupId = ?1 AND tbl = ?2 AND op = ?3 AND id = ?4"

// A group's rows whole, by its key, in the order the find wrote them, and
// gone by its key.
internal const val GROUP_FOUND_SQL =
    "SELECT seq, id, oldId, newId, seenRating, newRating, cost, tbl, op FROM save_found WHERE groupId = ?1 ORDER BY groupId, tbl, op, id"
internal const val GROUP_FOUND_GONE_SQL = "DELETE FROM save_found WHERE groupId = ?1"

internal const val REF_PAGE_SQL =
    "SELECT seq, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target, tbl FROM save_ref WHERE groupId = ?1 AND tbl = ?2 AND seq > ?3 ORDER BY seq LIMIT ?4"
internal const val GROUP_REFS_SQL =
    "SELECT seq, kind, keyText, keyNum, keyNum2, oldId, relinkKey, target, tbl FROM save_ref WHERE groupId = ?1 ORDER BY groupId, tbl, seq"
internal const val GROUP_REFS_GONE_SQL = "DELETE FROM save_ref WHERE groupId = ?1"

// A few of the found rows and references left after every group, which a
// whole apply leaves none of.
internal const val LEFT_SQL =
    "SELECT * FROM (SELECT tbl, op, id, groupId FROM save_found LIMIT 5) UNION ALL SELECT * FROM (SELECT tbl, kind, oldId, groupId FROM save_ref LIMIT 5)"

// The largest set of a group: over a group's capacity, the group is a run.
internal const val GROUP_MOST_SQL = "SELECT MAX(cost) FROM save_set WHERE groupId = ?1"

internal const val HAS_RENAMES_SQL = "SELECT EXISTS (SELECT 1 FROM save_ref WHERE groupId = ?1 AND tbl = 'save_rename')"

internal const val RENAME_IN_SQL = "INSERT OR REPLACE INTO save_rename (kind, old, new, byKey, `key`) VALUES (?1, ?2, ?3, ?4, ?5)"

// A song's plays on the phone: whether it has any, how many, its latest.
internal const val PLAYED_SQL = "SELECT EXISTS (SELECT 1 FROM play_event WHERE trackId = ?1)"
internal const val PLAYS_OF_SQL = "SELECT COUNT(*) FROM play_event WHERE trackId = ?1"
internal const val LATEST_PLAY_SQL = "SELECT startedAt FROM play_event WHERE trackId = ?1 ORDER BY startedAt DESC LIMIT 1"

// What a song's play total keeps of it, and the account its copies' plays
// are counted for.
internal const val SONG_KEYS_SQL = "SELECT albumId, artistId, sortKey, artistSortKey, durationMs FROM track WHERE id = ?1"

// The key a song, album or artist is filed under, by kind.
internal val FILED_KEY_SQL: Map<String, String> = mapOf(
    RENAME_SONG to "SELECT relinkKey FROM track WHERE id = ?1",
    RENAME_ALBUM to "SELECT searchKey FROM album WHERE id = ?1",
    RENAME_ARTIST to "SELECT searchKey FROM artist WHERE id = ?1",
)
internal const val TOTALS_ACCOUNT_SQL = "SELECT totalsAccount FROM merge_state WHERE id = 0"

internal const val ENSURE_MERGE_STATE_SQL = "INSERT OR IGNORE INTO merge_state (id, skipKey, claimActive) VALUES (0, NULL, 0)"
internal const val RAISE_SWAP_COUNT_SQL = "UPDATE merge_state SET swapCount = swapCount + 1 WHERE id = 0"

internal const val NOTES_PAGE_SQL = "SELECT sourceRowId, rating FROM rating_note WHERE sourceRowId > ?1 ORDER BY sourceRowId LIMIT ?2"
internal const val SHOW_NOTE_SQL = "UPDATE track SET rating = ?2 WHERE id = (SELECT s.mergedId FROM source_track s WHERE s.id = ?1) AND rating IS NOT ?2"
// The last rating note of a page cleared, read by seek through its key.
internal const val NOTES_END_SQL = "SELECT sourceRowId FROM rating_note WHERE sourceRowId >= '' ORDER BY sourceRowId LIMIT 1 OFFSET ?1"
internal const val TAKE_IN_END_SQL = "SELECT id FROM take_in_log WHERE generation = ?1 AND kind = ?2 ORDER BY id LIMIT 1 OFFSET ?3"

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
