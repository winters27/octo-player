package app.winters.octo.offline

import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// The rules walk's last attempt never makes a tap wait for the walk: with
// a swap after every page, 20k liked songs and Keep liked on, the first two
// walks each stop at their second page and the third holds the merge lock
// for its whole walk, but the download store's lock only a page at a time.
// A Remove asked for while that walk writes a page goes in right after it.
// Today's walk held both locks for the whole walk.
@RunWith(RobolectricTestRunner::class)
class RulesThirdAttemptTapTest {
    private val db = openTestCatalog()
    private val outside = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() {
        outside.cancel()
        db.close()
    }

    @Test
    fun aRemoveDuringTheLastWalkWaitsForOnePageAtMost() = timed(120) {
        db.put(library(IN_USE, 2_000))
        val merge = db.testMerge(IN_USE)
        merge.rebuild()
        db.likeAll((0 until 2_000).map { "$IN_USE:s$it" } + (0 until 18_000).map { "$IN_USE:zz%05d".format(it) })
        val tapped = "$IN_USE:s1500"
        db.putKept(listOf(keptRow(tapped, "s1500", setOf(Reasons.MANUAL, Reasons.LIKED), state = DownloadStatus.Done, path = "/m/1500.flac")))
        val rig = RulesRig(db, merge)
        val swaps = AtomicInteger()
        var remove: Job? = null
        val hooks = object : NotedHooks() {
            override suspend fun applied(page: Int, written: Boolean) {
                super.applied(page, written)
                if (!merge.holdsMergeLock()) {
                    if (written) db.growAndSwap(merge, swaps.incrementAndGet())
                } else {
                    events += "inside $page"
                }
            }

            override suspend fun writing(page: Int) {
                if ("opened 3" !in events) return
                if (page == 5) {
                    // A Remove tapped while the page is written: it waits
                    // for the store's lock, which the page holds.
                    remove = outside.launch { rig.rules.unkeep(tapped) { error("Liked songs still keep it") } }
                    delay(100)
                }
                if (page == 6) {
                    val reasons = db.downloadDao().forTrack(tapped).flatMapTo(HashSet()) { it.reasons }
                    events += if (Reasons.MANUAL in reasons) "page 6 before the remove" else "page 6 after the remove"
                }
            }
        }
        rig.rules.hooks = hooks
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
        remove!!.join()

        val events = hooks.events
        assertEquals(listOf("opened 1", "wrote 1", "rolled back 2", "opened 2", "wrote 1", "rolled back 2", "opened 3"), events.take(7))
        assertEquals(2, swaps.get())
        assertTrue(events.toString(), "page 6 after the remove" in events)
        val last = events.drop(7)
        assertEquals("every page of the last walk held the merge lock", last.count { it.startsWith("wrote") }, last.count { it.startsWith("inside") })
        assertEquals(setOf(Reasons.LIKED), db.heldNow()[tapped])
        assertTrue(db.todayPlan(keepLiked = true, playlists = emptySet()).isEmpty)
    }
}
