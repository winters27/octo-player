package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

// With the phone's copy claimed beside the server's, how far the merge is
// follows the server's copy, so the server copy's ring reaches Add and
// Save.
@RunWith(RobolectricTestRunner::class)
class ProgressFollowsServerStageTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() {
        db.close()
    }

    @Test
    fun theServersCopyIsTheOneFollowed() = runBlocking {
        withTimeout(10_000) {
            db.put(library(DEVICE, 4))
            db.put(library(server, 4, msShift = 100))
            val seen = Collections.synchronizedList(mutableListOf<StageProgress?>())
            val merge = db.testMerge(server)
            merge.rebuild()
            merge.hooks = object : MergeHooks() {
                override fun progress(progress: StageProgress?) {
                    seen += progress
                }
            }
            // The phone's copy is first in the claim.
            db.stage(library(DEVICE, 6), generation = 1)
            db.stage(library(server, 1_200, msShift = 100), generation = 2)
            assertEquals(DEVICE, db.workDao().metas().first().sourceId)
            merge.rebuild()
            merge.awaitStage(DEVICE, 1)
            merge.awaitStage(server, 2)
            val claimed = seen.filterNotNull()
            assertEquals(setOf(server), claimed.map { it.sourceId }.toSet())
            assertEquals(2L, claimed.first().generation)
            assertEquals(1_200, claimed.first().of)
            // Add: songs placed page by page, up to every one of them.
            assertTrue(claimed.any { !it.saving && it.placed in 1 until 1_200 })
            // Save: every song placed, then how much of the save is in, group
            // by group, never moving back.
            val saving = claimed.filter { it.saving }
            assertTrue(saving.isNotEmpty())
            assertTrue(saving.all { it.placed == 1_200 })
            assertNull(saving.first().saved)
            val parts = saving.mapNotNull { it.saved }
            assertTrue("parts $parts", parts.isNotEmpty() && parts.zipWithNext().all { (a, b) -> b >= a })
            assertNull(seen.last())
        }
    }
}
