package app.winters.octo.proof

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.MERGE_PAGE
import app.winters.octo.catalog.MERGE_WORK_CLEAR
import app.winters.octo.catalog.Measure
import app.winters.octo.catalog.SAVE_APPLY
import app.winters.octo.catalog.SAVE_FIND
import app.winters.octo.catalog.SAVE_UNMERGES
import app.winters.octo.catalog.STAGE_CLEAR
import app.winters.octo.catalog.SaveFindHooks
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.stableRead
import app.winters.octo.listening.SongPlays
import app.winters.octo.listening.totalOf
import app.winters.octo.testing.Counted
import app.winters.octo.testing.CountedTransaction
import app.winters.octo.testing.StatementWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor

// The labels of a save's own transactions, which its bound holds: the
// find's writes and the apply's groups and last steps.
internal val SAVE_TRANSACTIONS = setOf(SAVE_FIND, SAVE_APPLY)

// Every label the merge writes under around a save: its claim and build
// ("merge"), the find's reads of copies out of the library, and the clears
// after the save.
internal val MERGE_WRITES = SAVE_TRANSACTIONS + setOf(SAVE_UNMERGES, MERGE_WORK_CLEAR, STAGE_CLEAR, "merge")

// The find's labels, its reads of copies out of the library included.
private val FIND_LABELS = setOf(SAVE_FIND, SAVE_UNMERGES)

// The label the proof's own reads run under inside a save's hooks, so none
// is counted as the save's.
internal const val PROOF_READ = "proof read"

// The label of the writer that writes beside a save, and its table.
internal const val RIVAL_WRITE = "rival"
internal const val RIVAL_TABLE = "rival_probe"

// How long the writer beside a save and each hold probe rest between
// turns, so the save keeps moving while they ask again and again.
private const val PROBE_REST_MS = 5L

// What one label's transactions cost while a watch ran: how many, the
// largest by the save's cost model (SaveGroupBoundTest's Measure) with what
// made it up, how many passed the bound, the longest held, and the rows
// they changed.
internal class LabelCosts(val label: String) {
    var transactions = 0L
    var largest = 0L
    var largestReads = 0L
    var largestWrites = 0L
    var largestParts = ""
    var over = 0L
    var firstOver = ""
    var longestNanos = 0L
    var rows = 0L

    // Every transaction's hold, for its median and 99th percentile.
    private var holds = LongArray(1_024)
    private var held = 0

    fun hold(nanos: Long) {
        longestNanos = maxOf(longestNanos, nanos)
        if (held == holds.size) holds = holds.copyOf(holds.size * 2)
        holds[held++] = nanos
    }

    // The hold at a share of the transactions, in milliseconds.
    fun holdAt(share: Double): Long {
        if (held == 0) return 0
        val sorted = holds.copyOf(held).also { it.sort() }
        return sorted[((held - 1) * share).toInt()] / 1_000_000
    }

    override fun toString() =
        "$label: $transactions transactions, the largest costs $largest ($largestReads reads, $largestWrites writes), " +
            "${if (over > 0) "$over over the bound ($firstOver)" else "none over the bound"}, held ${holdAt(0.5)} ms median, ${holdAt(0.99)} ms at the 99th percentile, " +
            "the longest ${longestNanos / 1_000_000} ms, $rows rows"
}

// What one walk of the find read: its statements, those that read a row
// (its pages), the rows read, and the most one statement read.
internal class WalkReads(val name: String) {
    var statements = 0L
    var pages = 0L
    var rows = 0L
    var most = 0
    var mostSql = ""

    override fun toString() = "$name: $pages pages of $statements statements, $rows rows read, at most $most in one (${mostSql.take(90)})"
}

