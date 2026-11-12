package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// While a read hold is held the totals do not change: a refresh and a
// rebuild asked for meanwhile write nothing until it ends, then both land,
// and a hold asked for while the refresh waits enters after it.
@RunWith(RobolectricTestRunner::class)
class ReadHoldFreezesTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun plays(id: String) = db.longOf("SELECT plays FROM play_total WHERE trackId = ?", id)

    @Test
    fun aHoldFreezesTheTotalsUntilItEnds() = timed {
        db.fillPlays(seed = 61, songs = 200, albums = 10)
        val rig = TotalsRig(db, db.testMerge(HOME)).started()
        val a = "t00001"
        val b = "t00002"
        val (beforeA, beforeB) = plays(a) to plays(b)
        val version = rig.totals.version.value
        coroutineScope {
            val holding = CompletableDeferred<Unit>()
            val letGo = CompletableDeferred<Unit>()
            val held = async(Dispatchers.Default) {
                rig.totals.withReadHold {
                    holding.complete(Unit)
                    letGo.await()
                    plays(a) to plays(b)
                }
            }
            holding.await()
            val waits = CompletableDeferred<Unit>()
            rig.totals.holds.writeWaits = { waits.complete(Unit) }
            db.playOn(a, NOW + 1)
            rig.totals.refresh(listOf(a))
            waits.await()
            db.playOn(b, NOW + 2)
            rig.totals.rebuild()
            // A waiting write turns a new hold away.
            val next = async(Dispatchers.Default) { rig.totals.withReadHold { plays(a) } }
            assertEquals(beforeA to beforeB, plays(a) to plays(b))
            assertEquals(version, rig.totals.version.value)
            letGo.complete(Unit)
            assertEquals(beforeA to beforeB, held.await())
            assertEquals(beforeA + 1, next.await())
        }
        rig.totals.version.first { it >= version + 2 }
        assertEquals(beforeA + 1 to beforeB + 1, plays(a) to plays(b))
    }
}
