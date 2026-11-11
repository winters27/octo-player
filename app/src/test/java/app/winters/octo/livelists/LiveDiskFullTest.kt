package app.winters.octo.livelists

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.playback.putTracks
import app.winters.octo.playback.radioTrack
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// A live list short of disk keeps what it showed and says so: before a
// build, with how many MB to free ("Not enough space to show this list.");
// when the disk fills on the third page of a build, with "some space"; the
// list keeps running, and the next change builds it whole. A picked song
// takes what LIVE_BYTES_PER_ROW says, measured on 6,000 songs with long
// ids as the file's pages once the log is checkpointed in. Guard: the base
// wrote nothing.
@RunWith(RobolectricTestRunner::class)
class LiveDiskFullTest {
    @Test
    fun aShortDiskKeepsTheListAndSaysSo() = timed(90) {
        val db = openTestCatalog()
        try {
            db.liveLibrary(100)
            var space = 1_000L
            val rig = LiveRig(db, page = 10, space = FreeSpace { space }).started()
            val query = MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.Title.id)))
            coroutineScope {
                val watching = Watching(this, rig.picks, WorkOwners.marked("live:d"), "d", query)
                val short = watching.after(0) as LiveShown.Problem
                assertEquals(null, short.before)
                assertTrue(short.words, short.words.startsWith("Not enough space to show this list. Free up "))
                assertTrue(short.words, short.words.endsWith(" MB, then try again."))
                space = Long.MAX_VALUE
                db.putTracks(listOf(radioTrack("zz1")))
                val shown = watching.until { it is LiveShown.Shown } as LiveShown.Shown
                val rows = rig.rows(WorkOwners.marked("live:d"))
                assertEquals(101, rows.size)

                var inserts = 0
                db.counted.fullDisk = { sql -> sql.startsWith("INSERT INTO live_song") && ++inserts > 20 }
                query.value = LibraryQuery(sort = QuerySort(SongSort.Year.id, true))
                val full = watching.until { it is LiveShown.Problem } as LiveShown.Problem
                assertEquals("Not enough space to show this list. Free up some space, then try again.", full.words)
                assertEquals(shown, full.before)
                assertEquals(rows, rig.rows(WorkOwners.marked("live:d")))
                assertEquals(0L, db.longOf("SELECT COUNT(*) FROM live_song WHERE owner = ? AND gen != ?", WorkOwners.marked("live:d"), shown.gen))
                db.counted.fullDisk = null
                db.putTracks(listOf(radioTrack("zz2")))
                watching.until { it is LiveShown.Shown }
                assertEquals(rig.today(query.value).map { it.id }, rig.rows(WorkOwners.marked("live:d")))
                watching.job.cancel()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun aSongTakesWhatItsConstantSays() = timed(90) {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            val ids = "server:music.home-server.example.net:4533:"
            db.putTracks((0 until 6_000).map { radioTrack(ids + "%032x".format(it), sortKey = "untitled song number %05d".format(it)) })
            val rig = LiveRig(db).started()
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val before = used(dir)
            // Under an owner as the app writes it, the launch's mark first.
            rig.picks.copy(null, LibraryQuery(sort = QuerySort(SongSort.Title.id)), WorkOwners.marked("queue:1"))
            // The rows alone: the log's room is asked beside them.
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val perRow = (used(dir) - before) / 6_000
            println("MEASURED live rows=6000 perRow=$perRow")
            println("RESULT live_bytes_per_row=$perRow")
            assertTrue("measured $perRow, constant $LIVE_BYTES_PER_ROW", perRow <= LIVE_BYTES_PER_ROW)
            assertTrue("measured $perRow, constant $LIVE_BYTES_PER_ROW", LIVE_BYTES_PER_ROW <= perRow * 11 / 10 + 10)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    // A build asks for the room it takes, a little over and never under:
    // its rows once the log is checkpointed in, and the most the log held
    // while it wrote them, from a log emptied first. The ask is as a build
    // with no room says it.
    @Test
    fun aBuildAsksForTheRoomItTakes() = timed(90) {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            val ids = "server:music.home-server.example.net:4533:"
            db.putTracks((0 until 6_000).map { radioTrack(ids + "%032x".format(it), sortKey = "untitled song number %05d".format(it)) })
            val rig = LiveRig(db).started()
            val query = LibraryQuery(sort = QuerySort(SongSort.Title.id))
            val asked = try {
                LiveRig(db, space = FreeSpace { 0 }).started().picks.copy(null, query, WorkOwners.marked("queue:2"))
                error("a build with no room went on")
            } catch (e: NotEnoughSpace) {
                e.neededBytes!!
            }
            val wal = File(db.openHelper.writableDatabase.path + "-wal")
            var log = 0L
            rig.picks.hooks = object : LiveHooks() {
                override suspend fun page(owner: String, n: Int) {
                    log = maxOf(log, wal.length())
                }
            }
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val before = used(dir)
            rig.picks.copy(null, query, WorkOwners.marked("queue:1"))
            log = maxOf(log, wal.length())
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val taken = used(dir) - before + log
            println("MEASURED live build of 6000 rows: asked $asked, took $taken (the log's most $log)")
            assertTrue("asked $asked, took $taken", asked >= taken)
            assertTrue("asked $asked, took $taken", asked <= taken * 135 / 100)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
