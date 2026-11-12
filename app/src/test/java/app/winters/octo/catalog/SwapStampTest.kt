package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// The swap stamp is odd only inside a group's transaction: even between
// groups, while the claimed copy's songs are placed and while a copy's rows
// go after the save.
@RunWith(RobolectricTestRunner::class)
class SwapStampTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()
    private val seen = CopyOnWriteArrayList<String>()
    private lateinit var merge: CatalogMerge

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun state() = "stamp ${merge.swapStamp.value % 2}"

    // Notes what the merge says as it raises the swap count and while a
    // copy's rows are deleted.
    private inner class Watched : WorkDao by db.workDao() {
        private val inner = db.workDao()

        override suspend fun deleteStageTrackPage(sourceId: String, limit: Int): Int {
            seen += "delete: ${state()}"
            return inner.deleteStageTrackPage(sourceId, limit)
        }
    }

    private val skips = AtomicInteger()

    private val hooks = object : MergeHooks() {
        override fun skipped() {
            skips.incrementAndGet()
        }

        override suspend fun inGroup(turn: Long, group: Long) {
            seen += "group $turn: ${state()}"
        }

        override suspend fun betweenGroups(turn: Long) {
            seen += "between $turn: ${state()}"
        }

        // Every song placed (the save's progress after each group aside).
        override fun progress(progress: StageProgress?) {
            if (progress?.saving == true && progress.saved == null) seen += "placed: ${state()}"
        }

        override fun applied(turns: Long, rows: Long, millis: Long) {
            seen += "applied $turns ${rows > 0}"
        }
    }

    private fun watchedMerge(space: Long = Long.MAX_VALUE, bound: Long = SAVE_GROUP_ENTRIES) {
        merge = CatalogMerge(db, { space }, { server }, Watched(), bound).also { it.hooks = hooks }
    }

    @Test
    fun aGroupIsOddInsideItsTransactionAndEvenBetween() = run {
        db.put(library(DEVICE, 40))
        watchedMerge(bound = SAVE_GROUP_FLOOR)
        assertEquals(0L, merge.swapStamp.value)
        merge.rebuild()
        val groups = seen.filter { it.startsWith("group") }
        assertTrue("$seen", groups.size > 1)
        assertTrue("$seen", groups.all { it.endsWith("stamp 1") })
        assertTrue("$seen", seen.filter { it.startsWith("between") }.all { it.endsWith("stamp 0") })
        assertEquals("applied ${groups.size} true", seen.last())
        assertEquals(2L * groups.size, merge.swapStamp.value)
    }

    @Test
    fun aServerCopyIsEvenWhileItsSongsArePlaced() = run {
        db.put(library(DEVICE, 4))
        watchedMerge()
        db.stage(library(server, 30, msShift = 100), generation = 2)
        merge.rebuild()
        merge.awaitStage(server, 2)
        val shown = seen.filterNot { it.startsWith("delete") || it.startsWith("between") }
        assertEquals("placed: stamp 0", shown.first())
        val groups = shown.drop(1).dropLast(1)
        assertTrue("$shown", groups.isNotEmpty() && groups.all { it.startsWith("group") && it.endsWith("stamp 1") })
        assertEquals("applied ${groups.size} true", shown.last())
        assertNull(merge.stageProgress.value)
    }

    @Test
    fun aSkippedMergeThatFindsNothingStillMovesTheStamp() = run {
        db.put(library(server, 3))
        db.put(library(DEVICE, 3))
        watchedMerge()
        merge.rebuild()
        seen.clear()
        // Nothing to follow: the stamp moves past an odd value, no group.
        merge.rebuild()
        assertEquals(4L, merge.swapStamp.value)
        assertEquals(listOf("applied 0 false"), seen.toList())
        db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('$server:s0', '', 1)")
        seen.clear()
        merge.rebuild()
        assertEquals(listOf("group 1: stamp 1", "between 1: stamp 0", "applied 1 true"), seen.toList())
        assertEquals(6L, merge.swapStamp.value)
        assertEquals(2, skips.get())
    }

    @Test
    fun aDroppedCopyIsEvenWhileItsRowsGo() = run {
        db.put(library(DEVICE, 4))
        watchedMerge(space = 10)
        db.stage(library(server, 30), generation = 2)
        merge.rebuild()
        merge.awaitStageClear(server)
        assertEquals("placed: stamp 0", seen.first())
        assertEquals("delete: stamp 0", seen.first { it.startsWith("delete") })
    }

    // The phone's copies and the servers' take generations from one count.
    @Test
    fun generationsAreNeverGivenTwice() {
        val merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, db.workDao())
        assertEquals(1L, merge.issueGeneration(null))
        assertEquals(6L, merge.issueGeneration(5))
        assertEquals(7L, merge.issueGeneration(2))
        assertEquals(8L, merge.issueGeneration(null))
    }
}
