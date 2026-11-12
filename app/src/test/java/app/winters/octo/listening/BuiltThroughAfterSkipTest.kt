package app.winters.octo.listening

import app.winters.octo.catalog.HoldKind
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A skipped merge never strands a reader: one that moved no row moves the
// swap stamp but not the swap count, and the totals are built through it
// without a walk; one that moved a row does so in a group, which raises the
// count and keeps the totals of what it touched, so the totals are built
// through it without a walk too. A reader waiting from each new stamp
// finishes.
@RunWith(RobolectricTestRunner::class)
class BuiltThroughAfterSkipTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun everyStampMoveIsAnswered() = timed {
        val merge = db.mergedLibrary(30)
        // Under its song's key, so the first skipped merge has nothing to do.
        db.playOn("device:s1", NOW, "drake|song 1")
        val walks = AtomicInteger()
        val rig = TotalsRig(db, merge)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                walks.incrementAndGet()
            }
        }
        rig.started()
        rig.totals.builtThrough.first { it >= 2 }
        val afterStart = walks.get()
        coroutineScope {
            // Nothing to relink: the stamp moves, the count stays.
            val count = db.workDao().swapCount()
            merge.rebuild()
            assertEquals(4L, merge.swapStamp.value)
            assertEquals(count, db.workDao().swapCount())
            val reader = async { rig.totals.read(HoldKind.Read) { rig.dao.totalOf("device:s1")?.plays } }
            assertEquals(1, reader.await())
            assertEquals(4L, rig.totals.builtThrough.first { it >= 4 })
            assertEquals(afterStart, walks.get())

            // A like on a song id that went: its relink moves a row.
            db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('gone', 'drake|song 2', 1)")
            merge.rebuild()
            assertEquals(6L, merge.swapStamp.value)
            assertEquals(count + 1, db.workDao().swapCount())
            val next = async { rig.totals.read(HoldKind.Read) { rig.dao.totalOf("device:s1")?.plays } }
            assertEquals(1, next.await())
            rig.totals.builtThrough.first { it >= 6 }
            assertEquals(afterStart, walks.get())
        }
    }
}
