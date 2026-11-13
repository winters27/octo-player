package app.winters.octo.testing

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.WriteLabels
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

// Counts what each statement does at the database itself, below Room and
// every DAO: the rows its cursor yields, the rows a write changed and the
// arguments it bound. A raw query and a DAO call are counted alike, so a
// residency test needs no hand-wrapped DAO. Each write transaction is
// timed from its BEGIN to the return of its COMMIT, with the label of the
// writer that ran it (WriteLabels) and how it began.
//
// The largest figures since the last reset are always kept, in constant
// memory; every statement and transaction is kept only while `recording`.
// While recording, each write inside a transaction also counts every row
// it changed, its triggers' included (SQLite's total_changes() before and
// after it, on the transaction's own connection), and every statement and
// BEGIN carries its place in the order they ran.
class CountingOpenHelperFactory(
    private val inner: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    val log = StatementLog()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        CountingOpenHelper(inner.create(configuration), log)
}

// One counting wrapper per database the framework hands out, so every
// caller sees the same one.
class CountingOpenHelper(private val helper: SupportSQLiteOpenHelper, val log: StatementLog) : SupportSQLiteOpenHelper by helper {
    private val wrapped = IdentityHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()

    override val writableDatabase: SupportSQLiteDatabase
        get() = counted(helper.writableDatabase)

    override val readableDatabase: SupportSQLiteDatabase
        get() = counted(helper.readableDatabase)

    private fun counted(db: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrapped) { wrapped.getOrPut(db) { CountingDatabase(db, log) } }
}

// The counting log of a test library opened by openTestCatalog.
val CatalogDatabase.counted: StatementLog
    get() = (openHelper as CountingOpenHelper).log

// One statement as it ran: its SQL, the arguments bound, the rows its cursor
// yielded (moved to, counting each row once) and the rows it changed, the
// writer's label, whether it ran inside a transaction, and its place in the
// order statements and BEGINs ran (`seq`). A write recorded inside a
// transaction also has every row it changed with its triggers' writes
// (`totalChanges`); `rowsChanged` is SQLite's changes(), which leaves those
// out. Null for anything else.
class Counted internal constructor(val sql: String, val boundArgs: Int, val label: String?, val inTransaction: Boolean, val seq: Long) {
    @Volatile var rowsRead: Int = 0
        internal set

    @Volatile var rowsChanged: Int = 0
        internal set

    @Volatile var totalChanges: Long? = null
        internal set

    // Room's own bookkeeping (its table change log and the triggers that
    // write it, its schema checks) rather than the app's statements.
    val bookkeeping: Boolean
        get() = "room_table_modification_log" in sql || "room_table_modification_trigger" in sql || "room_master_table" in sql ||
            sql.startsWith("PRAGMA") || sql == CHANGES_SQL || sql == "SELECT last_insert_rowid()"

    override fun toString() = "read $rowsRead, changed $rowsChanged, bound $boundArgs: $sql"
}

// How a transaction began: beginTransaction's EXCLUSIVE, the IMMEDIATE of
// beginTransactionNonExclusive (Room's begin under write-ahead logging), or
// a read-only begin. The first two ask for the write lock at once. A
// read-only begin is DEFERRED where the framework can open one, and falls
// back to an EXCLUSIVE begin on the primary connection where it cannot (on
// a phone that refuses its reflection), so a read that must never wait for
// the write lock opens no transaction at all.
enum class BeginKind {
    Exclusive,
    Immediate,
    ReadOnly,
    ;

    val asksForWriteLock: Boolean get() = this != ReadOnly
}

