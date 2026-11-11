package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// A new download that takes the place of a finished one under the same key
// (another server's copy kept from before, picked again as the best copy)
// lets that one's file go once it has committed: the new one downloads its
// own. Today the file stayed on the phone with nothing pointing at it.
@RunWith(RobolectricTestRunner::class)
class AddReplacesFileTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aReplacedDownloadsFileGoes() = timed {
        val file = File.createTempFile("song", ".flac").apply { writeText("music") }
        val other = File.createTempFile("other", ".flac").apply { writeText("music") }
        try {
            db.putLibrary(
                listOf(librarySong("t1"), librarySong("t2")),
                listOf(serverCopy("t1", "n1"), serverCopy("t1", "o1", source = "server:o", bitrate = 999), serverCopy("t2", "o2", source = "server:o", bitrate = 999)),
            )
            db.putKept(
                listOf(
                    keptRow("t1", "o1", setOf(Reasons.MANUAL), sourceId = "server:o", state = DownloadStatus.Done, path = file.path),
                    // Not finished: nothing of it is on the phone to let go of.
                    keptRow("t2", "o2", setOf(Reasons.MANUAL), sourceId = "server:o", state = DownloadStatus.Failed, path = other.path),
                ),
            )
            val rig = RulesRig(db)
            rig.rules.download(listOf("t1", "t2"), refused = null)
            assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:o", "o1")!!.state)
            assertFalse("the replaced download's file went", file.exists())
            assertTrue(other.exists())
        } finally {
            file.delete()
            other.delete()
        }
    }
}
