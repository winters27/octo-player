package app.winters.octo.offline

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The rules walk holds the merge lock one page at a time: with 20k liked
// songs and Keep liked on, the walk reads with no lock and takes the lock
// only to write a page. A like and a swap asked for while a page is being
// written each go in right after that page; the swap rolls the next page
// back and the walk starts again, ending within three walks as today's
// rule would leave it. A swap between a page's read and its write rolls
// that page back the same way. A Remove made between a page's read and
// its write keeps the hand-asked reason off. Today's walk held the merge
// lock for the whole walk.
@RunWith(RobolectricTestRunner::class)
class RulesWalkPagedLockTest {
    private val db = openTestCatalog()
    private val outside = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() {
        outside.cancel()
        db.close()
    }

    // 2,000 server songs merged into the library, so swaps keep them; all
    // of them liked, and 18,000 songs the library does not have, whose ids
    // come after theirs, so the library's songs fill the first four pages.
    private suspend fun likedLibrary(): CatalogMerge {
        db.put(library(IN_USE, 2_000))
        val merge = db.testMerge(IN_USE)
        merge.rebuild()
        db.likeAll((0 until 2_000).map { "$IN_USE:s$it" } + (0 until 18_000).map { "$IN_USE:zz%05d".format(it) })
        return merge
    }

    private suspend fun assertSettled() {
        assertTrue("today's rule has more to do", db.todayPlan(keepLiked = true, playlists = emptySet()).isEmpty)
        assertEquals(2_000, db.heldNow().size)
    }

    @Test
    fun aLikeAndASwapAskedForDuringAPageGoInRightAfterIt() = timed(120) {
        val merge = likedLibrary()
        val rig = RulesRig(db, merge)
        var like: Job? = null
        var swap: Job? = null
        val hooks = object : NotedHooks() {
            override suspend fun read(stream: RulesStream) {
                if (!events.contains("opened 3")) assertFalse("a read holds the merge lock", merge.holdsMergeLock())
            }

            override suspend fun writing(page: Int) {
                assertTrue("a page is written outside the merge lock", merge.holdsMergeLock())
                if (page == 5 && like == null) {
                    like = outside.launch {
                        db.userDao().like(LikedTrackEntity("$IN_USE:late", "", 1))
                        events += "liked"
                    }
                    swap = outside.launch {
                        db.growAndSwap(merge, 1)
                        events += "swapped"
                    }
                }
            }

            override suspend fun applied(page: Int, written: Boolean) {
                super.applied(page, written)
                if (page == 5 && written) {
                    // Between pages the walk holds nothing, so both finish.
                    assertFalse(merge.holdsMergeLock())
                    like?.join()
                    swap?.join()
                }
            }
        }
        rig.rules.hooks = hooks
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)

        val events = hooks.events
        val sixth = events.indexOfFirst { it.endsWith(" 6") && !it.startsWith("opened") }
        assertTrue(events.toString(), events.indexOf("liked") in 0 until sixth && events.indexOf("swapped") in 0 until sixth)
        assertEquals("rolled back 6", events[sixth])
        assertTrue(events.toString(), "opened 2" in events && "opened 3" !in events)
        assertSettled()
    }

    @Test
    fun aSwapBetweenAPagesReadAndItsWriteRollsThePageBack() = timed(120) {
        val merge = likedLibrary()
        val rig = RulesRig(db, merge)
        var swapped = false
        val hooks = object : NotedHooks() {
            override suspend fun applying(page: Int) {
                if (page == 5 && !swapped) {
                    swapped = true
                    db.growAndSwap(merge, 1)
                }
            }
        }
        rig.rules.hooks = hooks
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)

        val events = hooks.events
        assertEquals(listOf("opened 1", "wrote 1", "wrote 2", "wrote 3", "wrote 4", "rolled back 5", "opened 2"), events.take(7))
        assertTrue(events.toString(), "opened 3" !in events)
        // The song the swap renamed follows its new id, downloaded once.
        assertEquals(setOf(Reasons.LIKED), db.heldNow()["device:s0"])
        assertSettled()
    }

    // A page held inside its innermost lock while a swap waits for the
    // merge lock and a Remove and a Download wait for the store's lock:
    // once the page goes on, every one of them finishes, and so does the
    // walk.
    @Test
    fun aPagePausedInsideItsLocksHoldsNothingUp() = timed(120) {
        val merge = likedLibrary()
        val rig = RulesRig(db, merge)
        val tapped = "$IN_USE:s1500"
        db.putKept(listOf(keptRow(tapped, "s1500", setOf(Reasons.MANUAL, Reasons.LIKED))))
        val paused = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val waiting = ArrayList<Job>()
        rig.rules.hooks = object : RulesHooks() {
            override suspend fun writing(page: Int) {
                if (page == 3 && !paused.isCompleted) {
                    paused.complete(Unit)
                    go.await()
                }
            }
        }
        val walk = outside.launch { rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null) }
        paused.await()
        waiting += outside.launch { db.growAndSwap(merge, 1) }
        waiting += outside.launch { rig.rules.unkeep(tapped) { error("Liked songs still keep it") } }
        waiting += outside.launch { rig.rules.download(listOf("$IN_USE:s1999"), refused = null) }
        go.complete(Unit)
        waiting.forEach { it.join() }
        walk.join()
        // The swap may land after the walk; the walk after it, as a sync's
        // after-steps run one, follows its new ids.
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
        assertEquals(setOf(Reasons.LIKED, Reasons.MANUAL), db.heldNow()["$IN_USE:s1999"])
        assertEquals(setOf(Reasons.LIKED), db.heldNow()[tapped])
        assertSettled()
    }

    @Test
    fun aRemoveBetweenAPagesReadAndItsWriteKeepsTheHandReasonOff() = timed(30) {
        val x = "$IN_USE:x"
        db.putServerSongs(listOf(x, "$IN_USE:y"))
        db.likeAll(listOf(x))
        db.putPlaylist("p", listOf(x, "$IN_USE:y"))
        db.putKept(listOf(keptRow(x, "n0", setOf(Reasons.MANUAL, Reasons.playlist("p"))), keptRow("$IN_USE:y", "n1", setOf(Reasons.playlist("p")))))
        val rig = RulesRig(db)
        rig.rules.hooks = object : RulesHooks() {
            override suspend fun applying(page: Int) {
                rig.rules.unkeep(x) { error("a playlist still keeps it") }
            }
        }
        rig.rules.walk(keepLiked = true, playlists = setOf("p"), refused = null)
        assertEquals(setOf(Reasons.LIKED, Reasons.playlist("p")), db.heldNow()[x])
    }
}