// Watches a library's work as it runs, in memory that does not grow with
// it: every transaction of the save and of the clears after it costed by
// the save's own model, with `bound` the largest a save's may cost; the end
// of each save transaction, in order, to count how many a waiter waited
// out; the merge's writes that ran outside any transaction; and the reads
// of each walk of the find, which `walking` names as they begin.
internal class SaveWatch(sql: SupportSQLiteDatabase, @Volatile var bound: Long) : StatementWatch {
    private val measure = Measure(sql)
    private val lock = Any()
    private val costs = LinkedHashMap<String, LabelCosts>()
    private var ends = LongArray(4_096)
    private var begins = LongArray(4_096)
    private var endCount = 0
    private val walkReads = LinkedHashMap<String, WalkReads>()
    private var walk = "clear"
    private var last: Counted? = null
    private var lastWalk = walk
    private var outsideMerge = 0L
    private var outsideMergeSql: String? = null

    override fun keeps(label: String?): Boolean = label in SAVE_TRANSACTIONS || label == MERGE_WORK_CLEAR || label == STAGE_CLEAR

    override fun started(c: Counted) {
        if (c.bookkeeping || c.label !in FIND_LABELS) return
        synchronized(lock) {
            close()
            last = c
            lastWalk = walk
        }
    }

    override fun outside(c: Counted) {
        if (c.bookkeeping || c.label !in MERGE_WRITES) return
        synchronized(lock) {
            outsideMerge++
            outsideMergeSql = c.sql
        }
    }

    override fun ended(t: CountedTransaction, statements: List<Counted>) {
        val label = t.label ?: return
        if (!keeps(label)) return
        val own = statements.filter { !it.bookkeeping && it.label == label }
        synchronized(lock) {
            val cost = measure.of(own)
            val c = costs.getOrPut(label) { LabelCosts(label) }
            c.transactions++
            c.rows += t.totalChanges
            c.hold(t.heldNanos)
            if (cost.total > c.largest) {
                c.largest = cost.total
                c.largestReads = cost.reads
                c.largestWrites = cost.writes
                c.largestParts = parts(own)
            }
            if (label in SAVE_TRANSACTIONS && cost.total > bound) {
                if (c.over == 0L) c.firstOver = "costs ${cost.total}: ${parts(own)}"
                c.over++
            }
            if (label in SAVE_TRANSACTIONS) {
                if (endCount == ends.size) {
                    ends = ends.copyOf(ends.size * 2)
                    begins = begins.copyOf(ends.size)
                }
                begins[endCount] = t.beganAt
                ends[endCount++] = t.beganAt + t.heldNanos
            }
        }
    }

    // The statements that cost most in one transaction, by their text.
    private fun parts(statements: List<Counted>): String =
        statements.groupBy { it.sql.take(60) }.mapValues { (_, s) -> measure.of(s).total }.entries.sortedByDescending { it.value }.take(4).joinToString(" | ") { "${it.value} ${it.key}" }

    // A walk of the find begins (and the one before it ends).
    fun walking(name: String) = synchronized(lock) {
        close()
        walk = name
    }

    // The find's reads so far, each walk's own; the last statement is
    // closed first.
    fun walks(): List<WalkReads> = synchronized(lock) {
        close()
        walkReads.values.toList()
    }

    private fun close() {
        val c = last ?: return
        last = null
        val w = walkReads.getOrPut(lastWalk) { WalkReads(lastWalk) }
        w.statements++
        if (c.rowsRead > 0) w.pages++
        w.rows += c.rowsRead
        if (c.rowsRead > w.most) {
            w.most = c.rowsRead
            w.mostSql = c.sql
        }
    }

    fun costs(): List<LabelCosts> = synchronized(lock) { costs.values.toList() }

    fun costOf(label: String): LabelCosts? = synchronized(lock) { costs[label] }

    // The merge's writes that ran outside any transaction, with the last.
    fun outsideMerge(): Pair<Long, String?> = synchronized(lock) { outsideMerge to outsideMergeSql }

    // How many save transactions ended after `from` and up to `until`.
    fun endedBetween(from: Long, until: Long): Int = synchronized(lock) {
        val start = firstAfter(from)
        val end = firstAfter(until)
        end - start
    }

