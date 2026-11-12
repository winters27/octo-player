package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A copy dropped after every song was placed: the merge says it is no
// longer saving before the copy's rows are deleted (after the merge, a
// page at a time), so nothing reads as saving while they go.
@RunWith(RobolectricTestRunner::class)
class AbortNotSavingTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()
    private val events = CopyOnWriteArrayList<String>()
    private lateinit var merge: CatalogMerge

    @After
    fun close() {
        db.close()
    }

    // Notes each delete of a copy's rows with what the merge said then, and
    // can make the copy fail the swap's check.
    private inner class Watched(private val failCheck: Boolean) : WorkDao by db.workDao() {
        private val inner = db.workDao()

        override suspend fun deleteStageTrackPage(sourceId: String, limit: Int): Int {
            events += "delete ${merge.stageProgress.value?.saving}"
            return inner.deleteStageTrackPage(sourceId, limit)
        }

        override suspend fun stageTrackCount(sourceId: String): Int = inner.stageTrackCount(sourceId) + if (failCheck) 1 else 0
    }

    private fun abortAfterPlaced(space: Long, failCheck: Boolean) = runBlocking {
        withTimeout(10_000) {
            db.put(library(DEVICE, 4))
            merge = CatalogMerge(db, { space }, { server }, Watched(failCheck))
            merge.hooks = object : MergeHooks() {
                override fun progress(progress: StageProgress?) {
                    events += "progress ${progress?.saving}"
                }
            }
            db.stage(library(server, 30), generation = 2)
            merge.rebuild()
            try {
                merge.awaitStage(server, 2)
                fail("the copy should not be swapped in")
            } catch (e: StageAborted) {
                // dropped
            }
            merge.awaitStageClear(server)
            val saved = events.indexOf("progress true")
            assertTrue("never saving: $events", saved >= 0)
            val deleted = events.indexOfFirst { it.startsWith("delete") }
            assertTrue("no delete after saving: $events", deleted > saved)
            assertEquals("still saving while the copy was deleted: $events", "delete null", events[deleted])
            assertEquals("progress null", events[deleted - 1])
        }
    }

    // No room for the swap, found once every song is placed.
    @Test
    fun noRoomAfterPlacingSaysNotSavingFirst() = abortAfterPlaced(space = 10, failCheck = false)

    // The copy changed under the claim, found in the swap.
    @Test
    fun aFailedCheckAfterPlacingSaysNotSavingFirst() = abortAfterPlaced(space = Long.MAX_VALUE, failCheck = true)
}
