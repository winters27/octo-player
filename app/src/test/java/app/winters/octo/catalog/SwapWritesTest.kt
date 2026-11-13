package app.winters.octo.catalog

import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// What a save writes, and when. An everyday copy whose songs moved place
// (one taken out ahead of them) rewrites only the songs that changed, and
// moves the others' places by a statement of their own. A copy's stage
// leaves after the save, a page of rows a transaction. A first copy into an
// empty library goes in groups with every index of the library and the
// source rows kept as the schema has them, in each group and after.
@RunWith(RobolectricTestRunner::class)
class SwapWritesTest {
    private val db = openTestCatalog()
    private val server = "server:swap.test"

    @After
    fun close() = db.close()

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    // Each save's steps, by name, with the rows each wrote over its groups.
    // With `holdClear`, the first stage clear stops after its first page
    // until `nextStaged`, or a second at most, as a clear on a busy phone
    // runs late.
    private class Steps(private val holdClear: Boolean = false) : GroupHooks() {
        val cleared: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val nextStaged = CompletableDeferred<Unit>()
        private val held = AtomicBoolean(false)

        override suspend fun stageClearPage(sourceId: String) {
            if (holdClear && held.compareAndSet(false, true)) withTimeoutOrNull(1_000) { nextStaged.await() }
        }

        override fun stageCleared(sourceId: String, rows: Long, millis: Long) {
            cleared += rows
        }
    }

    // A copy staged as a server copy starts its stage: once the copy swapped
    // in last has left the stage, whose clear takes the source's rows of
    // every generation. `staged` hears that the copy is staged.
    private suspend fun swapIn(merge: CatalogMerge, copy: SourceCatalog, generation: Long, staged: () -> Unit = {}) {
        merge.awaitStageClear(server)
        db.stage(copy, generation)
        staged()
        merge.rebuild()
        merge.awaitStage(server, generation)
    }

    // The first copy's stage clear runs late, and the next copy still keeps
    // every row it was staged with.
    @Test
    fun anEverydayCopyRewritesOnlyTheSongsThatChanged() = run {
        val steps = Steps(holdClear = true)
        val merge = db.testMerge(server, steps)
        val first = library(server, 300)
        swapIn(merge, first, generation = 1)
        val retitled = setOf("$server:s50", "$server:s60", "$server:s70")
        val next = first.copy(
            tracks = first.tracks.drop(10).map { if (it.id in retitled) it.copy(title = it.title + " (Live)") else it },
        )
        swapIn(merge, next, generation = 2) { steps.nextStaged.complete(Unit) }
        val everyday = steps.steps.last()
        assertEquals("$everyday", 3L, everyday["update source_track"])
        assertEquals("$everyday", 290L, everyday["update seq of source_track"])
        assertEquals("$everyday", 10L, everyday["delete source_track"])
        next.tracks.forEachIndexed { i, t ->
            assertEquals(t.id, i.toLong(), db.longOf("SELECT seq FROM source_track WHERE id = ?", t.id))
            assertEquals(t.id, t.title, db.stringOf("SELECT title FROM source_track WHERE id = ?", t.id))
        }
        assertEquals(290L, db.longOf("SELECT COUNT(*) FROM track"))
        assertEquals(3L, db.longOf("SELECT COUNT(*) FROM track WHERE title LIKE '% (Live)'"))
        merge.awaitStageClear(server)
    }

    @Test
    fun aCopysStageLeavesAfterItsSaveAPageAtATime() = run {
        val steps = Steps()
        val merge = db.testMerge(server, steps)
        val copy = library(server, 2 * STAGE_CLEAR_ROWS + 500)
        val staged = (copy.tracks.size + copy.albums.size + copy.artists.size).toLong()
        val (_, ran) = db.counted.record {
            runBlocking {
                swapIn(merge, copy, generation = 1)
                merge.awaitStageClear(server)
            }
        }
        val deletes = ran.filter { it.sql.startsWith("DELETE FROM stage_") && it.rowsChanged > 0 }
        assertTrue("no stage row was deleted", deletes.isNotEmpty())
        assertTrue("a stage delete ran in the save: ${deletes.filter { it.label != STAGE_CLEAR }}", deletes.all { it.label == STAGE_CLEAR })
        assertTrue("${deletes.maxOf { it.rowsChanged }} rows in one delete", deletes.all { it.rowsChanged <= STAGE_CLEAR_ROWS })
        val clears = db.counted.transactions.filter { it.label == STAGE_CLEAR && it.rowsChanged > 0 }
        assertTrue("${clears.size} clear transactions", clears.size >= 3 && clears.all { it.rowsChanged <= STAGE_CLEAR_ROWS })
        assertEquals(staged, clears.sumOf { it.rowsChanged })
        assertEquals(listOf(staged), steps.cleared.toList())
        assertFalse("the save cleared the stage itself: ${steps.steps.single()}", steps.steps.single().keys.any { "stage_" in it })
        for (table in listOf("stage_track", "stage_album", "stage_artist")) assertEquals(table, 0L, db.longOf("SELECT COUNT(*) FROM $table"))
        assertEquals(copy.tracks.size.toLong(), db.longOf("SELECT COUNT(*) FROM track"))
    }

    // Each of a table's indexes as the database keeps it, by name.
    private fun indexesOf(table: String): Map<String, String> =
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL ORDER BY name", arrayOf<Any?>(table)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    // Room's own statements for a table's indexes, as the database keeps
    // them (with no IF NOT EXISTS).
    private fun roomIndexes(table: String): Set<String> = OldSqlite.schemaStatements(19)
        .filter { it.startsWith("CREATE INDEX") && " ON `$table` " in it }
        .map { it.replace("IF NOT EXISTS ", "") }
        .toSet()

    @Test
    fun aFirstCopyKeepsEveryIndexInEveryGroup() = run {
        val tables = listOf("track", "source_track")
        val before = tables.associateWith { indexesOf(it) }
        for (table in tables) {
            assertEquals(roomIndexes(table), before.getValue(table).values.toSet())
            assertTrue("$table has its indexes", before.getValue(table).isNotEmpty())
        }
        val steps = Steps()
        val inGroups = ArrayList<Map<String, Map<String, String>>>()
        steps.inside = { inGroups += tables.associateWith { indexesOf(it) } }
        val merge = db.boundMerge(server, 400, steps)
        swapIn(merge, library(server, 300), generation = 1)
        assertTrue("${inGroups.size} groups", inGroups.size > 1)
        inGroups.forEach { assertEquals("an index went in a group", before, it) }
        assertEquals(before, tables.associateWith { indexesOf(it) })
        assertTrue("no step drops or makes an index: ${steps.steps.single()}", steps.steps.single().keys.none { "index" in it })
        assertEquals("every copy points at its library song", 300L, db.longOf("SELECT COUNT(*) FROM source_track WHERE mergedId != ''"))
        assertEquals(300L, db.longOf("SELECT COUNT(*) FROM track"))
    }
}