    // The save transactions that ended after `from` and up to `until`, each
    // its begin and its end (System.nanoTime).
    fun endedIn(from: Long, until: Long): List<Pair<Long, Long>> = synchronized(lock) {
        (firstAfter(from) until firstAfter(until)).map { begins[it] to ends[it] }
    }

    // Save transactions ended so far.
    fun ended(): Int = synchronized(lock) { endCount }

    private fun firstAfter(at: Long): Int {
        var lo = 0
        var hi = endCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= at) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // Everything forgotten, for the next save watched.
    fun reset() = synchronized(lock) {
        costs.clear()
        endCount = 0
        walkReads.clear()
        walk = "clear"
        last = null
        outsideMerge = 0
        outsideMergeSql = null
    }
}

// The find's walks, named to a watch as they begin.
internal class WalkHooks(private val watch: SaveWatch) : SaveFindHooks() {
    override fun walking(walk: String) = watch.walking(walk)
}

// How long each wait of a writer or a reader beside a save took, from its
// ask to its entry (System.nanoTime), and how many of the save's
// transactions ended meanwhile.
internal class Waits(val name: String) {
    private val asks = Collections.synchronizedList(ArrayList<LongArray>())

    // `during`: whether the save was part way at its entry. `queued`: when
    // the ask reached Room's transaction queue (QueueClock), 0 where not
    // noted.
    fun add(asked: Long, entered: Long, during: Boolean, queued: Long = 0) {
        asks += longArrayOf(asked, entered, if (during) 1 else 0, queued)
    }

    // `mostGroups`: the most save transactions that ended while one wait
    // waited, from its ask. `mostQueued`: the most that began after its ask
    // reached Room's queue and ended before it entered, for the waits whose
    // queue time is noted (-1 where none is). The queue is first come, first
    // served, so a queued write waits out at most the one transaction
    // already in; its own lead-in, from its ask to the queue, may add the
    // save's next one, begun before the write got there.
    class Summary(
        val name: String, val count: Int, val during: Int, val mostGroups: Int, val mostQueued: Int, val longestMs: Long, val overOne: Int, val worst: List<String>,
    ) {
        override fun toString() = "$name: $count waits, $during entered while the save was part way, at most $mostGroups save transactions waited out from the ask" +
            (if (mostQueued >= 0) ", at most $mostQueued begun after it was queued" else "") + ", the longest ${longestMs} ms" +
            if (overOne > 0) "; $overOne waits waited out more than one, the longest of them: ${worst.joinToString(" | ")}" else ""
    }

    fun summary(watch: SaveWatch): Summary {
        val all = synchronized(asks) { asks.toList() }
        val over = all.filter { watch.endedBetween(it[0], it[1]) > 1 }
        val queued = all.filter { it[3] > 0 }
        val mostQueued = if (queued.isEmpty()) -1 else queued.maxOf { a -> watch.endedIn(a[3], a[1]).count { (b, _) -> b > a[3] } }
        // Each of the longest waits that waited out more than one: its
        // length, when it was queued, and each save transaction it waited
        // out as its begin and end, all after the ask, in microseconds.
        val worst = over.sortedByDescending { it[1] - it[0] }.take(5).map { a ->
            "${(a[1] - a[0]) / 1_000} us" + (if (a[3] > 0) ", queued at ${(a[3] - a[0]) / 1_000}" else "") + ": " +
                watch.endedIn(a[0], a[1]).joinToString { (b, e) -> "began ${(b - a[0]) / 1_000} ended ${(e - a[0]) / 1_000}" }
        }
        return Summary(
            name, all.size, all.count { it[2] == 1L }, all.maxOfOrNull { watch.endedBetween(it[0], it[1]) } ?: 0, mostQueued,
            (all.maxOfOrNull { it[1] - it[0] } ?: 0) / 1_000_000, over.size, worst,
        )
    }
}

// Another writer beside a save: one short transaction of its own table
// after another, as a play or a heart is written, each wait noted.
internal class Rival(private val db: CatalogDatabase, private val merge: CatalogMerge) {
    val waits = Waits("another writer")
    private var job: Job? = null
    private val clock = QueueClock.on(db)