// One transaction: who wrote, how it began, on which thread and where its
// BEGIN came in the order statements ran (`beginSeq`), how long the BEGIN
// waited for the write
// lock, how long it then held it to the return of its COMMIT, how many of
// the app's statements and changed rows it held (Room's bookkeeping left
// out), every row its recorded writes changed with their triggers'
// (`totalChanges`), its first statement that changed a row, and whether it
// committed. One with no changed rows wrote nothing of the app's. A writer
// that cannot carry its label into the transaction names it from inside
// (nameOpen).
class CountedTransaction internal constructor(label: String?, val kind: BeginKind, val beginSeq: Long, val thread: String) {
    var label: String? = label
        internal set
    var totalChanges: Long = 0
        internal set
    var firstWrite: String? = null
        internal set
    var waitNanos: Long = 0
        internal set
    var heldNanos: Long = 0
        internal set
    var statements: Int = 0
        internal set
    var rowsChanged: Long = 0
        internal set
    var committed: Boolean = false
        internal set

    // When its BEGIN returned (System.nanoTime); it ended heldNanos later.
    var beganAt: Long = 0
        internal set

    override fun toString() = "${label ?: "unlabeled"}: ${heldNanos / 1_000_000} ms, $statements statements, $rowsChanged rows, " +
        if (committed) "committed" else "rolled back"
}

// Hears the work as it runs, for a test too big to keep every statement: each
// statement as it begins, each write that ran outside any transaction, and
// each transaction as it ends with the statements it ran. A transaction's
// statements are kept, and each write in it counts its triggers' rows
// (`totalChanges`), only while it is open and only when `keeps` takes the
// label it began with, so what is held never grows past one transaction.
interface StatementWatch {
    fun keeps(label: String?): Boolean = false

    fun started(c: Counted) {}

    fun outside(c: Counted) {}

    fun ended(t: CountedTransaction, statements: List<Counted>) {}
}

class StatementLog {
    // Keeps every statement and transaction while true.
    @Volatile var recording = false

    // Told of the work as it runs, when set.
    @Volatile var watch: StatementWatch? = null

    // When set, a write whose SQL it accepts fails before it runs, as it
    // would on a full disk.
    @Volatile var fullDisk: ((String) -> Boolean)? = null

    internal fun refuse(sql: String) {
        if (fullDisk?.invoke(sql) == true) throw SQLiteFullException("database or disk is full")
    }

    private val kept = Collections.synchronizedList(ArrayList<Counted>())
    private val keptTransactions = Collections.synchronizedList(ArrayList<CountedTransaction>())
    private val keptBegins = Collections.synchronizedList(ArrayList<CountedTransaction>())
    private val order = AtomicLong()
    private val lock = Any()
    private var mostRead: Counted? = null
    private var mostChanged: Counted? = null
    private var mostBound: Counted? = null
    private var mostHeld: CountedTransaction? = null
    private var total = 0L
    private val byTable = HashMap<String, Long>()
    private val heldByLabel = HashMap<String?, CountedTransaction>()
    private var mostUnlabeled: CountedTransaction? = null
    private var unlabeledTransactions = 0L
    private var unlabeledStatements = 0L
    private var unlabeledSql: String? = null

    // The transaction each thread has open, for nameOpen.
    internal val opened = ThreadLocal<CountedTransaction?>()

    // Names the transaction the calling thread has open, from inside it,
    // for a writer whose label cannot reach its BEGIN.
    fun nameOpen(label: String) {
        opened.get()?.label = label
    }

    // The longest write of each writer since the last reset, by label (null
    // for writes no writer labeled).
    val longestByLabel: Map<String?, CountedTransaction> get() = synchronized(lock) { HashMap(heldByLabel) }

    // Writes no writer labeled since the last reset: transactions that
    // changed rows of the app's, the longest of them, and statements that
    // changed rows outside any transaction, with the last one's SQL.
    val unlabeled: Unlabeled get() = synchronized(lock) { Unlabeled(unlabeledTransactions, mostUnlabeled, unlabeledStatements, unlabeledSql) }

    class Unlabeled(val transactions: Long, val longest: CountedTransaction?, val statements: Long, val statementSql: String?) {
        val none: Boolean get() = transactions == 0L && statements == 0L

        override fun toString() = "$transactions transactions (longest: $longest, its first write ${longest?.firstWrite?.take(100)}), " +
            "$statements statements outside one (the last ${statementSql?.take(100)})"
    }

