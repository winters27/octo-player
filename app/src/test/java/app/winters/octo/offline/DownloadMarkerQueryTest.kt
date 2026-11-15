package app.winters.octo.offline

import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The swapped-file check reads each finished download's path through the
// real query, so a smaller download's mark decides it.
@RunWith(RobolectricTestRunner::class)
class DownloadMarkerQueryTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aMarkedDownloadIsNeverTheFileAsItIs() = runBlocking {
        val dao = db.downloadDao()
        db.putLibrary(
            listOf(librarySong("t1"), librarySong("t2")),
            listOf(serverCopy("t1", "n1").copy(mimeType = "audio/aac", sizeBytes = 9_000_000), serverCopy("t2", "n2").copy(mimeType = "audio/aac", sizeBytes = 9_000_000)),
        )
        dao.upsert(listOf(downloadRow("t1", "n1", sourceId = IN_USE), downloadRow("t2", "n2", sourceId = IN_USE)))
        // An old download of the file as it is, and a smaller one with the mark.
        dao.finish(IN_USE, "n1", "/m/Octo/A - One [a1b2c3d4].aac", 8_000_000, "audio/aac", null)
        dao.finish(IN_USE, "n2", "/m/Octo/A - Two [b1b2c3d4~s].aac", 8_000_000, "audio/aac", null)
        val files = dao.doneFilesPage(IN_USE, "", 10).associateBy { it.serverId }
        assertEquals("/m/Octo/A - Two [b1b2c3d4~s].aac", files.getValue("n2").path)
        // As it is, of another size: fetched again. Made smaller: its size noted.
        assertEquals(KeptCopyCheck.Refetch, checkDownload(files.getValue("n1")))
        assertEquals(KeptCopyCheck.Note, checkDownload(files.getValue("n2")))
    }
}
