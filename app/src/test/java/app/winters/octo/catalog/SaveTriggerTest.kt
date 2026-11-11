package app.winters.octo.catalog

import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.sql.Connection

// The rename triggers (SaveTriggers.kt), table by table as the schema
// guard's column list names the columns they follow (SCHEMA_COLUMNS): a row
// inserted with an old id, and a row whose id is changed to one, land on
// the new id under every conflict clause a write can name; each table's
// guard keeps a row where it was; a rename by key reaches only a row with
// its key, and never a table that follows renames by follow alone; and a
// trigger's own update fires the table's trigger once more and stops. The
// same core runs on the app's database as Room opens it and on SQLite
// 3.21 with recursive triggers on.
@RunWith(RobolectricTestRunner::class)
class SaveTriggerTest {
    @Test
    fun theTriggersMoveRowsOnTheAppsDatabase() {
        val db = openTestCatalog()
        val w = db.openHelper.writableDatabase
        // Every case runs in one transaction, so every statement and every
        // read of total_changes() is on the one connection. A case that
        // fails may have ended it (OR ROLLBACK), so the end after a failure
        // is let go, and the failure is what the test reports.
        w.beginTransaction()
        var ended = false
        try {
            val engine = RoomEngine(w)
            // Room turns recursive triggers on for the connection that writes.
            assertEquals(1L, engine.long("PRAGMA recursive_triggers"))
            core(engine)
            w.setTransactionSuccessful()
            w.endTransaction()
            ended = true
        } finally {
            if (!ended) runCatching { w.endTransaction() }
            db.close()
        }
    }

    @Test
    fun theTriggersMoveRowsOnSqlite321() {
        OldSqlite.open().use { conn ->
            val engine = JdbcEngine(conn)
            engine.exec("PRAGMA recursive_triggers = 1")
            assertEquals(1L, engine.long("PRAGMA recursive_triggers"))
            core(engine)
        }
    }