    // While set, it writes nothing (a test counting another writer's
    // statements meanwhile).
    @Volatile var paused = false

    fun start(scope: CoroutineScope) {
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS `$RIVAL_TABLE` (n INTEGER PRIMARY KEY NOT NULL, at INTEGER NOT NULL)")
        job = scope.launch(Dispatchers.IO + WriteLabels.of(RIVAL_WRITE)) {
            var n = 0L
            while (isActive) {
                if (paused) {
                    delay(PROBE_REST_MS)
                    continue
                }
                val queued = LongArray(1)
                val asked = System.nanoTime()
                withContext(clock.noting(queued)) {
                    db.withTransaction {
                        val entered = System.nanoTime()
                        db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO `$RIVAL_TABLE` (n, at) VALUES (?, ?)", arrayOf<Any?>(n++ % 64, entered))
                        waits.add(asked, entered, merge.applyStamp.value % 2 == 1L, queued[0])
                    }
                }
                delay(PROBE_REST_MS)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
    }
}

// When an ask reached Room's transaction queue: the database's transaction
// executor (Room's first come, first served queue) is wrapped, as it is,
// to note the time a task is offered to it for the slot the asking
// coroutine carries. A proof's measure only.
internal class QueueClock private constructor() {
    private val slot = ThreadLocal<LongArray?>()

    // A coroutine context under which an ask's queue time lands in `into`.
    fun noting(into: LongArray) = slot.asContextElement(into)

    private class Noting(val inner: Executor, val clock: QueueClock) : Executor {
        override fun execute(command: Runnable) {
            clock.slot.get()?.let { if (it[0] == 0L) it[0] = System.nanoTime() }
            inner.execute(command)
        }
    }

    companion object {
        fun on(db: RoomDatabase): QueueClock = synchronized(db) {
            val field = RoomDatabase::class.java.getDeclaredField("internalTransactionExecutor").apply { isAccessible = true }
            val now = field.get(db) as Executor
            if (now is Noting) return now.clock
            QueueClock().also { field.set(db, Noting(now, it)) }
        }
    }
}

// Holds of every kind and stable reads beside a save, one after another of
// each, each wait noted: a Read, a Page and a Walk hold entering, and a
// stable read of a page of songs as a list asks for it, short and long.
internal class HoldProbes(private val db: CatalogDatabase, private val merge: CatalogMerge) {
    val holds = HoldKind.entries.associateWith { Waits("a ${it.name} hold") }
    val reads = listOf(HoldKind.Page, HoldKind.Walk).associateWith { Waits("a stable read, its last try a ${it.name} hold") }
    private val jobs = ArrayList<Job>()

    fun start(scope: CoroutineScope) {
        for ((kind, waits) in holds) {
            jobs += scope.launch(Dispatchers.IO + WriteLabels.of(PROOF_READ)) {
                while (isActive) {
                    val asked = System.nanoTime()
                    merge.betweenRebuilds(kind) { waits.add(asked, System.nanoTime(), merge.applyStamp.value % 2 == 1L) }
                    delay(PROBE_REST_MS)
                }
            }
        }
        for ((kind, waits) in reads) {
            jobs += scope.launch(Dispatchers.IO + WriteLabels.of(PROOF_READ)) {
                var page = 0
                while (isActive) {
                    val asked = System.nanoTime()
                    stableRead(merge, kind) {
                        db.query("SELECT id FROM track ORDER BY sortKey, id LIMIT 60 OFFSET ?", arrayOf<Any?>((page++ % 50) * 60)).use { c -> while (c.moveToNext()) Unit }
                    }
                    waits.add(asked, System.nanoTime(), merge.applyStamp.value % 2 == 1L)
                    delay(PROBE_REST_MS)
                }
            }
        }
    }

    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
    }

    fun summaries(watch: SaveWatch): List<Waits.Summary> = (holds.values + reads.values).map { it.summary(watch) }
}

// The room a library takes on the phone: its pages in use (as its
// connection sees the file, the log's pages included) and the log file's
// length, sampled for the most of each.
internal class SpaceProbe(private val db: CatalogDatabase) {
    private val sql get() = db.openHelper.writableDatabase
    private val wal = File(sql.path + "-wal")
    @Volatile var mostInUse = 0L
        private set
    @Volatile var mostLog = 0L
        private set
    private var job: Job? = null

