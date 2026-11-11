package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// The library's count of changes to its rows rises once in every group of
// a save, so a walk between two groups sees the count move: a save of many
// groups raises it once each, a claimed merge with nothing to write once (it
// takes one group's turn), a copy with nothing new once and is swapped in, a
// skipped merge once a group of what followed ids and not at all when
// nothing did; and it outlives the app.
@RunWith(RobolectricTestRunner::class)
class SwapCountTest {
    private val server = "server:home.test"
    private val dir: File = createTempDirectory("catalog").toFile()
    private var db = openTestCatalog(dir)

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    private fun swapCount() = db.longOf("SELECT swapCount FROM merge_state WHERE id = 0")

    @Test
    fun itRisesOnceInEveryGroup() = run {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            db.close()
            db = openTestCatalog(createTempDirectory("catalog").toFile())
            db.put(library(DEVICE, 30))
            val hooks = GroupHooks()
            val merge = db.boundMerge(server, bound, hooks)
            // Each group sees the count one past the last group's.
            val inside = ArrayList<Long>()
            hooks.inside = { inside += swapCount() }
            merge.rebuild()
            val groups = hooks.saves.single()
            assertEquals("bound $bound", groups, swapCount())
            assertEquals("bound $bound", (0 until groups).toList(), inside)
            if (bound == SAVE_GROUP_FLOOR) assertTrue("$groups groups", groups > 1)
            // A copy of the same rows, only placed anew: its groups raise it
            // once each.
            db.stage(library(DEVICE, 30), generation = 1)
            merge.rebuild()
            merge.awaitStage(DEVICE, 1)
            assertEquals("bound $bound", groups + hooks.saves.last(), swapCount())
            assertTrue(hooks.saves.last() >= 1)
        }
    }

    // A claimed merge with nothing at all to write still takes one turn,
    // which raises the count once.
    @Test
    fun aClaimWithNothingToWriteRaisesItOnce() = run {
        db.put(library(DEVICE, 4))
        val hooks = GroupHooks()
        val merge = db.boundMerge(server, SAVE_GROUP_ENTRIES, hooks)
        merge.rebuild()
        val before = swapCount()
        // The last merge's key forgotten, as a repair does: a claim, and
        // nothing to write.
        db.workDao().setSkipKey(null)
        val turns = hooks.turns.get()
        merge.rebuild()
        assertEquals(turns, hooks.turns.get())
        assertEquals(1L, hooks.saves.last())
        assertEquals(before + 1, swapCount())
    }

    // A copy with nothing new in it takes its one turn before its last
    // steps mark it swapped in, so the turn finds it still the copy claimed
    // and it is swapped in.
    @Test
    fun aCopyWithNothingNewIsSwappedInInOneTurn() = run {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4))
        val hooks = GroupHooks()
        val merge = db.boundMerge(server, SAVE_GROUP_ENTRIES, hooks)
        merge.rebuild()
        val before = swapCount()
        db.stage(library(server, 4), generation = 3)
        merge.rebuild()
        val failed = runCatching { merge.awaitStage(server, 3) }.exceptionOrNull()
        assertEquals("the copy was not swapped in: $failed", null, failed)
        assertEquals(1L, hooks.saves.last())
        assertEquals(before + 1, swapCount())
    }

    @Test
    fun aSkippedMergeRaisesItOnlyWhenARowFollowed() = run {
        db.put(library(server, 3))
        db.put(library(DEVICE, 3))
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        merge.rebuild()
        assertEquals(1L, swapCount())
        merge.rebuild()
        assertEquals(1, hooks.skips.get())
        assertEquals("a skipped merge that moved nothing", 1L, swapCount())
        // A like written with a server copy's id, which is no library song.
        db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('$server:s0', '', 1)")
        merge.rebuild()
        assertEquals(2, hooks.skips.get())
        assertTrue(db.userDao().isLiked("device:s0"))
        assertEquals("a skipped merge that moved a like", 2L, swapCount())
        merge.rebuild()
        assertEquals(3, hooks.skips.get())
        assertEquals(2L, swapCount())
        assertEquals(1, hooks.merges.get())
    }

    @Test
    fun itOutlivesARestart() = run {
        db.put(library(DEVICE, 4))
        val merge = db.testMerge(server)
        merge.rebuild()
        assertEquals(1L, swapCount())
        // The merge's work rows go after it; the app closes once they have.
        merge.awaitClear(MERGE_WORK_CLEAR)
        db = reopen(db, dir)
        assertEquals(1L, swapCount())
        db.put(library(DEVICE, 5))
        db.testMerge(server).rebuild()
        assertEquals(2L, swapCount())
    }
}
