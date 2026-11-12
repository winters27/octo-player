package app.winters.octo.ui.library.health

import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.data.serverSongIds
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.Counted
import app.winters.octo.testing.CurrentSqlite
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Split albums read a page at a time. Fix all counts a page of albums from
// three reads, their songs, the songs' rows and their copies, and comes to
// exactly what reading each album on its own does. A song's line is found
// by the song, the one line a walk of every line finds for it, in a
// statement or two, none reading more than a row or two.
@RunWith(RobolectricTestRunner::class)
class SplitPagesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun <T> recorded(block: suspend () -> T): Pair<T, List<Counted>> {
        val (out, ran) = db.counted.record { runBlocking { block() } }
        return out to ran.filterNot { it.bookkeeping }
    }

    // Fix all's count read album by album, as before: each album on its
    // own, its songs' copies, its join.
    private suspend fun albumByAlbum(gen: Long, source: String): Pair<Int, Int> {
        val sets = db.read("SELECT setId FROM health_split WHERE owner = '${HealthBuild.OWNER}' AND gen = ? ORDER BY k1, k2, leadAlbum", gen) { it.getLong(0) }
        var count = 0
        var total = 0
        for (setId in sets) {
            val album = splitAlbumOf(db, gen, setId) ?: continue
            val ids = serverSongIds(db.sourceDao().copiesInChunks(album.parts.flatMap { part -> part.songs.map { it.id } }), source)
            val (_, steps) = joinPlanOf(album, TrackHealth(), ids) ?: continue
            count++
            total += steps.size
        }
        return count to total
    }

    @Test
    fun fixAllCountsAPageOfAlbumsFromThreeReads() = timed(300) {
        db.healthLibrary(albums = 300, seed = 11)
        val rig = HealthRig(db, page = 9, cache = 16).keys()
        rig.built()
        val gen = db.readLong("SELECT gen FROM health_run WHERE id = 0")
        val albums = db.readLong("SELECT COUNT(*) FROM health_split WHERE owner = '${HealthBuild.OWNER}' AND gen = ?", gen).toInt()
        assertTrue("$albums split albums", albums > 8)
        for (source in listOf("server:h", "device")) {
            val (want, wantSteps) = albumByAlbum(gen, source)
            assertTrue("albums to join on $source", want > 0)
            assertEquals(source, FixAllCount(gen, source, false, want, wantSteps), rig.plans.count(HealthCheck.SplitAlbums, source, edit = false))
            // Pages of four albums, each read in three statements besides
            // the page's own.
            val walk = FixWalk(db, rig.build, HealthCheck.SplitAlbums, gen, source, edit = false, page = 4)
            var count = 0
            var steps = 0
            var pages = 0
            while (true) {
                val (items, ran) = recorded { walk.next() }
                items ?: break
                pages++
                count += items.size
                steps += items.sumOf { it.set.steps.size }
                assertTrue("page $pages ran nothing", ran.isNotEmpty())
                assertTrue("page $pages read its albums' songs ${ran.count { "FROM health_split_song" in it.sql }} times: $ran", ran.count { "FROM health_split_song" in it.sql } <= 1)
                assertTrue("page $pages read songs ${ran.count { "FROM track" in it.sql }} times: $ran", ran.count { "FROM track" in it.sql } <= 1)
                assertTrue("page $pages read copies ${ran.count { "FROM source_track" in it.sql }} times: $ran", ran.count { "FROM source_track" in it.sql } <= 1)
                assertTrue("page $pages ran ${ran.size} statements: $ran", ran.size <= 4)
            }
            assertTrue("$pages pages", pages >= 3)
            assertEquals(source, want to wantSteps, count to steps)
        }
    }

    @Test
    fun aSongsLineIsFoundByTheSong() = timed(240) {
        db.healthLibrary(albums = 120, seed = 7)
        val rig = HealthRig(db, page = 9, cache = 16).keys()
        rig.built()
        val lines = rig.page(HealthCheck.SplitAlbums, "device", size = 11)
        val loader = rig.build.lines(HealthCheck.SplitAlbums, "device")
        val songs = lines.mapNotNull { line -> (line.row as? HealthRow.Song)?.let { it.track.id to line.key } }
        assertTrue("${songs.size} songs", songs.size > 10)
        for ((id, key) in songs) {
            val (found, ran) = recorded { loader.keyOf(id) }
            assertEquals(id, songs.first { it.first == id }.second, found)
            assertTrue("keyOf($id) ran nothing", ran.isNotEmpty())
            assertTrue("keyOf($id) ran ${ran.size} statements: $ran", ran.size <= 3)
            assertTrue("keyOf($id) read ${ran.maxOf { it.rowsRead }} rows in one statement: $ran", ran.all { it.rowsRead <= 2 })
        }
        assertNull(loader.keyOf("no such song"))
        // A song of the report gone from the library has no line.
        val gone = songs[2].first
        db.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf<Any?>(gone))
        assertNull(loader.keyOf(gone))
    }

    // The song's line is looked up on the songs' index, on SQLite 3.21 and
    // on current SQLite.
    @Test
    fun aSongsLineIsLookedUpOnItsIndex() {
        val args = listOf<Any?>(HealthBuild.OWNER, 1L, "t1")
        val lines = OldSqlite.open().use { conn -> OldSqlite.plan(conn, SPLIT_LINE_OF_SQL, args) } + CurrentSqlite.plan(SPLIT_LINE_OF_SQL, args)
        assertTrue("$lines", lines.any { "index_health_split_song_track" in it })
        assertTrue("$lines", lines.none { "TEMP B-TREE" in it || "AUTOMATIC" in it || (it.trimStart().startsWith("SCAN") && "USING" !in it) })
    }
}