    fun inUse(): Long {
        fun pragma(name: String) = sql.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }

    fun log(): Long = wal.length()

    // The room taken since `mark`: the most pages in use above those in use
    // at the mark, and the most the log held after the mark emptied it.
    @Volatile var markInUse = 0L
        private set
    @Volatile var mostInUseSinceMark = 0L
        private set
    @Volatile var mostLogSinceMark = 0L
        private set

    // The log checkpointed in and emptied, and the room taken counted from
    // here; answers the log's length after (0 unless a reader held it).
    fun mark(): Long {
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        markInUse = inUse()
        mostInUseSinceMark = markInUse
        mostLogSinceMark = log()
        return mostLogSinceMark
    }

    fun takenSinceMark(): Long = mostInUseSinceMark - markInUse + mostLogSinceMark

    // Notes the most of each now.
    fun sample() {
        val inUse = inUse()
        val log = log()
        mostInUse = maxOf(mostInUse, inUse)
        mostLog = maxOf(mostLog, log)
        mostInUseSinceMark = maxOf(mostInUseSinceMark, inUse)
        mostLogSinceMark = maxOf(mostLogSinceMark, log)
    }

    // Samples every few hundred milliseconds until stopped, from `inUse`
    // and the log as they are now.
    fun start(scope: CoroutineScope) {
        mostInUse = inUse()
        mostLog = log()
        job = scope.launch(Dispatchers.IO + WriteLabels.of(PROOF_READ)) {
            while (isActive) {
                sample()
                delay(200)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        sample()
    }
}

// The file's pages in use (all pages less the free ones), in bytes, under
// this name in what tableSpace answers.
internal const val WHOLE_FILE = "(the whole file in use)"

// Whether the engine has SQLite's dbstat table.
internal fun hasDbstat(sql: SupportSQLiteDatabase): Boolean =
    runCatching { sql.query("SELECT COUNT(*) FROM dbstat WHERE name = 'sqlite_master'").use { it.moveToFirst() } }.getOrDefault(false)

// Each table's rows and bytes, and the file's pages in use (WHOLE_FILE, as
// bytes with no rows): a proof's measure only. The bytes are its pages' and
// its indexes' pages' by dbstat where the engine has it; where it has not
// (Android's build does not promise it, nor do the test engines), each
// save_ table's bytes are its rows' content (every column's length summed,
// no index, no page overhead) and every other table's are not known (-1).
internal fun tableSpace(sql: SupportSQLiteDatabase): Map<String, Pair<Long, Long>> {
    fun long(q: String): Long = sql.query(q).use { it.moveToFirst(); it.getLong(0) }
    val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND sql NOT LIKE 'CREATE VIRTUAL%'").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    val paged = if (hasDbstat(sql)) {
        sql.query("SELECT m.tbl_name, SUM(d.pgsize) FROM dbstat d JOIN sqlite_master m ON m.name = d.name GROUP BY m.tbl_name").use { c ->
            buildMap<String, Long> { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }
    } else {
        null
    }
    val out = LinkedHashMap<String, Pair<Long, Long>>()
    for (table in tables) {
        val rows = long("SELECT COUNT(*) FROM `$table`")
        val bytes = paged?.get(table) ?: if (table.startsWith("save_") && rows > 0) {
            val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
            long("SELECT COALESCE(SUM(" + columns.joinToString(" + ") { "COALESCE(LENGTH(`$it`), 0)" } + "), 0) FROM `$table`")
        } else if (rows == 0L) {
            0L
        } else {
            -1L
        }
        out[table] = rows to bytes
    }
    out[WHOLE_FILE] = 0L to (long("PRAGMA page_count") - long("PRAGMA freelist_count")) * long("PRAGMA page_size")
    return out
}

// The file in use, every save_ table, and every table of 10,000 rows or
// more at some measure, a line each: rows and MB at each measure (the MB
// left out where not known), the most rows first.
internal fun spaceLines(label: String, points: List<Pair<String, Map<String, Pair<Long, Long>>?>>): List<String> {
    val known = points.filter { it.second != null }
    if (known.isEmpty()) return listOf("$label: not measured")
    val tables = known.flatMap { it.second!!.keys }.toSet()
    fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
    return tables.map { t -> t to known.maxOf { it.second!![t]?.first ?: 0 } }
        .filter { (t, rows) -> t == WHOLE_FILE || t.startsWith("save_") || rows >= 10_000 }
        .sortedWith(compareBy<Pair<String, Long>> { it.first != WHOLE_FILE }.thenByDescending { it.second })
        .map { (t, _) ->
            "$label $t: " + known.joinToString("; ") { (name, m) ->
                val (rows, bytes) = m!![t] ?: (0L to 0L)
                when {
                    t == WHOLE_FILE -> "$name ${mb(bytes)}"
                    bytes < 0 -> "$name $rows rows"
                    else -> "$name $rows rows ${mb(bytes)}"
                }
            }
        }
}

// Songs whose stored play total differs from what a recount gives them now,
// with both, for `account`.
internal suspend fun totalsOff(db: CatalogDatabase, ids: Collection<String>, account: String?): List<String> = withContext(WriteLabels.of(PROOF_READ)) {
    val dao = db.playTotalsDao()
    ids.mapNotNull { id ->
        val counted = totalOf(SongPlays(id, dao.phonePlaysOf(id), dao.copiesOf(id, account)))
        val stored = db.query(
            "SELECT trackId, albumId, artistId, plays, lastPlayedAt, recentAt, phonePlays, phoneLastAt, sortKey, artistSortKey FROM play_total WHERE trackId = ?",
            arrayOf<Any?>(id),
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                app.winters.octo.listening.PlayTotalEntity(
                    c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), if (c.isNull(5)) null else c.getLong(5),
                    c.getInt(6), if (c.isNull(7)) null else c.getLong(7), c.getString(8), c.getString(9),
                )
            }
        }
        if (counted == stored) null else "$id: stored $stored, counted $counted"
    }
}

// Every library song's id, a page of MERGE_PAGE at a time.
internal suspend fun eachSongPage(db: CatalogDatabase, each: suspend (List<String>) -> Unit) {
    var after = ""
    while (true) {
        val page = db.query("SELECT id FROM track WHERE id > ? ORDER BY id LIMIT ?", arrayOf<Any?>(after, MERGE_PAGE)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (page.isEmpty()) return
        each(page)
        after = page.last()
    }
}

// Two libraries compared table by table, row by row in their keys' order,
// two cursors side by side, so neither is held whole: the oracle of a save
// at proof size (SaveOracle's rules: a table's key is its primary key, or
// its first column; the rowid only where it is the key; `skip`'s tables and
// `carved`'s columns left out). Answers the differences, the first `limit`,
// and the rows compared.
internal object StreamedOracle {
    class Result(val differences: List<String>, val rows: Long, val tables: Int)

    fun compare(expected: SupportSQLiteDatabase, actual: SupportSQLiteDatabase, skip: Set<String>, carved: Map<String, Set<String>>, limit: Int = 30): Result {
        fun tables(sql: SupportSQLiteDatabase) = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filter { it !in skip && it !in setOf("android_metadata", "room_master_table") && (it == "sqlite_sequence" || !it.startsWith("sqlite_")) }
        val lines = ArrayList<String>()
        val e = tables(expected)
        val a = tables(actual)
        (e - a.toSet()).forEach { lines += "$it: missing from the actual library" }
        (a - e.toSet()).forEach { lines += "$it: only in the actual library" }
        var rows = 0L
        for (table in e.filter { it in a }) {
            rows += compareTable(expected, actual, table, carved[table].orEmpty(), lines, limit)
            if (lines.size >= limit) break
        }
        return Result(lines.take(limit), rows, e.size)
    }

    private fun compareTable(expected: SupportSQLiteDatabase, actual: SupportSQLiteDatabase, table: String, left: Set<String>, lines: MutableList<String>, limit: Int): Long {
        val info = expected.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val pk = c.getColumnIndexOrThrow("pk")
            buildList { while (c.moveToNext()) add(c.getString(name) to c.getInt(pk)) }
        }
        val key = info.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }.ifEmpty { listOf(info.first().first) }
        val columns = info.map { it.first }.filter { it !in key && it !in left }
        val select = "SELECT ${(key + columns).joinToString { "`$it`" }} FROM `$table` ORDER BY ${key.joinToString { "`$it` COLLATE BINARY" }}"
        var rows = 0L
        expected.query(select).use { ec ->
            actual.query(select).use { ac ->
                var eNext = ec.moveToNext()
                var aNext = ac.moveToNext()
                while (eNext || aNext) {
                    if (lines.size >= limit) return rows
                    val order = when {
                        !aNext -> -1
                        !eNext -> 1
                        else -> compareKeys(ec, ac, key.size)
                    }
                    when {
                        order < 0 -> {
                            lines += "$table $key=${values(ec, 0, key.size)}: missing, expected ${values(ec, key.size, ec.columnCount)}"
                            eNext = ec.moveToNext()
                        }
                        order > 0 -> {
                            lines += "$table $key=${values(ac, 0, key.size)}: not expected, was ${values(ac, key.size, ac.columnCount)}"
                            aNext = ac.moveToNext()
                        }
                        else -> {
                            val differ = (key.size until ec.columnCount).filter { valueOf(ec, it) != valueOf(ac, it) }
                            if (differ.isNotEmpty()) {
                                lines += "$table $key=${values(ec, 0, key.size)}: " + differ.joinToString { "${columns[it - key.size]} expected ${valueOf(ec, it)} but was ${valueOf(ac, it)}" }
                            }
                            rows++
                            eNext = ec.moveToNext()
                            aNext = ac.moveToNext()
                        }
                    }
                }
            }
        }
        return rows
    }

