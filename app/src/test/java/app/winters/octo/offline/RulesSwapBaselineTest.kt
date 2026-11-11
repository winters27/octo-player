package app.winters.octo.offline

import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The rules walk notes the swap count in the transaction that opens it,
// before its first read: a swap after the opening and before the first
// stream page is seen by the first page's write, which writes nothing, and
// the walk opens again and follows the new ids. A count noted in the first
// write would have matched, and that page would have been written from
// reads that crossed the swap.
@RunWith(RobolectricTestRunner::class)
class RulesSwapBaselineTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aSwapBeforeTheFirstReadIsSeenByTheFirstWrite() = timed(60) {
        db.put(library(IN_USE, 300))
        val merge = db.testMerge(IN_USE)
        merge.rebuild()
        db.likeAll((0 until 300).map { "$IN_USE:s$it" })
        val rig = RulesRig(db, merge, page = 100)
        val hooks = object : NotedHooks() {
            override suspend fun opened(attempt: Int) {
                super.opened(attempt)
                if (attempt == 1) db.growAndSwap(merge, 1)
            }
        }
        rig.rules.hooks = hooks
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)

        assertEquals(listOf("opened 1", "rolled back 1", "opened 2", "wrote 1", "wrote 2", "wrote 3"), hooks.events)
        // The song the swap gave the phone's id keeps its like and is on
        // the phone now, so it is not downloaded.
        assertEquals(299, db.heldNow().size)
        assertTrue(db.todayPlan(keepLiked = true, playlists = emptySet()).isEmpty)
    }
}
