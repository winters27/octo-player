package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// A shelves read still running as a test's body ends (the totals moved just
// as the last look left) ends before the body returns, so it never reads on
// into the test closing its database.
@RunWith(RobolectricTestRunner::class)
class ShelvesStopTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aShelvesReadRunningAsTheBodyEndsEndsBeforeItReturns() {
        val reading = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        timed {
            db.fillPlays(seed = 131, songs = 300, albums = 10)
            val rig = TotalsRig(db, db.testMerge(HOME)).started()
            val hold = AtomicBoolean(false)
            rig.history.hooks = object : PlayWalkHooks() {
                override suspend fun page(stream: PlayStream) {
                    if (!hold.getAndSet(false)) return
                    reading.countDown()
                    // A statement still running: nothing suspends, so no
                    // cancel ends it.
                    Thread.sleep(1_000)
                    ended.set(true)
                }
            }
            coroutineScope {
                val look = launch { rig.history.shelves.collect {} }
                rig.history.shelves.first()
                hold.set(true)
                val song = "t00123"
                repeat(40) { db.playOn(song, NOW + it) }
                rig.totals.refresh(listOf(song))
                assertTrue("a shelves read started", withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
                look.cancel()
            }
        }
        assertTrue("the shelves read ran on past the test's body", ended.get())
    }
}
