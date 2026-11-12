package app.winters.octo.device

import app.winters.octo.catalog.longOf
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// One scan runs at a time for every asker: one that stops waiting leaves
// the scan running to its end, and asks made while a scan runs share the
// next one.
@RunWith(RobolectricTestRunner::class)
class PhoneRunnerTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // The phone's files, the listing stopping at file 30 the first time
    // until `go` opens.
    private class Paused(val files: PhoneFiles) {
        val reached = CountDownLatch(1)
        val go = CountDownLatch(1)
        private val once = AtomicBoolean(false)

        val listed = PhoneFiles(files.count) { i ->
            if (i == 30 && once.compareAndSet(false, true)) {
                reached.countDown()
                go.await(10, TimeUnit.SECONDS)
            }
            files.file(i)
        }
    }

    private fun phoneSongs() = db.longOf("SELECT COUNT(*) FROM track WHERE sourceId = 'device'")

    @Test
    fun aScanWhoseAskerStopsWaitingStillReachesTheLibrary() = runBlocking {
        val paused = Paused(PhoneFiles.albums(files = 200, perAlbum = 10))
        val phone = db.phoneLibrary(paused.listed)
        val asker = launch(Dispatchers.Default) { phone.rescan() }
        assertTrue(paused.reached.await(10, TimeUnit.SECONDS))
        asker.cancel()
        asker.join()
        paused.go.countDown()
        // The scan goes on to its end on its own.
        withTimeoutOrNull(10_000) { while (phoneSongs() < 200) delay(20) }
        assertEquals(200L, phoneSongs())
    }

    @Test
    fun asksDuringAScanShareTheNext() = runBlocking {
        val paused = Paused(PhoneFiles.albums(files = 200, perAlbum = 10))
        val phone = db.phoneLibrary(paused.listed)
        val first = launch(Dispatchers.Default) { phone.rescan() }
        assertTrue(paused.reached.await(10, TimeUnit.SECONDS))
        // Each ask runs until it waits, so all ten wait before the scan goes on.
        val more = List(10) { launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { phone.rescan() } }
        paused.go.countDown()
        withTimeout(10_000) { (more + first).joinAll() }
        val listings = FakeMediaStore.current!!.queries.get()
        // Two scans at most, each listing the files twice at most.
        assertTrue("the files were listed $listings times", listings <= 4)
        assertEquals(200L, phoneSongs())
    }
}
