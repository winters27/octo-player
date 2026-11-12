package app.winters.octo.listening

import app.winters.octo.catalog.HoldKind
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

// A read hold keeps every write of the totals out, so a read under it
// never sees a half-applied table: an apply waits for a hold in place, and
// a hold asked for while an apply runs waits for its end. Its books stay
// right when waiters give up, and a refresh holds the write turn from its
// check to its commit.
@RunWith(RobolectricTestRunner::class)
class TotalsReadHoldTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun plays(id: String) = db.longOf("SELECT plays FROM play_total WHERE trackId = ?", id)

    @Test
    fun anApplyWaitsForAThirdAttemptsHold() = timed {
        db.fillPlays(seed = 51, songs = 200, albums = 10)
        val merge = db.testMerge(HOME)
        val rig = TotalsRig(db, merge).started()
        val before = plays("t00001")
        val turn = rig.totals.applying.value
        val asking = CompletableDeferred<Unit>()
        rig.totals.holds.writeWaits = { asking.complete(Unit) }
        coroutineScope {
            val inside = CompletableDeferred<Unit>()
            val letGo = CompletableDeferred<Unit>()
            val reader = async {
                rig.totals.withReadHold {
                    merge.betweenRebuilds(HoldKind.Read) {
                        inside.complete(Unit)
                        letGo.await()
                        plays("t00001")
                    }
                }
            }
            inside.await()
            db.playOn("t00001", NOW + 1)
            rig.totals.rebuild()
            asking.await()
            // The apply waits for the write turn, and a new hold waits behind it.
            val later = async { rig.totals.withReadHold { plays("t00001") to rig.totals.applying.value } }
            assertEquals(turn, rig.totals.applying.value)
            assertEquals(before, plays("t00001"))
            letGo.complete(Unit)
            assertEquals(before, reader.await())
            // The new hold came after the whole apply.
            assertEquals(before + 1 to turn + 2, later.await())
        }
    }

    @Test
    fun aHoldAskedBetweenApplyPagesWaitsForTheWholeApply() = timed {
        db.fillPlays(seed = 52, songs = 200, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME), page = 1).started()
        val songs = listOf("t00001", "t00002", "t00003")
        val before = songs.map(::plays)
        val firstPage = Gate()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() = firstPage.hit()
        }
        songs.forEach { db.playOn(it, NOW + 1) }
        rig.totals.rebuild()
        firstPage.reached.await()
        assertEquals(1L, rig.totals.applying.value % 2)
        coroutineScope {
            val hold = async { rig.totals.withReadHold { songs.map(::plays) } }
            firstPage.open()
            assertEquals(before.map { it + 1 }, hold.await())
        }
    }

    // The books, alone: a waiter let in and stopped before it ran leaves no
    // hold behind, so a write enters at once.
    @Test
    fun aWaiterStoppedBetweenItsGrantAndItsTurnLeavesNoHold() = timed {
        val holds = TotalsHolds(MutableStateFlow(0L)) { null }
        val pool = Executors.newSingleThreadExecutor()
        try {
            coroutineScope {
                val writeIn = CompletableDeferred<Unit>()
                val writeGo = CompletableDeferred<Unit>()
                val write = launch(Dispatchers.Default) { holds.writing(apply = false) { writeIn.complete(Unit); writeGo.await() } }
                writeIn.await()
                val asked = CompletableDeferred<Unit>()
                val waiter = launch(pool.asCoroutineDispatcher()) {
                    asked.complete(Unit)
                    holds.hold { throw AssertionError("a stopped waiter never reads") }
                }
                asked.await()
                // The waiter's thread is kept busy, so its grant cannot run it.
                val busy = CountDownLatch(1)
                pool.execute { busy.await() }
                writeGo.complete(Unit)
                write.join()
                waiter.cancel()
                busy.countDown()
                waiter.join()
                withTimeout(2_000) { holds.writing(apply = false) { } }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // A write that gives up while it waits lets in the holds it turned away.
    @Test
    fun aWriteThatGivesUpLetsTheTurnedAwayHoldsIn() = timed {
        val holds = TotalsHolds(MutableStateFlow(0L)) { null }
        coroutineScope {
            val firstIn = CompletableDeferred<Unit>()
            val firstGo = CompletableDeferred<Unit>()
            val first = launch { holds.hold { firstIn.complete(Unit); firstGo.await() } }
            firstIn.await()
            val waits = CompletableDeferred<Unit>()
            holds.writeWaits = { waits.complete(Unit) }
            val write = launch(Dispatchers.Default) { holds.writing(apply = false) { throw AssertionError("the write gave up") } }
            waits.await()
            val second = async { holds.hold { "in" } }
            write.cancel()
            assertEquals("in", second.await())
            assertFalse(first.isCompleted)
            firstGo.complete(Unit)
        }
    }

    // From a refresh's check to its commit no hold enters: one asked for
    // in between reads the refreshed row.
    @Test
    fun noHoldEntersBetweenARefreshsCheckAndItsCommit() = timed {
        db.fillPlays(seed = 53, songs = 200, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val before = plays("t00002")
        val checked = Gate()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                if (write == TotalsWrite.Refresh) checked.hit()
            }
        }
        db.playOn("t00002", NOW + 1)
        rig.totals.refresh(listOf("t00002"))
        checked.reached.await()
        coroutineScope {
            val hold = async(Dispatchers.Default) { rig.totals.withReadHold { plays("t00002") } }
            assertEquals(before, plays("t00002"))
            checked.open()
            assertEquals(before + 1, hold.await())
        }
        assertTrue(rig.totals.applying.value % 2 == 0L)
    }
}
