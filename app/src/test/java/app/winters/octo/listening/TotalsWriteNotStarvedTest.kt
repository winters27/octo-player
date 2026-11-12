package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// Holds taken page after page, back to back, cannot keep a write out: a
// waiting write turns every new hold away, and the last hold to leave
// hands the turn to the write before any of them.
@RunWith(RobolectricTestRunner::class)
class TotalsWriteNotStarvedTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // The books, alone: a hold asked for while a write waits behind another
    // hold enters only after the write.
    @Test
    fun aReleaseHandsTheTurnToTheWaitingWrite() = timed {
        val holds = TotalsHolds(MutableStateFlow(0L)) { null }
        val order = Collections.synchronizedList(ArrayList<String>())
        coroutineScope {
            val firstIn = CompletableDeferred<Unit>()
            val firstGo = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) { holds.hold { firstIn.complete(Unit); firstGo.await() } }
            firstIn.await()
            val waits = CompletableDeferred<Unit>()
            holds.writeWaits = { waits.complete(Unit) }
            val write = launch(Dispatchers.Default) { holds.writing(apply = false) { order += "write" } }
            waits.await()
            val second = launch(Dispatchers.Default) { holds.hold { order += "second" } }
            firstGo.complete(Unit)
            write.join()
            second.join()
        }
        assertEquals(listOf("write", "second"), order.toList())
    }

    // Two walks taking a hold for each page, back to back, while a refresh
    // and then a rebuild wait: both land while the walks go on.
    @Test
    fun twoWalksTakingPageHoldsCannotStarveAWrite() = timed(20) {
        db.fillPlays(seed = 71, songs = 300, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val version = rig.totals.version.value
        val going = AtomicBoolean(true)
        coroutineScope {
            val walks = (1..2).map {
                async(Dispatchers.Default) {
                    var pages = 0
                    while (going.get()) {
                        rig.totals.withReadHold { rig.dao.storedPage("", 50) }
                        pages++
                        yield()
                    }
                    pages
                }
            }
            db.playOn("t00003", NOW + 1)
            rig.totals.refresh(listOf("t00003"))
            rig.totals.version.first { it > version }
            db.playOn("t00004", NOW + 1)
            rig.totals.rebuild()
            rig.totals.version.first { it > version + 1 }
            going.set(false)
            walks.forEach { it.await() }
        }
    }

    // Autoplay's songs played lately are one read holding the totals still,
    // taken right before it with no lock after: asked for while a write
    // waits behind another hold, it is turned away, and reads once the
    // write has run, so it sees the write. Guard: the base read every play
    // whole, with no hold.
    @Test
    fun autoplaysRecentPlaysAreOneHeldReadAfterAWaitingWrite() = timed(20) {
        db.fillPlays(seed = 72, songs = 200, albums = 8)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val order = Collections.synchronizedList(ArrayList<String>())
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                order += "write"
            }
        }
        coroutineScope {
            val firstIn = CompletableDeferred<Unit>()
            val firstGo = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) { rig.totals.withReadHold { firstIn.complete(Unit); firstGo.await() } }
            firstIn.await()
            val waits = CompletableDeferred<Unit>()
            rig.totals.holds.writeWaits = { waits.complete(Unit) }
            db.playOn("t00005", NOW + 10)
            rig.totals.refresh(listOf("t00005"))
            waits.await()
            val held = CompletableDeferred<Unit>()
            rig.totals.holds.holdWaits = { held.complete(Unit) }
            val recent = async(Dispatchers.Default) { rig.history.recentlyPlayedIds(50).also { order += "recent" } }
            withTimeoutOrNull(5_000) { held.await() }
            assertEquals("nothing read while the write waits", emptyList<String>(), order.toList())
            firstGo.complete(Unit)
            val ids = recent.await()
            assertEquals(listOf("write", "recent"), order.toList())
            assertEquals("the song just played comes first", "t00005", ids.first())
            assertFalse("no lock is held after the read", rig.merge.holdsMergeLock())
        }
    }
}