    private fun values(c: Cursor, from: Int, until: Int) = (from until until).map { valueOf(c, it) }

    private fun valueOf(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
        else -> c.getString(i)
    }

    // Equal keys, the common case, are told by equality alone.
    private fun compareKeys(e: Cursor, a: Cursor, n: Int): Int {
        for (i in 0 until n) {
            val x = valueOf(e, i)
            val y = valueOf(a, i)
            if (x == y) continue
            return compareValue(x, y)
        }
        return 0
    }

    // SQLite's order: NULL, then numbers, then text by its UTF-8 bytes
    // (BINARY), then blobs.
    @Suppress("UNCHECKED_CAST")
    private fun compareValue(x: Any?, y: Any?): Int {
        fun rank(v: Any?) = when (v) {
            null -> 0
            is Long, is Double -> 1
            is String -> 2
            else -> 3
        }
        val rx = rank(x)
        val ry = rank(y)
        if (rx != ry) return rx.compareTo(ry)
        return when (x) {
            null -> 0
            is Long -> if (y is Long) x.compareTo(y) else x.toDouble().compareTo(y as Double)
            is Double -> x.compareTo((y as Number).toDouble())
            is String -> compareBytes(x.toByteArray().asList(), (y as String).toByteArray().asList())
            else -> compareBytes(x as List<Byte>, y as List<Byte>)
        }
    }

    private fun compareBytes(x: List<Byte>, y: List<Byte>): Int {
        for (i in 0 until minOf(x.size, y.size)) {
            val order = (x[i].toInt() and 0xff).compareTo(y[i].toInt() and 0xff)
            if (order != 0) return order
        }
        return x.size.compareTo(y.size)
    }
}