    // Rows changed since the last reset, by the table each write names;
    // Room's bookkeeping left out.
    val changedByTable: Map<String, Long> get() = synchronized(lock) { HashMap(byTable) }

    // The statement whose cursor yielded the most rows since the last reset,
    // and likewise for changed rows and bound arguments; Room's bookkeeping
    // is left out of all three.
    val maxRead: Counted? get() = synchronized(lock) { mostRead }
    val maxChanged: Counted? get() = synchronized(lock) { mostChanged }
    val maxBound: Counted? get() = synchronized(lock) { mostBound }

    // The transaction that held the write lock longest since the last reset,
    // among those that changed rows of the app's.
    val longestWrite: CountedTransaction? get() = synchronized(lock) { mostHeld }

    // Statements run since the last reset, bookkeeping included.
    val count: Long get() = synchronized(lock) { total }

    val statements: List<Counted> get() = synchronized(kept) { kept.toList() }

    // The statements of the app, leaving out Room's bookkeeping.
    val own: List<Counted> get() = statements.filterNot { it.bookkeeping }

    val transactions: List<CountedTransaction> get() = synchronized(keptTransactions) { keptTransactions.toList() }

    // Every transaction begun while recording, in the order they began, the
    // ones still open included.
    val begins: List<CountedTransaction> get() = synchronized(keptBegins) { keptBegins.toList() }

    // Where the order has got to: a statement or BEGIN that runs after this
    // call has a larger `seq` (or `beginSeq`) than it answers.
    fun mark(): Long = order.get()

    internal fun nextSeq(): Long = order.incrementAndGet()

    fun reset() {
        synchronized(lock) {
            mostRead = null
            mostChanged = null
            mostBound = null
            mostHeld = null
            total = 0
            byTable.clear()
            heldByLabel.clear()
            mostUnlabeled = null
            unlabeledTransactions = 0
            unlabeledStatements = 0
            unlabeledSql = null
        }
        kept.clear()
        keptTransactions.clear()
        keptBegins.clear()
    }

    // Runs `block` with every statement and transaction kept, and answers
    // what it ran.
    fun <T> record(block: () -> T): Pair<T, List<Counted>> {
        reset()
        recording = true
        try {
            return block() to statements
        } finally {
            recording = false
        }
    }

    internal fun started(c: Counted) {
        synchronized(lock) {
            total++
            if (!c.bookkeeping && (mostBound == null || c.boundArgs > mostBound!!.boundArgs)) mostBound = c
        }
        if (recording) kept += c
    }

    internal fun read(c: Counted, rows: Int) {
        c.rowsRead = rows
        if (c.bookkeeping) return
        synchronized(lock) { if (mostRead == null || rows > mostRead!!.rowsRead) mostRead = c }
    }

    internal fun changed(c: Counted, rows: Int) {
        c.rowsChanged = rows
        if (c.bookkeeping) return
        val table = if (rows > 0) tableOf(c.sql) else null
        synchronized(lock) {
            if (mostChanged == null || rows > mostChanged!!.rowsChanged) mostChanged = c
            if (table != null) byTable[table] = (byTable[table] ?: 0L) + rows
            if (rows > 0 && !c.inTransaction && c.label == null) {
                unlabeledStatements++
                unlabeledSql = c.sql
            }
        }
    }

    internal fun began(t: CountedTransaction) {
        if (recording) keptBegins += t
    }

    internal fun ended(t: CountedTransaction) {
        if (t.rowsChanged > 0) {
            synchronized(lock) {
                if (mostHeld == null || t.heldNanos > mostHeld!!.heldNanos) mostHeld = t
                val held = heldByLabel[t.label]
                if (held == null || t.heldNanos > held.heldNanos) heldByLabel[t.label] = t
                if (t.label == null) {
                    unlabeledTransactions++
                    if (mostUnlabeled == null || t.heldNanos > mostUnlabeled!!.heldNanos) mostUnlabeled = t
                }
            }
        }
        if (recording) keptTransactions += t
    }
}

