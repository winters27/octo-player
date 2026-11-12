package app.winters.octo.ui.library.health

import app.winters.octo.catalog.read
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.Counted
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.Keyed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Split albums' lines are found by each album's place in them, which the
// build writes: the line at a place, a line's place and the lines before a
// line each seek the album, then read within it, wherever the line is,
// counted at the database, and each answers what a walk from the top
// does. Once a swap lands after the build (a song of the report gone), the
// places no longer count the lines the page shows, and every read walks
// again, as exact as before.
@RunWith(RobolectricTestRunner::class)
class SplitPlacesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun <T> recorded(block: suspend () -> T): Pair<T, List<Counted>> {
        val (out, ran) = db.counted.record { runBlocking { block() } }
        return out to ran.filterNot { it.bookkeeping }
    }

    // A read that seeks: a few statements, none reading more than a row.
    private fun assertSeeks(what: String, ran: List<Counted>) {
        assertTrue("$what ran nothing", ran.isNotEmpty())
        assertTrue("$what ran ${ran.size} statements: $ran", ran.size <= 6)
        assertTrue("$what read ${ran.maxOf { it.rowsRead }} rows in one statement: ${ran.maxBy { it.rowsRead }}", ran.all { it.rowsRead <= 1 })
    }

    @Test
    fun eachLineIsFoundByItsAlbumsPlace() = timed(240) {
        db.healthLibrary(albums = 120, seed = 7)
        val rig = HealthRig(db, page = 9, cache = 16).keys()
        rig.built()
        val lines = rig.page(HealthCheck.SplitAlbums, "device", size = 11)
        val headings = lines.count { it.row is HealthRow.Split }
        assertTrue("$headings split albums in ${lines.size} lines", headings >= 4 && lines.size > 2 * headings)
        val loader = rig.build.lines(HealthCheck.SplitAlbums, "device")
        assertEquals(lines.size, loader.count())
        for (at in lines.indices) {
            val (key, ran) = recorded { loader.keyAt(at) }
            assertEquals("line $at", lines[at].key, key)
            assertSeeks("keyAt($at)", ran)
            // A survey counts the lines too, each count one row.
            val (place, placed) = recorded { loader.survey(lines[at].key).anchorAt }
            assertEquals("line $at", at, place)
            assertTrue("the place of line $at ran nothing", placed.isNotEmpty())
            assertTrue("the place of line $at: ${placed.maxBy { it.rowsRead }}", placed.all { it.rowsRead <= 1 })
        }
        assertNull(loader.keyAt(lines.size))
        // The lines before a line, nearest first, read on from `limit` up.
        for (at in listOf(lines.size - 1, lines.size / 2, 3, 0)) {
            val (before, ran) = recorded { loader.before(lines[at].key, 5) }
            assertEquals("before line $at", lines.subList(maxOf(0, at - 5), at).reversed().map(Keyed<HealthRow>::key), before.map { it.key })
            if (at > 0) assertTrue("before line $at read ${ran.size} statements", ran.size <= 40)
        }
    }

    @Test
    fun aSwapAfterTheBuildMakesEveryReadWalkAgain() = timed(240) {
        db.healthLibrary(albums = 120, seed = 7)
        val rig = HealthRig(db, page = 9, cache = 16).keys()
        rig.built()
        // A song of a split album leaves the library in a swap, before the
        // next report: the page leaves it out, and the places would not.
        val gone = db.read("SELECT trackId FROM health_split_song ORDER BY setId, part LIMIT 1 OFFSET 2") { it.getString(0) }.single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf<Any?>(gone))
        db.workDao().raiseSwapCount()
        val lines = rig.page(HealthCheck.SplitAlbums, "device", size = 11)
        assertTrue(lines.none { (it.row as? HealthRow.Song)?.track?.id == gone })
        val loader = rig.build.lines(HealthCheck.SplitAlbums, "device")
        assertEquals(lines.size, loader.count())
        for (at in lines.indices) {
            assertEquals("line $at", lines[at].key, loader.keyAt(at))
            assertEquals("line $at", at, loader.survey(lines[at].key).anchorAt)
        }
        val at = lines.size - 1
        assertEquals(lines.subList(at - 5, at).reversed().map { it.key }, loader.before(lines[at].key, 5).map { it.key })
    }
}
