package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A file found to be a sign-in page puts back only the playing song's
// finished downloads kept in it, and the downloaded set hears it. Its
// downloads kept in other files, and other songs' downloads, stay done.
@RunWith(RobolectricTestRunner::class)
class RequeueFileTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    @Test
    fun onlyThatSongsDownloadInThatFileWaitsAgain() = timed {
        book.upsert(
            listOf(
                downloadRow("t", serverId = "a", state = DownloadStatus.Done, path = "/m/page.flac"),
                downloadRow("t", serverId = "b", sourceId = "server:o", state = DownloadStatus.Done, path = "/m/other.flac"),
                downloadRow("u", state = DownloadStatus.Done, path = "/m/page.flac"),
            ),
        )
        val done = book.doneVersion.value

        assertEquals(1, book.requeueFile("t", "/m/page.flac"))

        assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:h", "a")!!.state)
        assertEquals(DownloadStatus.Done, db.downloadDao().get("server:o", "b")!!.state)
        assertEquals(DownloadStatus.Done, db.downloadDao().get("server:h", "u")!!.state)
        assertEquals(done + 1, book.doneVersion.value)
    }
}
