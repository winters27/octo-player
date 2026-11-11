package app.winters.octo.listening

import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A rebuild that crosses a rename counts the song once: the save lands
// after the rebuild read the old id's stored row (judged unchanged) and
// before it reads the new id's plays. The save's group moved the song's
// total to its new id, the rebuild's apply counts each of its songs again,
// so the rebuild ends in one pass with no other after it, and the album's
// plays equal today's.
@RunWith(RobolectricTestRunner::class)
class CrossingRenameAlbumCountTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun phone(vararg natives: String) = SourceCatalog(
        DEVICE,
        true,
        natives.map { song(DEVICE, it, "Song ${it.drop(1)}", "a") },
        listOf(albumRow(DEVICE, "a")),
        listOf(artistRow(DEVICE)),
    )

    private val server = SourceCatalog(
        HOME,
        false,
        listOf(song(HOME, "s0", "Song 0", "a"), song(HOME, "s1", "Song 1", "a")),
        listOf(albumRow(HOME, "a")),
        listOf(artistRow(HOME)),
    )

    // Plays by album as stored, and as today counts them.
    private fun storedByAlbum(): Map<String, Long> = db.query("SELECT albumId, SUM(plays) FROM play_total GROUP BY albumId", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
    }

    @Test
    fun theAlbumCountsTheRenamedSongOnce() = timed(20) {
        db.put(phone("s0", "s1"))
        db.put(server)
        val merge = db.testMerge(HOME)
        merge.rebuild()
        repeat(3) { db.playOn("device:s0", NOW - it, relinkKey = "drake|song 0") }
        db.playOn("device:s1", NOW - 10, relinkKey = "drake|song 1")
        val rig = TotalsRig(db, merge, page = 1)
        val storedLoads = AtomicInteger()
        val opened = AtomicInteger()
        var armed = false
        var crossed = false
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }

            // The second page of stored rows: the old id's row is read and
            // judged; the phone's copy of song 0 goes, so its id becomes
            // the server's.
            override suspend fun page(stream: PlayStream) {
                if (armed && stream == PlayStream.Stored && storedLoads.incrementAndGet() == 2) {
                    db.put(phone("s1"))
                    merge.rebuild()
                    crossed = true
                }
            }
        }
        rig.started()
        assertEquals(mapOf("device:s0" to 3, "device:s1" to 1), db.query("SELECT trackId, plays FROM play_total", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        })
        val before = opened.get()
        armed = true
        val applying = rig.totals.applying.value
        rig.totals.rebuild()
        rig.totals.applying.first { it >= applying + 2 }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        rig.built()
        assertTrue("the save never landed in the rebuild", crossed)
        assertEquals("the rebuild that crossed the rename opened once, and none followed", before + 1, opened.get())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM track WHERE id = 'device:s0'"))
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM track WHERE id = 'server:h:s0'"))
        val today = TodayPlays(db, "home").tracks().groupBy { it.track.albumId }.mapValues { (_, songs) -> songs.sumOf { it.plays.toLong() } }
        assertEquals(today, storedByAlbum())
        assertEquals(4L, storedByAlbum().values.sum())
    }
}
