package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.playback.CarInput
import app.winters.octo.playback.carInputs
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A thousand downloads failing, as "Keep liked songs" on a server that
// refuses streams makes them, tell the car nothing and make no song row ask
// again, since none changes the set of downloaded songs. The downloads'
// own state still moves, so a download that starts shows at once.
@RunWith(RobolectricTestRunner::class)
class DownloadFailureStormTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    @Test
    fun failuresTellTheCarNothingAndAskNoRowAgain() = timed(60) {
        book.upsert((0 until 1_000).map { downloadRow("device:s$it") } + downloadRow("device:mark", serverId = "mark"))
        val told = Channel<CarInput>(Channel.UNLIMITED)
        val asks = AtomicInteger()
        val marks = Channel<Boolean>(Channel.UNLIMITED)
        coroutineScope {
            val car = launch { carInputs(listOf(CarInput.Downloads to book.doneVersion)).collect { told.send(it) } }
            // A song row's mark, asked again whenever the marks' count moves.
            val row = launch {
                book.doneVersion.map {
                    asks.incrementAndGet()
                    book.isDone("device:mark")
                }.collect { marks.send(it) }
            }
            assertEquals(false, withTimeout(5_000) { marks.receive() })
            val states = Channel<DownloadStatus?>(Channel.UNLIMITED)
            val page = launch { book.keptAmong(listOf("device:mark")).collect { states.send(it["device:mark"]?.state) } }
            assertEquals(DownloadStatus.Queued, withTimeout(5_000) { states.receive() })
            val done = book.doneVersion.value
            val version = book.version.value
            (0 until 1_000).forEach { book.setState("server:h", "device:s$it", DownloadStatus.Failed) }
            assertEquals(done, book.doneVersion.value)
            assertEquals(1, asks.get())
            assertTrue("the car was told nothing", told.tryReceive().isFailure)
            assertTrue(book.version.value >= version + 1_000)

            // A download that starts moves the downloads' own state at once.
            book.markDownloading("server:h", "mark")
            assertEquals(DownloadStatus.Downloading, withTimeout(5_000) { states.receive() })
            assertEquals(1, asks.get())

            // Its finish tells the car once and asks the row again once.
            book.finish("server:h", "mark", "/m/mark.flac", 10, "audio/flac", 10)
            assertEquals(CarInput.Downloads, withTimeout(5_000) { told.receive() })
            assertEquals(true, withTimeout(5_000) { marks.receive() })
            assertEquals(2, asks.get())
            assertTrue(told.tryReceive().isFailure)
            car.cancel()
            row.cancel()
            page.cancel()
        }
    }
}
