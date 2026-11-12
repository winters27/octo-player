package app.winters.octo.livelists

import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.device.DEVICE
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.renameSong
import app.winters.octo.discovery.serverReads
import app.winters.octo.discovery.shownIds
import app.winters.octo.health.HealthCheck
import app.winters.octo.subsonic.Song
import app.winters.octo.listening.HOME
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.library.health.HealthRig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A copy is dated by its data, not by when it was made: a live list's
// generation shown at swap count c, copied after a swap renamed one of its
// songs and before the list walked again, answers c, holds the old id, and
// that id follows to the song's new one, so a queue built from the copy
// heals by the count. A health report's copy and a server list's copy are
// dated the same way. Guard: the base had no copies.
@RunWith(RobolectricTestRunner::class)
class StaleGenCopyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun phone(vararg skip: Int) = SourceCatalog(
        DEVICE,
        true,
        (0 until 20).filter { it !in skip }.map { song(DEVICE, "s$it", "Song $it", "a") },
        listOf(albumRow(DEVICE, "a")),
        listOf(artistRow(DEVICE)),
    )

    private val server = SourceCatalog(HOME, false, (0 until 20).map { song(HOME, "s$it", "Song $it", "a") }, listOf(albumRow(HOME, "a")), listOf(artistRow(HOME)))

    @Test
    fun aLiveCopyIsDatedByItsGeneration() = timed(60) {
        db.put(phone(0))
        db.put(server)
        val merge = db.testMerge(HOME).also { it.rebuild() }
        val rig = LiveRig(db, merge, page = 6).started()
        val query = LibraryQuery(sort = QuerySort(SongSort.Title.id))
        coroutineScope {
            val watching = Watching(this, rig.picks, WorkOwners.marked("live:x"), "x", MutableStateFlow(query))
            watching.after(0)
            watching.job.cancel()
        }
        val built = db.longOf("SELECT swapCount FROM live_state WHERE owner = ?", WorkOwners.marked("live:x"))
        assertTrue("server:h:s0" in rig.rows(WorkOwners.marked("live:x")))
        db.put(phone())
        merge.rebuild()
        val now = db.longOf("SELECT swapCount FROM merge_state")
        assertTrue(now > built)
        val copy = rig.picks.copy(WorkOwners.marked("live:x"), query, WorkOwners.marked("queue:1"))
        assertEquals(built, copy.swapCount)
        val rows = rig.rows(WorkOwners.marked("queue:1"))
        assertEquals(20, rows.size)
        assertTrue("server:h:s0" in rows)
        assertEquals("device:s0", db.followAll(rows)["server:h:s0"])
        assertEquals(20, db.followAll(rows).values.filterNotNull().toSet().size)
    }

    // The same for a health report: built at count c, copied for a fill
    // after a swap renamed one of a check's songs, the copy answers c and
    // holds the old id, which follows to the new one.
    @Test
    fun aHealthCopyIsDatedByItsReport() = timed(60) {
        db.put(phone(0))
        db.put(server)
        val merge = db.testMerge(HOME).also { it.rebuild() }
        val rig = HealthRig(db, merge, page = 6).keys()
        val report = rig.built()
        db.put(phone())
        merge.rebuild()
        assertTrue(db.longOf("SELECT swapCount FROM merge_state") > report.swapCount)
        val loader = rig.build.loader(HealthCheck.NoGenre, WorkOwners.marked("queue:2"))
        val copy = loader.copy()
        assertEquals(report.swapCount, copy.swapCount)
        assertEquals(20, copy.count)
        val rows = copiedRows(loader)
        assertTrue("server:h:s0" in rows)
        assertEquals("device:s0", db.followAll(rows)["server:h:s0"])
    }

    // The same for a server list's whole read: read at count c, copied for
    // a fill after a swap renamed one of its songs, the copy answers c, and
    // its rows name the song as it is now (the swap's relinks followed every
    // owner's rows).
    @Test
    fun aServerReadsCopyIsDatedByItsRead() = timed(60) {
        val songs = (0 until 40).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }
        val server = ListServer(songs)
        try {
            db.putLibrary((0 until 40).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
            server.lists["L"] = (0 until 40).map { "srv$it" }
            val reads = db.serverReads(server)
            val read = reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1"))!!
            db.renameSong("t3", "t3-new")
            assertTrue(db.longOf("SELECT swapCount FROM merge_state") > read.swapCount)
            val copy = reads.copy(WorkOwners.marked("vm:1"), WorkOwners.marked("queue:4"))!!
            assertEquals(read.swapCount, copy.swapCount)
            val rows = reads.shownIds(WorkOwners.marked("queue:4"))
            assertEquals(40, rows.size)
            assertTrue("t3-new" in rows && "t3" !in rows)
        } finally {
            server.close()
        }
    }

    private suspend fun copiedRows(loader: app.winters.octo.ui.library.health.HealthLoader): List<String> {
        val all = ArrayList<String>()
        var after: app.winters.octo.ui.paging.RowKey? = null
        while (true) {
            val page = loader.after(after, 7)
            all += page.map { it.row }
            if (page.size < 7) return all
            after = page.last().key
        }
    }
}
