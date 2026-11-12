package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Downloads are looked up by song, 900 songs at a time, never as a map of
// every download: whether a song is downloaded (a finished download from
// any server), which of some songs are, and their rows as today's map of
// every row by song gave them (the row written last for a song of two
// servers). The rows are read again on each write but a progress tick.
@RunWith(RobolectricTestRunner::class)
class DownloadLookupsTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    @Test
    fun lookupsAskAChunkAtATimeAndAnswerAsTheWholeMapDid() = timed(30) {
        val rows = (0 until 2_000).map { n -> downloadRow("device:s$n", state = if (n % 3 == 0) DownloadStatus.Done else DownloadStatus.Queued) }
        book.upsert(rows)
        // Two servers' copies of one song: the first finished, the second
        // written later and still waiting.
        book.upsert(listOf(downloadRow("device:twice", serverId = "x1", state = DownloadStatus.Done, path = "/m/1.flac")))
        book.upsert(listOf(downloadRow("device:twice", serverId = "x2", sourceId = "server:o")))
        val asked = (0 until 2_000).map { "device:s$it" } + "device:twice" + "device:none"
        val whole = db.downloadDao().all().associateBy { it.trackId }

        db.counted.reset()
        assertEquals((0 until 2_000).filter { it % 3 == 0 }.map { "device:s$it" }.toSet() + "device:twice", book.doneAmong(asked))
        assertTrue(book.isDone("device:twice"))
        assertEquals(false, book.isDone("device:s1"))
        val kept = book.keptAmong(asked).first()
        assertEquals(whole.filterKeys { it in asked }, kept)
        assertEquals("x2", kept["device:twice"]!!.serverId)
        assertEquals(whole["device:twice"], book.keptRow("device:twice").first())
        assertEquals(null, book.keptRow("device:none").first())
        assertTrue("${db.counted.maxBound}", db.counted.maxBound!!.boundArgs <= 900)
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
    }

    @Test
    fun keptRowsAreReadAgainOnWritesButNotOnTicks() = timed {
        book.upsert(listOf(downloadRow("device:a")))
        val seen = Channel<DownloadEntity?>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { book.keptRow("device:a").collect { seen.send(it) } }
            assertEquals(DownloadStatus.Queued, withTimeout(5_000) { seen.receive() }!!.state)
            book.markDownloading("server:h", "device:a")
            assertEquals(DownloadStatus.Downloading, withTimeout(5_000) { seen.receive() }!!.state)
            db.counted.reset()
            db.counted.recording = true
            repeat(10) { book.setProgress("server:h", "device:a", it / 10f) }
            book.setState("server:h", "device:a", DownloadStatus.Failed)
            assertEquals(DownloadStatus.Failed, withTimeout(5_000) { seen.receive() }!!.state)
            db.counted.recording = false
            val reads = db.counted.statements.count { it.sql.startsWith("SELECT * FROM download WHERE trackId = ?") }
            assertEquals("one read, for the failure, none for the ticks", 1, reads)
            collecting.cancel()
        }
    }
}