private const val CHANGES_SQL = "SELECT changes()"

// The table a write names, by its statement's opening words.
private val WRITE_TABLE = Regex("""^\s*(?:INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?|DELETE\s+FROM)\s+[`"]?(\w+)""", RegexOption.IGNORE_CASE)
private val tables = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun tableOf(sql: String): String? = tables[sql] ?: WRITE_TABLE.find(sql)?.groupValues?.get(1)?.also { tables[sql] = it }

// A statement that writes rows, by its first word: its changed rows are
// asked for after it runs. A WITH statement writes when it ends in a write.
private fun writes(sql: String): Boolean {
    val words = sql.trimStart().uppercase()
    return when {
        words.startsWith("INSERT") || words.startsWith("UPDATE") || words.startsWith("DELETE") || words.startsWith("REPLACE") -> true
        words.startsWith("WITH") -> Regex("""\b(INSERT|UPDATE|DELETE|REPLACE)\b""").containsMatchIn(words)
        else -> false
    }
}

// The transaction this thread has open, if any, and how deeply nested, with
// its statements when a watch keeps them.
private class Open(val counted: CountedTransaction, val started: Long, val kept: ArrayList<Counted>?) {
    var depth = 1
    var markedAtTop = false
    var innerFailed = false
    var markedAtDepth = 0
}

private class CountingDatabase(private val db: SupportSQLiteDatabase, private val log: StatementLog) : SupportSQLiteDatabase by db {
    private val open = ThreadLocal<Open?>()

    private fun counted(sql: String, args: Int): Counted {
        log.refuse(sql)
        val t = open.get()
        val c = Counted(sql.trim(), args, WriteLabels.current.get(), t != null, log.nextSeq())
        log.started(c)
        if (t != null && !c.bookkeeping) t.counted.statements++
        t?.kept?.add(c)
        log.watch?.started(c)
        return c
    }

    // Runs a write, and while recording inside a transaction, counts every
    // row it changed, its triggers' included: the difference in SQLite's
    // total_changes(), asked on the transaction's connection, which this
    // thread holds until the transaction ends.
    private fun <T> measured(c: Counted, write: () -> T): T {
        val t = open.get()
        if (t == null || !(log.recording || t.kept != null)) return write()
        val before = totalChanges()
        val result = write()
        val changed = totalChanges() - before
        c.totalChanges = changed
        t.counted.totalChanges += changed
        return result
    }

