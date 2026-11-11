package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A new download written over a finished one under the same key (another
// server's copy of a song, downloaded before, picked again as the best
// copy) takes a song out of the downloaded set, so it moves doneVersion,
// and the marks and the car hear it. A new download over nothing, or over
// one not finished, moves version only.
@RunWith(RobolectricTestRunner::class)
class AddReplacesDoneTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    @Test
    fun aNewDownloadOverAFinishedOneMovesDoneVersion() = timed {
        book.upsert(listOf(downloadRow("server:h:t", serverId = "x", sourceId = "server:o", state = DownloadStatus.Done, path = "/m/t.flac")))
        book.upsert(listOf(downloadRow("server:h:u", serverId = "y", sourceId = "server:o", state = DownloadStatus.Failed)))
        val done = book.doneVersion.value
        val any = book.version.value

        book.upsert(listOf(downloadRow("server:h:t", serverId = "x", sourceId = "server:o")))
        assertEquals(done + 1, book.doneVersion.value)
        assertEquals(any + 1, book.version.value)
        assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:o", "x")!!.state)

        book.upsert(listOf(downloadRow("server:h:u", serverId = "y", sourceId = "server:o"), downloadRow("server:h:v", serverId = "z")))
        assertEquals(done + 1, book.doneVersion.value)
        assertEquals(any + 2, book.version.value)
    }
}
