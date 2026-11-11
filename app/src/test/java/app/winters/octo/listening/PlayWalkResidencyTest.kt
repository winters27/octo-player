package app.winters.octo.listening

import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Home's shelves (most played, recently played and the rediscovery
// shelves, which Home's three lists map from) and History's lists hold one
// page plus a constant at any size: on 20k plays, no statement's cursor,
// counted at the database, yields more than a page, through the first
// rebuild of the totals, the first shelves and History. Today's shelves
// read every played song and every album at once.
@RunWith(RobolectricTestRunner::class)
class PlayWalkResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun plays(): Long = db.longOf("SELECT COUNT(*) FROM play_event")

    @Test
    fun theShelvesHoldAPageAtAnySize() = timed(60) {
        db.fillPlays(seed = 21, songs = 12_000, albums = 600)
        assertTrue("${plays()} plays", plays() >= 20_000)
        db.counted.reset()
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val shelves = rig.shelves()
        val most = db.counted.maxRead!!
        assertTrue("$most", most.rowsRead <= PLAY_PAGE)
        assertTrue(shelves.mostPlayed.size == PLAY_SHELF && shelves.recentlyPlayed.size == PLAY_SHELF)
    }

    @Test
    fun historysListsHoldTheirLengthAtAnySize() = timed(60) {
        db.fillPlays(seed = 22, songs = 12_000, albums = 600)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        db.counted.reset()
        rig.history.mostPlayed(NOW - 182 * DAY, 100).first()
        value(rig.history.mostPlayed(null, 100).first())
        rig.history.recentPlays(HISTORY_RECENT).first()
        val most = db.counted.maxRead!!
        assertTrue("$most", most.rowsRead <= maxOf(PLAY_PAGE, HISTORY_RECENT))
    }
}