    // Each trigger's condition and statement, planned on their own with the
    // written row's values as arguments, on SQLite 3.21 and the current
    // SQLite, both with the triggers installed: every table is read through
    // a key, nothing is sorted or built in memory, and on 3.21 no program
    // walks a whole table.
    @Test
    fun everyTriggerReadsByKeyOnBothEngines() {
        val problems = mutableListOf<String>()
        OldSqlite.open().use { conn ->
            val installed = conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use { it.next(); it.getInt(1) }
            }
            assertEquals(SAVE_RENAME_TRIGGERS.size, installed)
            for (target in SAVE_RENAME_TARGETS) {
                for (sql in listOf("SELECT ${target.condition { "?" }}", target.body { "?" })) {
                    val args = List(sql.count { it == '?' }) { null }
                    val old = OldSqlite.plan(conn, sql, args)
                    val current = CurrentSqlite.plan(sql, args)
                    (old + current).filter { line ->
                        "TEMP B-TREE" in line || "AUTOMATIC" in line || "LIST SUBQUERY" in line ||
                            (line.startsWith("SCAN") && "USING" !in line && "CONSTANT ROW" !in line)
                    }.forEach { problems += "${target.table}: $it in $sql" }
                    // The rename is read through its key on both, in either
                    // way a plan names a table.
                    if (old.none(RENAME_BY_KEY::containsMatchIn)) problems += "${target.table}: 3.21 reads no rename by key: $old"
                    if (current.none(RENAME_BY_KEY::containsMatchIn)) problems += "${target.table}: the current SQLite reads no rename by key: $current"
                    OldSqlite.opcodes(conn, sql, args).filter { it in setOf("Rewind", "Last", "OpenEphemeral", "SorterOpen") }
                        .forEach { problems += "${target.table}: $it in $sql" }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The tables the schema guard classes as followed by a trigger, each
    // with its trigger's target.
    private fun followed(): List<RenameTarget> {
        val columns = SCHEMA_COLUMNS.flatMap { (table, cols) -> cols.filterValues { it == IdClass.Trigger }.keys.map { table to it } }
        assertEquals(18, columns.size)
        return columns.map { (table, column) -> SAVE_RENAME_TARGETS.single { it.table == table && it.column == column } }
    }

    private fun core(e: Engine) {
        e.exec("INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('p', 'P', 1, 1)")
        val cases = Cases(e)
        for (target in followed()) {
            for (clause in CLAUSES) {
                cases.insertLands(target, clause)
                cases.updateLands(target, clause)
                cases.guardHolds(target, clause)
            }
            cases.keyRule(target)
            cases.noIdStays(target)
        }
        cases.recursionStops()
    }

    private class Cases(private val e: Engine) {
        private var n = 0

        private fun fresh(): Int = ++n

        // A rename of a target's kind (an album's, for a pin).
        private fun rename(t: RenameTarget, old: String, new: String, byKey: Boolean = false, key: String = "") =
            e.exec("INSERT INTO save_rename (kind, old, new, byKey, `key`) VALUES (?, ?, ?, ?, ?)", listOf(t.kind ?: RENAME_ALBUM, old, new, if (byKey) 1L else 0L, key))

        // A row of the target's table holding `id`, `relinkKey` where it
        // keeps one, the values its condition and guard read, and in every
        // other column a value no other row has.
        private fun row(t: RenameTarget, id: String?, relinkKey: String = "k", fixed: Map<String, Any?> = emptyMap()): Map<String, Any?> {
            val at = fresh()
            val only = t.only
            return e.columns(t.table).withIndex().associate { (i, c) ->
                c.name to when {
                    c.name == t.column -> id
                    c.name in fixed -> fixed[c.name]
                    c.name == "relinkKey" -> relinkKey
                    only != null && c.name == only.first -> only.second.toLong()
                    t.kind == null && c.name == "kind" -> RENAME_ALBUM
                    t.table == "playlist_item" && c.name == "playlistId" -> "p"
                    t.guard == RenameGuard.InList && (c.name == "queueId" || c.name == "listId") -> 1L
                    c.type.startsWith("INTEGER") -> at * 1_000L + i
                    c.type == "REAL" -> at.toDouble()
                    c.type == "BLOB" -> byteArrayOf(at.toByte(), i.toByte())
                    else -> "v$at-${c.name}"
                }
            }
        }

        private fun insert(t: RenameTarget, clause: String, values: Map<String, Any?>): Long {
            val names = values.keys.toList()
            return e.insert(
                "INSERT OR $clause INTO `${t.table}` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})",
                names.map { values[it] },
            )
        }

        private fun idAt(t: RenameTarget, rowid: Long): String? = e.text("SELECT `${t.column}` FROM `${t.table}` WHERE rowid = ?", listOf(rowid))

        private fun setId(t: RenameTarget, clause: String, rowid: Long, id: String) =
            e.exec("UPDATE OR $clause `${t.table}` SET `${t.column}` = ? WHERE rowid = ?", listOf(id, rowid))

        fun insertLands(t: RenameTarget, clause: String) {
            val k = fresh()
            rename(t, "old$k", "new$k")
            val rowid = insert(t, clause, row(t, "old$k"))
            assertEquals("${t.table}, INSERT OR $clause", "new$k", idAt(t, rowid))
        }

        fun updateLands(t: RenameTarget, clause: String) {
            val k = fresh()
            rename(t, "old$k", "new$k")
            val rowid = insert(t, "ABORT", row(t, "other$k"))
            assertEquals("${t.table}: a row with no rename stays", "other$k", idAt(t, rowid))
            setId(t, clause, rowid, "old$k")
            assertEquals("${t.table}, UPDATE OR $clause", "new$k", idAt(t, rowid))
        }

        // A write the guard should keep from conflicting: one that fails
        // instead (its trigger's update met the new id under the write's own
        // clause) fails the case.
        private fun guarded(t: RenameTarget, clause: String, values: Map<String, Any?>): Long = try {
            insert(t, clause, values)
        } catch (e: Exception) {
            throw AssertionError("${t.table}, OR $clause: the write failed where its trigger met the new id: ${e.message}", e)
        }

        // A row the table's guard or condition keeps stays on its old id,
        // and the row already on the new id stays as it was, whatever the
        // write's conflict clause.
        fun guardHolds(t: RenameTarget, clause: String) {
            val k = fresh()
            rename(t, "old$k", "new$k")
            when (t.guard) {
                RenameGuard.None -> Unit
                RenameGuard.Taken -> {
                    val held = row(t, "new$k")
                    val first = insert(t, "ABORT", held)
                    val second = guarded(t, clause, row(t, "old$k"))
                    assertEquals("${t.table}, OR $clause: the new id is held, so the row stays", "old$k", idAt(t, second))
                    assertEquals("${t.table}, OR $clause: the row on the new id is kept", "new$k", idAt(t, first))
                    assertEquals(1L, e.long("SELECT COUNT(*) FROM `${t.table}` WHERE `${t.column}` = ?", listOf("new$k")))
                    val other = held.keys.first { it != t.column && it != "relinkKey" && it != "kind" }
                    assertEquals("${t.table}: the held row's own values", held[other].toString(), e.text("SELECT `$other` FROM `${t.table}` WHERE rowid = ?", listOf(first)))
                }
                RenameGuard.InList -> {
                    insert(t, "ABORT", row(t, "new$k"))
                    val same = guarded(t, clause, row(t, "old$k"))
                    assertEquals("${t.table}, OR $clause: the list holds the new id, so the row stays", "old$k", idAt(t, same))
                    val elsewhere = insert(t, clause, row(t, "old$k", fixed = mapOf("listId" to 2L)))
                    assertEquals("${t.table}, OR $clause: another list's row moves", "new$k", idAt(t, elsewhere))
                }
            }
            t.only?.let { (name, value) ->
                val kept = insert(t, clause, row(t, "old$k", fixed = mapOf(name to (1L - value))))
                assertEquals("${t.table}, OR $clause: $name ${1 - value} stays", "old$k", idAt(t, kept))
            }
        }

        // A rename by key reaches a row with its key and no other; a rename
        // by follow reaches every row; a table that takes renames by follow
        // alone keeps every row a rename by key names. Inserts and updates
        // alike.
        fun keyRule(t: RenameTarget) {
            val k = fresh()
            // Each case its own old id, so no case's new id is held by another's row.
            for (case in listOf("a", "b", "c", "d")) rename(t, "$case$k", "${case}New$k", byKey = true, key = "k1")
            rename(t, "f$k", "fNew$k")
            if (t.followOnly) {
                val rowid = insert(t, "ABORT", row(t, "a$k"))
                assertEquals("${t.table} follows renames by follow alone", "a$k", idAt(t, rowid))
                val changed = insert(t, "ABORT", row(t, "x$k"))
                setId(t, "ABORT", changed, "b$k")
                assertEquals("${t.table} follows renames by follow alone, updated", "b$k", idAt(t, changed))
                return
            }
            assertTrue("${t.table} keeps a relink key", t.keyed)
            val otherKey = insert(t, "ABORT", row(t, "a$k", relinkKey = "k2"))
            assertEquals("${t.table}: a rename by key leaves a row with another key", "a$k", idAt(t, otherKey))
            val otherUpdated = insert(t, "ABORT", row(t, "x$k", relinkKey = "k2"))
            setId(t, "ABORT", otherUpdated, "b$k")
            assertEquals("${t.table}: and one updated to its old id", "b$k", idAt(t, otherUpdated))
            val sameKey = insert(t, "ABORT", row(t, "c$k", relinkKey = "k1"))
            assertEquals("${t.table}: a rename by key moves a row with its key", "cNew$k", idAt(t, sameKey))
            val sameUpdated = insert(t, "ABORT", row(t, "y$k", relinkKey = "k1"))
            setId(t, "ABORT", sameUpdated, "d$k")
            assertEquals("${t.table}: and one updated to its old id", "dNew$k", idAt(t, sameUpdated))
            val followed = insert(t, "ABORT", row(t, "f$k", relinkKey = "k2"))
            assertEquals("${t.table}: a rename by follow moves any row", "fNew$k", idAt(t, followed))
        }

        // A row with no id where the column may have none stays without one.
        fun noIdStays(t: RenameTarget) {
            val nullable = e.columns(t.table).single { it.name == t.column }.notNull.not()
            if (!nullable) return
            val rowid = insert(t, "ABORT", row(t, null))
            assertEquals(null, idAt(t, rowid))
        }

        // With recursive triggers on, a trigger's own update fires the
        // table's update trigger again: a row whose new id were renamed in
        // turn would follow (no save makes such a chain, since a rename's new
        // id is final), and once no rename is left the triggers stop. Each
        // count is every row the insert and its triggers changed, against an
        // insert no trigger moves.
        fun recursionStops() {
            val plays = SAVE_RENAME_TARGETS.single { it.table == "play_event" }
            rename(plays, "x", "y")
            rename(plays, "y", "z")
            rename(plays, "solo", "soloNew")
            fun changes(id: String): Pair<Long, String?> {
                val before = e.long("SELECT total_changes()")
                val rowid = insert(plays, "ABORT", row(plays, id))
                return e.long("SELECT total_changes()") - before to idAt(plays, rowid)
            }
            val (plain, stayed) = changes("plain")
            assertEquals("plain", stayed)
            assertEquals("one move", plain + 1 to "soloNew", changes("solo"))
            assertEquals("a move, then the move it led to, then none", plain + 2 to "z", changes("x"))
        }
    }

    private class Column(val name: String, val type: String, val notNull: Boolean)

    // One SQLite to run the cases on.
    private interface Engine {
        fun exec(sql: String, args: List<Any?> = emptyList())

        // Runs an insert and answers the new row's rowid.
        fun insert(sql: String, args: List<Any?>): Long

        fun text(sql: String, args: List<Any?> = emptyList()): String?

        fun long(sql: String, args: List<Any?> = emptyList()): Long

        fun columns(table: String): List<Column>
    }

    private class RoomEngine(private val db: SupportSQLiteDatabase) : Engine {
        private val known = HashMap<String, List<Column>>()

        override fun exec(sql: String, args: List<Any?>) = db.execSQL(sql, args.toTypedArray())

        override fun insert(sql: String, args: List<Any?>): Long = db.compileStatement(sql).use { s ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> s.bindNull(i + 1)
                    is Long -> s.bindLong(i + 1, a)
                    is Double -> s.bindDouble(i + 1, a)
                    is ByteArray -> s.bindBlob(i + 1, a)
                    else -> s.bindString(i + 1, a.toString())
                }
            }
            s.executeInsert()
        }

        override fun text(sql: String, args: List<Any?>): String? =
            db.query(sql, args.toTypedArray()).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

        override fun long(sql: String, args: List<Any?>): Long = db.query(sql, args.toTypedArray()).use { c -> c.moveToFirst(); c.getLong(0) }

        override fun columns(table: String): List<Column> = known.getOrPut(table) {
            db.query("PRAGMA table_info(`$table`)").use { c ->
                buildList { while (c.moveToNext()) add(Column(c.getString(1), c.getString(2), c.getInt(3) == 1)) }
            }
        }
    }

    private class JdbcEngine(private val conn: Connection) : Engine {
        private val known = HashMap<String, List<Column>>()

        private fun <T> prepared(sql: String, args: List<Any?>, run: (java.sql.PreparedStatement) -> T): T =
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                run(st)
            }

        override fun exec(sql: String, args: List<Any?>) {
            prepared(sql, args) { it.execute() }
        }

        override fun insert(sql: String, args: List<Any?>): Long {
            prepared(sql, args) { it.executeUpdate() }
            return long("SELECT last_insert_rowid()")
        }

        override fun text(sql: String, args: List<Any?>): String? = prepared(sql, args) { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

        override fun long(sql: String, args: List<Any?>): Long = prepared(sql, args) { st -> st.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }

        override fun columns(table: String): List<Column> = known.getOrPut(table) {
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    buildList { while (rs.next()) add(Column(rs.getString(2), rs.getString(3), rs.getInt(4) == 1)) }
                }
            }
        }
    }

    private companion object {
        // Every conflict clause a write can name.
        val CLAUSES = listOf("ROLLBACK", "ABORT", "FAIL", "IGNORE", "REPLACE")

        // A plan line reading save_rename by its key, as SQLite 3.21 and
        // later versions write it.
        val RENAME_BY_KEY = Regex("""^SEARCH (TABLE save_rename AS r|r) USING INDEX sqlite_autoindex_save_rename_1 \(kind=\? AND old=\?\)""")
    }
}
