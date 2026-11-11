package app.winters.octo.ui.library.health

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A queue's copy of a check keeps the report's generation number under its
// own owner. When a later build publishes and the old report goes, only the
// report's own rows go: each fill's copy stays whole until the fill clears
// it. The base deleted every row of the old generation, the copies' too.
@RunWith(RobolectricTestRunner::class)
class HealthOldReportKeepsCopiesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aNewReportLeavesTheQueuesCopies() = timed(60) {
        db.healthLibrary(albums = 40)
        val rig = HealthRig(db, page = 9).keys()
        val first = rig.built()
        val checks = listOf(HealthCheck.Duplicates, HealthCheck.SplitAlbums, HealthCheck.NoYear)
        val copies = checks.mapIndexed { i, check -> rig.build.loader(check, WorkOwners.marked("queue:${i + 1}")).also { it.copy() } }
        val counts = copies.map { it.count() }
        assertTrue("$counts", counts.all { it > 0 })
        val second = rig.built()
        assertTrue(second.gen > first.gen)
        // The first report's own rows went.
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_song WHERE owner = '${HealthBuild.OWNER}' AND gen = ?", first.gen))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_member WHERE owner = '${HealthBuild.OWNER}' AND gen = ?", first.gen))
        // Each queue's copy is still whole.
        assertEquals(counts, copies.map { it.count() })
        copies.forEach { it.clear() }
        val (from, until) = WorkOwners.kindRange("queue")
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM health_song WHERE owner >= ? AND owner < ?", from, until))
    }
}
