package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A screen's observer of the library (a Room flow over track) reads again
// at most once a group of a save, never once a row, and at least once.
@RunWith(RobolectricTestRunner::class)
class SaveObserverTest {
    @Test
    fun anObserverReadsAgainAtMostOnceAGroup() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                db.put(library(DEVICE, if (bound == SAVE_GROUP_FLOOR) 30 else 400))
                db.boundMerge(null, bound).rebuild()
                db.put(library(DEVICE, if (bound == SAVE_GROUP_FLOOR) 40 else 500))
                val hooks = GroupHooks()
                val merge = db.boundMerge(null, bound, hooks)
                val seen = java.util.concurrent.CopyOnWriteArrayList<Int>()
                val watching = launch(Dispatchers.Default) { db.dao().libraryTrackCount().collect { seen += it } }
                // The observer has read once before the save.
                withTimeout(10_000) { while (seen.isEmpty()) kotlinx.coroutines.yield() }
                val songs = if (bound == SAVE_GROUP_FLOOR) 40 else 500
                val (_, ran) = db.counted.record {
                    runBlocking {
                        merge.rebuild()
                        // Its last read, after the save's last group.
                        withTimeout(10_000) { while (seen.last() != songs) kotlinx.coroutines.yield() }
                    }
                }
                watching.cancel()
                val reads = ran.count { it.sql == "SELECT COUNT(*) FROM track" && it.label == null }
                val groups = hooks.saves.single()
                assertTrue("bound $bound: $groups groups", groups > 1)
                assertTrue("bound $bound: the observer read $reads times", reads >= 1)
                assertTrue("bound $bound: the observer read $reads times over $groups groups", reads <= groups + 2)
            } finally {
                db.close()
            }
        }
    }
}
