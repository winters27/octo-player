package app.winters.octo.device

import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.Pause
import app.winters.octo.catalog.TestHooks
import app.winters.octo.catalog.library
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.put
import app.winters.octo.catalog.stage
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CopyOnWriteArrayList

// The phone's copy against merges that run for other reasons: one may save
// it before the scan asks, a merge holding a claim keeps the scan from
// starting until its save has ended, and the phone's copy and a server's
// are claimed and saved together.
@RunWith(RobolectricTestRunner::class)
class PhoneStageRaceTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()
    private val files = PhoneFiles.albums(files = 50, perAlbum = 10)

    @After
    fun close() = db.close()

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun phoneSongs() = db.longOf("SELECT COUNT(*) FROM track WHERE sourceId = 'device'")

    @Test
    fun aMergeForSomethingElseMaySwapThePhonesCopyInFirst() = run {
        val hooks = TestHooks()
        val merge = db.testMerge(null, hooks)
        val phone = db.phoneLibrary(files, merge)
        phone.scan.reached = { if (it == ScanPoint.Ready) merge.rebuild() }
        phone.rescan()
        assertEquals(50L, phoneSongs())
        // The scan's own merge found nothing left to do.
        assertEquals(listOf("swap"), hooks.events.toList())
        assertEquals(1, hooks.skips.get())
    }

    @Test
    fun aScanStartsOnlyOnceAClaimingMergesSaveHasEnded() = run {
        db.put(library(server, 4))
        val atClaim = Pause()
        val order = CopyOnWriteArrayList<String>()
        val hooks = object : MergeHooks() {
            override suspend fun claimed() = atClaim.hit()

            override suspend fun inSwap() {
                order += "swap"
            }

            override fun applied(turns: Long, rows: Long, millis: Long) {
                order += "saved"
            }
        }
        val merge = db.testMerge(server, hooks)
        db.stage(library(server, 6), generation = 50)
        val serverMerge = async(Dispatchers.Default) { merge.rebuild() }
        atClaim.reached.await()
        val phone = db.phoneLibrary(files, merge)
        val started = CompletableDeferred<Unit>()
        phone.scan.reached = {
            if (it == ScanPoint.Started) {
                order += "phone started"
                started.complete(Unit)
            }
        }
        val scan = launch(Dispatchers.Default) { phone.rescan() }
        try {
            phone.scanning.first { it }
            // The scan waits on the gate the claiming merge holds.
            assertNull(withTimeoutOrNull(300) { started.await() })
        } finally {
            atClaim.release()
        }
        serverMerge.await()
        scan.join()
        // The scan starts once the claiming merge's save has ended, every
        // group of it in.
        val seen = order.toList()
        assertEquals("$seen", listOf("swap", "phone started", "swap"), seen.filter { it != "saved" })
        assertTrue("$seen", seen.indexOf("swap") < seen.indexOf("saved") && seen.indexOf("saved") < seen.indexOf("phone started"))
        assertEquals(6L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
        assertEquals(50L, phoneSongs())
    }

    @Test
    fun thePhonesCopyAndAServersAreSwappedInTogether() = run {
        db.put(library(server, 4))
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        val phone = db.phoneLibrary(files, merge)
        phone.scan.reached = { if (it == ScanPoint.Ready) db.stage(library(server, 7), generation = 90) }
        phone.rescan()
        assertEquals(listOf("swap"), hooks.events.toList())
        assertEquals(50L, phoneSongs())
        assertEquals(7L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
        // Both copies leave the stage after the swap, in the background.
        merge.awaitStageClear(DEVICE)
        merge.awaitStageClear(server)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
    }
}
