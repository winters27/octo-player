package app.winters.octo.catalog

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.CountingOpenHelperFactory
import app.winters.octo.testing.testContext
import app.winters.octo.testing.testMerge
import java.io.File
import java.nio.file.Files

// The oracle a save is measured by: every table of a library, row by row by
// its primary key, so two libraries compare equal when every row with the
// same key holds the same values, whatever order the rows went in. A
// rowid is compared only where it is the table's INTEGER PRIMARY KEY (then
// it is the key); anywhere else it is no reader's (RowidBlindTest), and a
// write split into groups can move it.
//
// Left out of the compare: the work rows a merge clears after it (the
// merge's output, the stage, the save's own rows), and the carve-outs, each
// kept beside the image for a test to assert on its own: the swap count
// (once a swap today, once a group for a save), the apply record (gone once
// a save is in), the account the play totals were counted for (the
// rebuild's), and the renames a save publishes (today's swap has none).
internal object SaveOracle {
    // Tables of work rows, cleared after the merge that wrote them.
    val CLEARED = setOf(
        "merge_candidate", "merged_id_next", "album_next", "artist_next",
        "stage_track", "stage_album", "stage_artist",
        "save_found", "save_ref", "save_node", "save_set",
    )

    // Columns kept out of the compare, each table's.
    val CARVED: Map<String, Set<String>> = mapOf(
        "merge_state" to setOf("swapCount", "applyClaim", "applySkipKey", "applyAccount", "applyFirstCopy", "applyScan", "applyStopped", "totalsAccount"),
    )

    // Tables kept out of the compare whole.
    val CARVED_TABLES = setOf("save_rename")

    // Tables no app statement writes: Room's own and SQLite's own, but for
    // the counters of AUTOINCREMENT keys, which are compared by table name.
    private val SKIPPED = setOf("android_metadata", "room_master_table")

    fun image(sql: SupportSQLiteDatabase, cleared: Set<String> = CLEARED, carved: Map<String, Set<String>> = CARVED, carvedTables: Set<String> = CARVED_TABLES): DbImage {
        val names = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filter { it !in SKIPPED && (it == "sqlite_sequence" || !it.startsWith("sqlite_")) }
        val tables = LinkedHashMap<String, TableImage>()
        val apart = LinkedHashMap<String, TableImage>()
        for (name in names) {
            if (name in cleared) continue
            val table = tableImage(sql, name, carved[name].orEmpty())
            if (name in carvedTables) apart[name] = table else tables[name] = table
            carved[name]?.let { cols -> apart["$name (carved)"] = tableImage(sql, name, emptySet(), only = cols) }
        }
        return DbImage(tables, apart)
    }

    // A table's rows by key; `only` keeps those columns (and the key) alone.
    private fun tableImage(sql: SupportSQLiteDatabase, table: String, left: Set<String>, only: Set<String>? = null): TableImage {
        val info = sql.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val pk = c.getColumnIndexOrThrow("pk")
            buildList { while (c.moveToNext()) add(c.getString(name) to c.getInt(pk)) }
        }
        val key = info.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }.ifEmpty { listOf(info.first().first) }
        val columns = info.map { it.first }.filter { it !in key && it !in left && (only == null || it in only) }
        val select = (key + columns).joinToString { "`$it`" }
        val rows = LinkedHashMap<List<Any?>, List<Any?>>()
        sql.query("SELECT $select FROM `$table`").use { c ->
            while (c.moveToNext()) {
                val values = (0 until c.columnCount).map { i -> valueAt(c, i) }
                check(rows.put(values.take(key.size), values.drop(key.size)) == null) { "$table holds the key ${values.take(key.size)} twice" }
            }
        }
        return TableImage(key, columns, rows)
    }

    private fun valueAt(c: android.database.Cursor, i: Int): Any? = when (c.getType(i)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
        else -> c.getString(i)
    }

    // Every way two images differ, a line each, the first `limit` of them.
    fun differences(expected: DbImage, actual: DbImage, limit: Int = 40): List<String> {
        val lines = ArrayList<String>()
        for (name in (expected.tables.keys + actual.tables.keys).distinct()) {
            val e = expected.tables[name]
            val a = actual.tables[name]
            if (e == null || a == null) {
                lines += "$name: ${if (e == null) "only in the actual library" else "missing from the actual library"}"
                continue
            }
            if (e.columns != a.columns) lines += "$name: columns ${e.columns} against ${a.columns}"
            for ((key, row) in e.rows) {
                val other = a.rows[key]
                when {
                    other == null -> lines += "$name ${e.key}=$key: missing, expected $row"
                    other != row -> lines += "$name ${e.key}=$key: " + e.columns.indices.filter { row[it] != other[it] }
                        .joinToString { "${e.columns[it]} expected ${row[it]} but was ${other[it]}" }
                }
            }
            for ((key, row) in a.rows) if (key !in e.rows) lines += "$name ${e.key}=$key: not expected, was $row"
            if (lines.size > limit) break
        }
        return lines.take(limit)
    }

    // Each table's rows that differ between two images by key: added,
    // removed, and changed (with the columns that changed).
    fun changes(before: DbImage, after: DbImage): Map<String, TableChanges> =
        (before.tables.keys + after.tables.keys).distinct().associateWith { name ->
            val b = before.tables[name]?.rows.orEmpty()
            val a = after.tables[name]?.rows.orEmpty()
            val columns = (after.tables[name] ?: before.tables.getValue(name)).columns
            TableChanges(
                columns = columns,
                added = a.filterKeys { it !in b },
                removed = b.filterKeys { it !in a },
                changed = a.filter { (k, row) -> b[k] != null && b[k] != row }.mapValues { (k, row) ->
                    val old = b.getValue(k)
                    columns.indices.filter { row[it] != old[it] }.associate { columns[it] to (old[it] to row[it]) }
                },
            )
        }.filterValues { it.added.isNotEmpty() || it.removed.isNotEmpty() || it.changed.isNotEmpty() }
}