    private fun totalChanges(): Long = db.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }

    private fun wrote(c: Counted, rows: Int) {
        log.changed(c, rows)
        if (!c.bookkeeping) {
            open.get()?.counted?.let {
                it.rowsChanged += rows
                if (rows > 0 && it.firstWrite == null) it.firstWrite = c.sql
            }
        }
        if (open.get() == null) log.watch?.outside(c)
    }

    // A write given as plain SQL runs as a compiled statement, bound as
    // execSQL binds it, so it answers its changed rows on its own connection
    // (changes() asked afterwards may go to another of the pool's).
    private fun executeWrite(sql: String, bindArgs: Array<out Any?>): Int = db.compileStatement(sql).use { s ->
        bindArgs.forEachIndexed { i, arg ->
            when (arg) {
                null -> s.bindNull(i + 1)
                is ByteArray -> s.bindBlob(i + 1, arg)
                is Double -> s.bindDouble(i + 1, arg)
                is Float -> s.bindDouble(i + 1, arg.toDouble())
                is Long -> s.bindLong(i + 1, arg)
                is Int -> s.bindLong(i + 1, arg.toLong())
                is Short -> s.bindLong(i + 1, arg.toLong())
                is Byte -> s.bindLong(i + 1, arg.toLong())
                is Boolean -> s.bindLong(i + 1, if (arg) 1 else 0)
                else -> s.bindString(i + 1, arg.toString())
            }
        }
        s.executeUpdateDelete()
    }

    override fun query(query: String): Cursor = rows(counted(query, 0)) { db.query(query) }

    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = rows(counted(query, bindArgs.size)) { db.query(query, bindArgs) }

    // The database binds a query's arguments as it opens the cursor, so the
    // statement is counted once they are known.
    override fun query(query: SupportSQLiteQuery): Cursor {
        val binds = CountingQuery(query)
        val cursor = db.query(binds)
        return RowCountingCursor(cursor, counted(query.sql, binds.bound), log)
    }

    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
        val binds = CountingQuery(query)
        val cursor = db.query(binds, cancellationSignal)
        return RowCountingCursor(cursor, counted(query.sql, binds.bound), log)
    }

    private fun rows(c: Counted, opening: () -> Cursor): Cursor = RowCountingCursor(opening(), c, log)

    override fun compileStatement(sql: String): SupportSQLiteStatement = CountingStatement(db.compileStatement(sql), sql)

    override fun execSQL(sql: String) {
        val c = counted(sql, 0)
        if (writes(sql)) wrote(c, measured(c) { executeWrite(sql, emptyArray()) }) else db.execSQL(sql)
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        val c = counted(sql, bindArgs.size)
        if (writes(sql)) wrote(c, measured(c) { executeWrite(sql, bindArgs) }) else db.execSQL(sql, bindArgs)
    }

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
        val c = counted("INSERT INTO $table", values.size())
        return measured(c) { db.insert(table, conflictAlgorithm, values) }.also { if (it != -1L) wrote(c, 1) }
    }

    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int {
        val c = counted("UPDATE $table", values.size() + (whereArgs?.size ?: 0))
        return measured(c) { db.update(table, conflictAlgorithm, values, whereClause, whereArgs) }.also { wrote(c, it) }
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int {
        val c = counted("DELETE FROM $table", whereArgs?.size ?: 0)
        return measured(c) { db.delete(table, whereClause, whereArgs) }.also { wrote(c, it) }
    }

    override fun beginTransaction() = begin(BeginKind.Exclusive) { db.beginTransaction() }

    override fun beginTransactionNonExclusive() = begin(BeginKind.Immediate) { db.beginTransactionNonExclusive() }

    override fun beginTransactionReadOnly() = begin(BeginKind.ReadOnly) { db.beginTransactionReadOnly() }

    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
        begin(BeginKind.Exclusive) { db.beginTransactionWithListener(transactionListener) }

    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
        begin(BeginKind.Immediate) { db.beginTransactionWithListenerNonExclusive(transactionListener) }

    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
        begin(BeginKind.ReadOnly) { db.beginTransactionWithListenerReadOnly(transactionListener) }

    // A nested begin runs no BEGIN of its own, so only the outermost counts.
    private fun begin(kind: BeginKind, start: () -> Unit) {
        val nested = open.get()
        if (nested != null) {
            start()
            nested.depth++
            return
        }
        val seq = log.nextSeq()
        val asked = System.nanoTime()
        start()
        val began = System.nanoTime()
        val counted = CountedTransaction(WriteLabels.current.get(), kind, seq, Thread.currentThread().name).apply {
            waitNanos = began - asked
            beganAt = began
        }
        open.set(Open(counted, began, if (log.watch?.keeps(counted.label) == true) ArrayList() else null))
        log.opened.set(counted)
        log.began(counted)
    }

    override fun setTransactionSuccessful() {
        db.setTransactionSuccessful()
        open.get()?.let { if (it.depth == 1) it.markedAtTop = true else it.markedAtDepth = it.depth }
    }

    override fun endTransaction() {
        val t = open.get()
        if (t == null) {
            db.endTransaction()
            return
        }
        if (t.depth > 1) {
            if (t.markedAtDepth != t.depth) t.innerFailed = true
            t.markedAtDepth = 0
            t.depth--
            db.endTransaction()
            return
        }
        try {
            db.endTransaction()
        } finally {
            open.set(null)
            log.opened.set(null)
            t.counted.heldNanos = System.nanoTime() - t.started
            t.counted.committed = t.markedAtTop && !t.innerFailed
            log.ended(t.counted)
            log.watch?.ended(t.counted, t.kept ?: emptyList())
        }
    }

    // A compiled statement, counted each time it runs, with the arguments
    // bound since it was compiled or last cleared.
    private inner class CountingStatement(private val s: SupportSQLiteStatement, private val sql: String) : SupportSQLiteStatement by s {
        private var bound = 0

        override fun bindNull(index: Int) = s.bindNull(index).also { bound = maxOf(bound, index) }

        override fun bindLong(index: Int, value: Long) = s.bindLong(index, value).also { bound = maxOf(bound, index) }

        override fun bindDouble(index: Int, value: Double) = s.bindDouble(index, value).also { bound = maxOf(bound, index) }

        override fun bindString(index: Int, value: String) = s.bindString(index, value).also { bound = maxOf(bound, index) }

        override fun bindBlob(index: Int, value: ByteArray) = s.bindBlob(index, value).also { bound = maxOf(bound, index) }

        override fun clearBindings() {
            s.clearBindings()
            bound = 0
        }

        // A write runs as executeUpdateDelete, which runs it the same way and
        // also answers its changed rows.
        override fun execute() {
            val c = counted(sql, bound)
            if (writes(sql)) wrote(c, measured(c) { s.executeUpdateDelete() }) else s.execute()
        }

        override fun executeUpdateDelete(): Int {
            val c = counted(sql, bound)
            return measured(c) { s.executeUpdateDelete() }.also { wrote(c, it) }
        }

        override fun executeInsert(): Long {
            val c = counted(sql, bound)
            return measured(c) { s.executeInsert() }.also { if (it != -1L) wrote(c, 1) }
        }

        override fun simpleQueryForLong(): Long {
            val c = counted(sql, bound)
            return s.simpleQueryForLong().also { log.read(c, 1) }
        }

        override fun simpleQueryForString(): String? {
            val c = counted(sql, bound)
            return s.simpleQueryForString().also { log.read(c, 1) }
        }
    }
}

