package app.winters.octo.catalog

import app.winters.octo.listening.TotalsRig
import app.winters.octo.livelists.DRAFT_KIND
import app.winters.octo.livelists.LIVE_DRAFT_SQL
import app.winters.octo.livelists.LIVE_KIND
import app.winters.octo.livelists.LIVE_SAVED_SQL
import app.winters.octo.playlists.CLEAR_ROWS
import app.winters.octo.playlists.SYNC_OWNER
import app.winters.octo.playlists.clearPlaylistWork
import app.winters.octo.server.StagedCopy
import app.winters.octo.testing.CountedTransaction
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.ui.library.health.HEALTH_CLEAR_ROWS
import app.winters.octo.ui.library.health.HEALTH_UNOWNED_TABLES
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.listening.DIFF_CLEAR_ROWS
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// The library's open writes this launch's mark and flags, and deletes no
// row, however full the work tables are; each work table's own clearer then
// empties it a bounded page a transaction. An older launch's owners are
// invisible to every reader before the sweep takes them, and this launch's
// rows stay.
@RunWith(RobolectricTestRunner::class)
class OpenBoundTest {
    private val source = "server:open.test"

    private fun run(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(120_000) { block() } }

    // The page each clearer deletes in one transaction.
    private fun pageOf(table: String, bound: Long): Int = when (WORK_TABLE_CLEARERS.getValue(table)) {
        WorkClearer.Owners, WorkClearer.Pins -> OWNER_PAGE
        WorkClearer.CopyStart, WorkClearer.Stage, WorkClearer.ScanStart, WorkClearer.MergeWork -> STAGE_CLEAR_ROWS
        WorkClearer.TotalsRebuild -> DIFF_CLEAR_ROWS
        WorkClearer.PlaylistStep -> CLEAR_ROWS
        WorkClearer.HealthBuild -> HEALTH_CLEAR_ROWS
    }

