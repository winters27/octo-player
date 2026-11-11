package app.winters.octo.ui.offline

import app.winters.octo.listening.Gate
import app.winters.octo.listening.timed
import app.winters.octo.offline.DownloadBook
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.downloadRow
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Presented
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.DONE_SEGMENT
import app.winters.octo.ui.paging.DownloadsLists
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListReads
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The Downloads page follows the download table's write counter, never a
// progress tick: ten ticks read nothing again; a download that starts moves
// into Downloading at once; a storm of a thousand failures during one
// refresh queues one more refresh at most and never moves the counter the
// download marks read.
@RunWith(RobolectricTestRunner::class)
class DownloadsListTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // The list's shared pages stop before the database closes.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    // The page's list, held at a gate inside a page read once armed.
    private class Held(private val inner: ListLoader<DownloadRow>) : ListLoader<DownloadRow> by inner {
        @Volatile var gate: Gate? = null

        override suspend fun after(key: RowKey?, limit: Int): List<Keyed<DownloadRow>> {
            gate?.let {
                gate = null
                it.hit()
            }
            return inner.after(key, limit)
        }
    }

    @Test
    fun ticksReadNothingAStartShowsAtOnceAndAStormQueuesOneRefresh() = timed(60) {
        book.upsert((0 until 40).map { i -> downloadRow("device:s%02d".format(i), state = if (i < 30) DownloadStatus.Done else DownloadStatus.Queued, path = if (i < 30) "/m/$i" else "") })
        val held = Held(DownloadsLists(db.listsDao()).loader("server:h", SortOrder(DownloadSort.RecentlyDownloaded, true)))
        val handle = ListHandle<Int, DownloadRow>(scope, 0, { held }, ListReads.Plain, { _, _ -> book.version.drop(1) })
        Presented(handle.pages, scope).use { shown ->
            shown.settle()
            assertEquals(40, shown.size())
            assertEquals(DownloadStatus.Queued, shown.get(0)!!.row.state)

            val beforeStart = handle.generationNow
            book.markDownloading("server:h", "device:s35")
            shown.settle(until = { handle.presented > beforeStart })
            val first = shown.get(0)!!
            assertEquals("the started download leads at once", "device:s35", first.row.trackId)
            assertEquals(0, first.key.segment)

            val afterStart = handle.generationNow
            repeat(10) { book.setProgress("server:h", "device:s35", (it + 1) / 10f) }
            delay(200)
            shown.settle()
            assertEquals("ticks read nothing again", afterStart, handle.generationNow)

            val gate = Gate()
            held.gate = gate
            handle.refresh()
            gate.reached.await()
            val stormFrom = handle.generationNow
            val marks = book.doneVersion.value
            repeat(1_000) { book.setState("server:h", "device:s%02d".format(30 + it % 10), if (it % 2 == 0) DownloadStatus.Failed else DownloadStatus.Queued) }
            gate.open()
            shown.settle(until = { handle.presented == handle.generationNow })
            assertTrue("one more refresh at most: ${handle.generationNow - stormFrom}", handle.generationNow - stormFrom <= 1)
            assertEquals("the marks' counter never moved", marks, book.doneVersion.value)
            assertEquals(40, shown.size())
            assertEquals(30, (0 until 40).count { shown.get(it)!!.key.segment == DONE_SEGMENT })
        }
    }
}