// A query whose bound arguments are counted as the database binds them:
// the highest place bound.
private class CountingQuery(private val q: SupportSQLiteQuery) : SupportSQLiteQuery by q {
    var bound = 0
        private set

    override fun bindTo(statement: SupportSQLiteProgram) {
        q.bindTo(
            object : SupportSQLiteProgram by statement {
                override fun bindNull(index: Int) = statement.bindNull(index).also { bound = maxOf(bound, index) }

                override fun bindLong(index: Int, value: Long) = statement.bindLong(index, value).also { bound = maxOf(bound, index) }

                override fun bindDouble(index: Int, value: Double) = statement.bindDouble(index, value).also { bound = maxOf(bound, index) }

                override fun bindString(index: Int, value: String) = statement.bindString(index, value).also { bound = maxOf(bound, index) }

                override fun bindBlob(index: Int, value: ByteArray) = statement.bindBlob(index, value).also { bound = maxOf(bound, index) }
            },
        )
    }
}

// Counts the rows a cursor yields: the furthest row it moved to.
private class RowCountingCursor(cursor: Cursor, private val c: Counted, private val log: StatementLog) : CursorWrapper(cursor) {
    private var furthest = 0

    private fun seen(moved: Boolean): Boolean {
        if (moved && position + 1 > furthest) {
            furthest = position + 1
            log.read(c, furthest)
        }
        return moved
    }

    override fun moveToNext(): Boolean = seen(super.moveToNext())

    override fun moveToFirst(): Boolean = seen(super.moveToFirst())

    override fun moveToLast(): Boolean = seen(super.moveToLast())

    override fun moveToPosition(position: Int): Boolean = seen(super.moveToPosition(position))

    override fun move(offset: Int): Boolean = seen(super.move(offset))

    override fun moveToPrevious(): Boolean = seen(super.moveToPrevious())
}