    // `rows` rows into a table, each column a value of its type that differs
    // by row; an owner column takes `owner`'s, a source column `source`.
    private fun fill(db: CatalogDatabase, table: String, rows: Int, from: Int = 1, owner: (Int) -> String) {
        val w = db.openHelper.writableDatabase
        val columns = w.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1) to c.getString(2).uppercase()) } }
        val sql = "INSERT INTO `$table` (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { "?" }})"
        w.beginTransaction()
        try {
            w.compileStatement(sql).use { s ->
                for (i in from until from + rows) {
                    columns.forEachIndexed { at, (name, type) ->
                        val n = at + 1
                        when {
                            name == "owner" -> s.bindString(n, owner(i))
                            name == "sourceId" -> s.bindString(n, source)
                            type.contains("INT") -> s.bindLong(n, i.toLong())
                            type.contains("BLOB") -> s.bindBlob(n, byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte()))
                            type.contains("REAL") -> s.bindDouble(n, i.toDouble())
                            else -> s.bindString(n, "$table.$name.$i")
                        }
                    }
                    s.executeInsert()
                    s.clearBindings()
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun count(db: CatalogDatabase, table: String) = db.longOf("SELECT COUNT(*) FROM `$table`")

    // How many rows a full table holds: three of its clearer's pages and
    // more; the health report's one run row.
    private fun fullOf(table: String, bound: Long): Long = if (table == "health_run") 1 else 3L * pageOf(table, bound) + 7

    // Every work table filled, the owned ones under an older launch's
    // owners, and a copy being built for `source`; then the app's end, and a
    // new launch's open.
    private fun fullLibrary(bound: Long): Pair<File, String> {
        val dir = Files.createTempDirectory("open").toFile()
        val older = LaunchMark.prefix
        openTestCatalog(dir).apply {
            for (table in WORK_TABLE_CLEARERS.keys) fill(this, table, fullOf(table, bound).toInt()) { i -> "${older}vm:$i" }
            exec(
                "INSERT INTO source_meta (sourceId, copyOf, stageOwner, stamp, stage, generation, stagedTracks, stagedAlbums, stagedArtists) VALUES (?, NULL, 'a', 3, ?, 9, 1, 1, 1)",
                source, STAGE_BUILDING,
            )
            close()
        }
        LaunchMark.next()
        return dir to older
    }

    @Test
    fun anOpenWritesFlagsOnlyAndEachClearerEmptiesItsTablesInBoundedPages() = run {
        val bound = SAVE_GROUP_FLOOR
        val (dir, _) = fullLibrary(bound)
        // A token minted before the open carries this launch.
        val owners = WorkOwners()
        val early = owners.vm()
        val db = openTestCatalog(dir)
        try {
            val w = db.openHelper.writableDatabase
            // The rows the open changed, on the connection it wrote on (a
            // transaction runs on that one).
            w.beginTransaction()
            val opened = try {
                w.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
            } finally {
                w.endTransaction()
            }
            assertTrue("the open changed $opened rows", opened in 1..2)
            println("OpenBoundTest the open: $opened rows changed")
            assertEquals(LaunchMark.current, db.longOf("SELECT launchMark FROM merge_state"))
            assertTrue(early.startsWith(LaunchMark.prefix))
            assertTrue(early.startsWith("${db.longOf("SELECT launchMark FROM merge_state")}$LAUNCH_SEPARATOR"))
            for (table in WORK_TABLE_CLEARERS.keys) assertEquals("$table lost rows at the open", fullOf(table, bound), count(db, table))
            assertEquals(STAGE_NONE, db.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", source))
            assertEquals(1L, db.longOf("SELECT stageClearing FROM source_meta WHERE sourceId = ?", source))

            val log = db.counted
            // `kept`: what the clearer's own work leaves of its tables.
            suspend fun cleared(clearers: Set<WorkClearer>, kept: Map<String, Long> = emptyMap(), clear: suspend () -> Unit) {
                val tables = WORK_TABLE_CLEARERS.filterValues { it in clearers }.keys
                log.reset()
                log.recording = true
                clear()
                log.recording = false
                val writes: List<CountedTransaction> = log.transactions.filter { it.totalChanges > 0 }
                assertTrue("$clearers wrote nothing", writes.isNotEmpty())
                for (table in tables) assertEquals("$clearers left rows of $table", kept[table] ?: 0L, count(db, table))
                val page = tables.maxOf { pageOf(it, bound) }
                val most = writes.maxBy { it.totalChanges }
                assertTrue("$clearers: a transaction deleted ${most.totalChanges} rows, over its page $page: $most", most.totalChanges <= page)
                assertTrue("$clearers: ${writes.size} transactions for its tables", writes.size >= 3)
                println("OpenBoundTest $clearers: ${tables.size} tables, ${writes.size} transactions, largest ${most.totalChanges} rows (page $page)")
                // The stage's clear and the merge's work clear cost at most a
                // save group's bound a transaction, measured from what each ran.
                if (clearers == setOf(WorkClearer.Stage) || clearers == setOf(WorkClearer.MergeWork)) {
                    val label = if (clearers == setOf(WorkClearer.Stage)) STAGE_CLEAR else MERGE_WORK_CLEAR
                    val statements = log.statements
                    val all = log.transactions.sortedBy { it.beginSeq }
                    val begins = all.map { it.beginSeq } + Long.MAX_VALUE
                    val measure = Measure(db.openHelper.writableDatabase)
                    val costs = all.withIndex().filter { (_, t) -> t.totalChanges > 0 && t.label == label }.map { (i, _) ->
                        measure.of(statements.filter { it.inTransaction && !it.bookkeeping && it.label == label && it.seq > begins[i] && it.seq < begins[i + 1] })
                    }
                    assertTrue("$clearers: no $label transaction", costs.isNotEmpty())
                    val largest = costs.maxBy { it.total }
                    println("OpenBoundTest $clearers: largest cost ${largest.total} (${largest.reads} reads, ${largest.writes} writes)")
                    assertTrue("$clearers: a transaction costs ${largest.total}, over $SAVE_GROUP_ENTRIES", largest.total <= SAVE_GROUP_ENTRIES)
                }
            }

            val merge = db.boundMerge(null, bound)
            cleared(setOf(WorkClearer.Pins, WorkClearer.Owners)) { PlaylistSets(db).let { it.sweep(); it.idle() } }
            cleared(setOf(WorkClearer.Stage)) { merge.awaitStageClear(source) }
            cleared(setOf(WorkClearer.MergeWork)) {
                merge.rebuild()
                merge.awaitClear(MERGE_WORK_CLEAR)
            }
            cleared(setOf(WorkClearer.CopyStart)) { StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE }).start("a", source) }
            cleared(setOf(WorkClearer.TotalsRebuild)) { TotalsRig(db, merge).started() }
            // The first build of the launch leaves its own run row.
            cleared(setOf(WorkClearer.HealthBuild), kept = mapOf("health_run" to 1L)) {
                HealthBuild(db, merge, WorkOwners(), FreeSpace { Long.MAX_VALUE }, 500, 100).buildNow()
            }
            cleared(setOf(WorkClearer.PlaylistStep)) { clearPlaylistWork(db) }
            cleared(setOf(WorkClearer.ScanStart)) { db.phoneLibrary(PhoneFiles.albums(files = 20, perAlbum = 10), merge).rescan() }
        } finally {
            db.close()
        }
    }

    // The owner sweep: an older launch's rows of every owned table go a page
    // a transaction, this launch's stay.
    @Test
    fun theSweepTakesOlderLaunchesRowsAndKeepsThisLaunchs() = run {
        val (dir, _) = fullLibrary(SAVE_GROUP_FLOOR)
        val db = openTestCatalog(dir)
        try {
            for (table in SWEPT_WORK_TABLES - PIN_TABLE) fill(db, table, 5, from = 100_000) { i -> WorkOwners.marked("vm:keep$i") }
            db.counted.reset()
            db.counted.recording = true
            PlaylistSets(db).let { it.sweep(); it.idle() }
            db.counted.recording = false
            for (table in SWEPT_WORK_TABLES - PIN_TABLE) assertEquals(table, 5L, count(db, table))
            val most = db.counted.transactions.maxOf { it.totalChanges }
            assertTrue("a sweep transaction changed $most rows", most in 1..OWNER_PAGE.toLong())
        } finally {
            db.close()
        }
    }

    // Each reader of an owner's rows by its kind or of the newest read sees
    // only this launch's: a sync step's pin, a saved live list, a live list
    // being made, the newest whole read of a server list, and the health
    // report; each still finds this launch's row, which the sweep leaves.
    @Test
    fun anOlderLaunchsOwnersAreInvisibleToEachReaderBeforeTheSweep() = run {
        val dir = Files.createTempDirectory("open").toFile()
        val older = LaunchMark.prefix
        openTestCatalog(dir).apply {
            exec("INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('p', 'P', 1, 1)")
            exec("INSERT INTO playlist_set_pin (owner, playlistId, itemSet) VALUES (?, 'p', 0)", "${older}sync")
            exec("INSERT INTO live_state VALUES (?, 'l', 1, 'q', 0, 0, 1000, 0)", "${older}live:l")
            exec("INSERT INTO live_state VALUES (?, NULL, 1, 'q', 0, 0, 2000, 0)", "${older}draft:1")
            exec(
                "INSERT INTO server_head (owner, kind, listId, title, readSeq, swapCount, complete) VALUES (?, 'Album', 'a1', 'Old', 500, 0, 1)",
                "${older}vm:1",
            )
            exec("INSERT INTO health_song VALUES (?, 1, 't', 1, x'00', x'00', 'al', 0, 1, x'00')", "${older}report")
            close()
        }
        LaunchMark.next()
        val db = openTestCatalog(dir)
        try {
            val sets = PlaylistSets(db)
            val dao = db.playlistSetDao()
            // Before the sweep, nothing of the older launch answers.
            assertNull("a sync pin of an older launch", dao.pinOf(SYNC_OWNER, "p"))
            assertEquals(emptyList<String>(), saved(db))
            assertNull(draft(db))
            assertNull(db.stringOf(SERVER_NEWEST_SQL, "Album", "a1"))
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_song WHERE owner = ?", HealthBuild.OWNER))
            // This launch's rows answer.
            sets.pin(SYNC_OWNER, "p")
            db.exec("INSERT INTO live_state VALUES (?, 'l', 1, 'q', 0, 0, 1000, 0)", WorkOwners.marked("live:l"))
            db.exec("INSERT INTO live_state VALUES (?, NULL, 1, 'q', 0, 0, 3000, 0)", WorkOwners.marked("draft:2"))
            db.exec("INSERT INTO server_head (owner, kind, listId, title, readSeq, swapCount, complete) VALUES (?, 'Album', 'a1', 'New', 3, 0, 1)", WorkOwners.marked("vm:2"))
            db.exec("INSERT INTO health_song VALUES (?, 1, 't', 1, x'00', x'00', 'al', 0, 1, x'00')", HealthBuild.OWNER)
            assertNotNull(dao.pinOf(SYNC_OWNER, "p"))
            assertEquals(listOf(WorkOwners.marked("live:l")), saved(db))
            assertEquals(WorkOwners.marked("draft:2"), draft(db))
            assertEquals(WorkOwners.marked("vm:2"), db.stringOf(SERVER_NEWEST_SQL, "Album", "a1"))
            // The sweep takes the older launch's and leaves this one's.
            sets.sweep()
            sets.idle()
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))
            assertNotNull(dao.pinOf(SYNC_OWNER, "p"))
            assertEquals(listOf(WorkOwners.marked("live:l"), WorkOwners.marked("draft:2")).sorted(), allOwners(db, "live_state"))
            assertEquals(listOf(WorkOwners.marked("vm:2")), allOwners(db, "server_head"))
            assertEquals(listOf(HealthBuild.OWNER), allOwners(db, "health_song"))
            assertEquals("queue", WorkOwners.kindOf(WorkOwners.marked("queue:5")))
        } finally {
            db.close()
        }
    }

    // The reads by a launch's range of owners seek by an index on both
    // engines: the saved lists and the lists being made by the owner's range,
    // the newest read of a list by its list index.
    @Test
    fun eachLaunchRangeReadSeeksByAnIndex() {
        val conn = OldSqlite.open()
        try {
            val (liveFrom, liveUntil) = WorkOwners.kindRange(LIVE_KIND)
            val (draftFrom, draftUntil) = WorkOwners.kindRange(DRAFT_KIND)
            val reads = listOf(
                Triple("saved lists", LIVE_SAVED_SQL, listOf<Any?>(liveFrom, liveUntil)),
                Triple("lists being made", LIVE_DRAFT_SQL, listOf<Any?>(draftFrom, draftUntil, "q")),
            )
            for ((name, sql, args) in reads) {
                for ((engine, plan) in listOf("3.21" to OldSqlite.plan(conn, sql, args), "current" to CurrentSqlite.plan(sql, args))) {
                    val text = plan.joinToString(" | ")
                    assertTrue("$name on $engine: $text", text.contains("SEARCH") && text.contains("owner>? AND owner<?"))
                    assertFalse("$name on $engine: $text", plan.any { it.startsWith("SCAN") })
                }
            }
            for ((engine, plan) in listOf("3.21" to OldSqlite.plan(conn, SERVER_NEWEST_SQL, listOf("Album", "a1")), "current" to CurrentSqlite.plan(SERVER_NEWEST_SQL, listOf("Album", "a1")))) {
                val text = plan.joinToString(" | ")
                assertTrue("newest read on $engine: $text", plan.first().contains("index_server_head_list"))
                assertFalse("newest read on $engine: $text", plan.first().startsWith("SCAN"))
            }
        } finally {
            conn.close()
        }
    }

    private suspend fun saved(db: CatalogDatabase): List<String> {
        val (from, until) = WorkOwners.kindRange(LIVE_KIND)
        return db.query(LIVE_SAVED_SQL, arrayOf<Any?>(from, until)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    private fun draft(db: CatalogDatabase): String? {
        val (from, until) = WorkOwners.kindRange(DRAFT_KIND)
        return db.stringOf(LIVE_DRAFT_SQL, from, until, "q")
    }

    private fun allOwners(db: CatalogDatabase, table: String): List<String> =
        db.query("SELECT owner FROM `$table` ORDER BY owner", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
}
