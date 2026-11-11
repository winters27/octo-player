package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A stable read answers from one state of the library: it waits out a swap
// running when it starts, runs again when one lands while it reads, and
// makes its last try inside the merge lock, where none can.
@RunWith(RobolectricTestRunner::class)
class StableReadTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun songs() = db.longOf("SELECT COUNT(*) FROM track")

    @Test
    fun aReadWaitsOutTheSwapRunningWhenItStarts() = run {
        db.put(library(DEVICE, 4))
        val pause = Pause()
        val merge = db.testMerge(server, TestHooks(atSwap = pause))
        val merging = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        var tries = 0
        val reading = async(Dispatchers.Default) {
            stableRead(merge, HoldKind.Read) {
                tries++
                songs()
            }
        }
        assertNull(withTimeoutOrNull(300) { reading.await() })
        assertEquals(0, tries)
        pause.release()
        assertEquals(4L, reading.await())
        assertEquals(1, tries)
        merging.join()
    }

    @Test
    fun swapsDuringTwoTriesPutTheLastInsideTheLock() = run {
        db.put(library(DEVICE, 4))
        val merge = db.testMerge(server)
        merge.rebuild()
        var tries = 0
        var prepared = 0
        lateinit var later: Job
        val answer = stableRead(merge, HoldKind.Read, prepare = { prepared++ }) {
            tries++
            if (tries <= 2) {
                // A swap lands while this try reads.
                db.put(library(DEVICE, 4 + tries))
                merge.rebuild()
                -1L
            } else {
                assertTrue(merge.holdsMergeLock())
                // A swap asked for now waits for this try to end.
                db.put(library(DEVICE, 10))
                val stamp = merge.swapStamp.value
                later = launch(Dispatchers.Default) { merge.rebuild() }
                assertNull(withTimeoutOrNull(300) { merge.swapStamp.first { it != stamp } })
                songs()
            }
        }
        assertEquals(3, tries)
        assertEquals(3, prepared)
        assertEquals(6L, answer)
        later.join()
        assertEquals(10L, songs())
    }
}
