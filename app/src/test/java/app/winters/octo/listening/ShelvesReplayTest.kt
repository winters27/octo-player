package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The car's one look at the shelves never answers old ones: once no one
// has looked for longer than the shelves stay shared, their kept copy
// goes, and the next look reads them afresh.
@RunWith(RobolectricTestRunner::class)
class ShelvesReplayTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aLookAfterSharingStoppedReadsAfresh() = timed {
        db.fillPlays(seed = 121, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        rig.shelves()
        // Past the time the shelves stay shared, with no one looking.
        delay(300)
        val song = "t00123"
        repeat(40) { db.playOn(song, NOW + it) }
        val version = rig.totals.version.value
        rig.totals.refresh(listOf(song))
        rig.totals.version.first { it > version }
        assertEquals(song, rig.shelves().mostPlayed.first().id)
    }
}
