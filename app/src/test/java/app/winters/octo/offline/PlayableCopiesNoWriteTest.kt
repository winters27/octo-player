package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// The read a play makes for its downloaded copies writes nothing, so a tap
// never waits for the database's one writer: copies whose files have gone
// are handed back as a requeue to run later, which then moves only the
// rows still finished with the file that went. Today the read requeues
// them itself, inline.
@RunWith(RobolectricTestRunner::class)
class PlayableCopiesNoWriteTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())
    private val dir = Files.createTempDirectory("downloads").toFile()

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun theReadWritesNothingAndTheRequeueRunsLater() = timed {
        val here = File(dir, "here.flac").apply { writeText("x") }
        book.upsert(
            listOf(
                downloadRow("device:here", state = DownloadStatus.Done, path = here.path),
                downloadRow("device:gone", state = DownloadStatus.Done, path = File(dir, "gone.flac").path),
                downloadRow("device:again", state = DownloadStatus.Done, path = File(dir, "old.flac").path),
                downloadRow("device:waiting"),
            ),
        )
        var later: (suspend () -> Int)? = null
        val (copies, ran) = db.counted.record {
            runBlocking { book.playableCopies(listOf("device:here", "device:gone", "device:again", "device:waiting")) { later = it } }
        }
        assertEquals(setOf("device:here"), copies.keys)
        val writes = ran.filter { !it.bookkeeping && Regex("""^\s*(UPDATE|INSERT|DELETE|REPLACE)""", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) }
        assertEquals("the read wrote: $writes", emptyList<Any>(), writes)
        assertTrue(ran.all { it.rowsChanged == 0 })
        assertNotNull("the gone copies are handed back", later)
        assertEquals(DownloadStatus.Done, db.downloadDao().get("server:h", "device:gone")!!.state)

        // Downloaded again under a new name before the requeue runs.
        book.finish("server:h", "device:again", File(dir, "new.flac").path, 10, "audio/flac", 10)
        val done = book.doneVersion.value
        assertEquals(1, later!!())
        assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:h", "device:gone")!!.state)
        assertEquals(DownloadStatus.Done, db.downloadDao().get("server:h", "device:again")!!.state)
        assertEquals(done + 1, book.doneVersion.value)
    }

    @Test
    fun nothingGoneHandsNothingBack() = timed {
        val here = File(dir, "here.flac").apply { writeText("x") }
        book.upsert(listOf(downloadRow("device:here", state = DownloadStatus.Done, path = here.path)))
        var handed = false
        assertEquals(setOf("device:here"), book.playableCopies(listOf("device:here", "device:none")) { handed = true }.keys)
        assertEquals(false, handed)
    }
}