// A library's tables by name, each row by its key, and the carve-outs
// (carved columns, and whole tables) beside them.
internal class DbImage(val tables: Map<String, TableImage>, val carved: Map<String, TableImage>)

internal class TableImage(val key: List<String>, val columns: List<String>, val rows: Map<List<Any?>, List<Any?>>)

// A table's rows two images differ in, by key; a changed row by its columns,
// each with its value before and after.
internal class TableChanges(
    val columns: List<String>,
    val added: Map<List<Any?>, List<Any?>>,
    val removed: Map<List<Any?>, List<Any?>>,
    val changed: Map<List<Any?>, Map<String, Pair<Any?, Any?>>>,
)

// A copy of a library's file (checkpointed, with its log), opened as the
// app opens one but with no open cleanup, so its work rows stay as they
// were: to run two saves from the same start.
internal fun copyCatalog(db: CatalogDatabase, checkpoint: Boolean = true): CatalogDatabase {
    val sql = db.openHelper.writableDatabase
    if (checkpoint) sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    val from = File(sql.path!!)
    val dir = Files.createTempDirectory("catalog-copy").toFile()
    from.copyTo(File(dir, CATALOG_FILE))
    File(from.path + "-wal").takeIf { it.exists() }?.copyTo(File(dir, "$CATALOG_FILE-wal"))
    return Room.databaseBuilder(testContext(), CatalogDatabase::class.java, File(dir, CATALOG_FILE).path)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(MIGRATION_18_19)
        .openHelperFactory(CountingOpenHelperFactory())
        .allowMainThreadQueries()
        .build()
}

// A real merge of the sources as they are, for a test's starting library,
// with the clears it leaves running in the background (its work rows, a
// copy's stage) done, so none of them takes rows a later build writes.
internal suspend fun rebuildSettled(db: CatalogDatabase, server: String?) {
    val merge = db.testMerge(server)
    merge.rebuild()
    merge.awaitClear(MERGE_WORK_CLEAR)
    db.workDao().metas().forEach { merge.awaitStageClear(it.sourceId) }
}

// What a merge does up to its save, for a test: claims every copy waiting
// for a source in the library, as a merge's claim does (the take-ins it
// carries are the test's to stage), and builds the merge's output. Answers
// the claim as the swap takes it and as the find takes it.
internal suspend fun claimAndBuild(db: CatalogDatabase, server: String?): Pair<TodayClaim, SaveClaim> {
    val work = db.workDao()
    val metas = work.metas().associateBy { it.sourceId }
    val ready = metas.values.filter { it.stage == STAGE_READY && inLibrary(it.sourceId, server) }
    val live = HashSet<String>()
    db.query("SELECT DISTINCT sourceId FROM source_track UNION SELECT DISTINCT sourceId FROM source_album", null).use { c ->
        while (c.moveToNext()) live += c.getString(0)
    }
    val ids = (live + ready.map { it.sourceId }).filter { inLibrary(it, server) }.distinct().sortedBy { if (it == DEVICE) "" else it }
    db.withTransaction {
        ready.forEach { work.setStage(it.sourceId, STAGE_CLAIMED) }
        work.setClaimActive(true)
    }
    val staged = ready.associate { it.sourceId to it.generation }
    CatalogMerger(db, work) { MergeHooks() }.merge(ids.mapIndexed { rank, id -> MergeSource(id, rank, id in staged) })
    return TodayClaim(ids, staged, server.orEmpty()) to SaveClaim(ids, staged)
}
