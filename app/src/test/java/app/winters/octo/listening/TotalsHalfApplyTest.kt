package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// No reader sees a half-applied rebuild: while an apply stops after its
// first page, Home's shelves give nothing new (Home keeps the earlier
// ones), and once it ends they give the whole change.
@RunWith(RobolectricTestRunner::class)
class TotalsHalfApplyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theShelvesShowTheEarlierOnesThenTheWholeChange() = timed {
        db.fillPlays(seed = 111, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), page = 1).started()
        coroutineScope {
            val seen = Channel<PlayShelves>(Channel.UNLIMITED)
            val watching = launch { rig.history.shelves.collect { seen.send(value(it)) } }
            val earlier = seen.receive()
            val firstPage = Gate()
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun applied() = firstPage.hit()
            }
            // Thirty songs played thirty times each: the most played change.
            repeat(30) { song -> repeat(30) { db.playOn("t%05d".format(100 + song), NOW + it) } }
            rig.totals.rebuild()
            firstPage.reached.await()
            assertNull(withTimeoutOrNull(300) { seen.receive() })
            firstPage.open()
            val whole = TodayPlays(db, "home").shelves(NOW).ids()
            assertNotEquals(earlier.ids(), whole)
            assertEquals(whole, seen.receive().ids())
            watching.cancel()
        }
    }
}
