package app.winters.octo.offline

import app.winters.octo.catalog.exec
import app.winters.octo.catalog.song
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A download as a test writes it.
internal fun downloadRow(
    trackId: String,
    serverId: String = trackId,
    sourceId: String = "server:h",
    state: DownloadStatus = DownloadStatus.Queued,
    path: String = "",
    title: String = trackId,
) = DownloadEntity(
    trackId = trackId, sourceId = sourceId, serverId = serverId, path = path, sizeBytes = 0, format = null, state = state,
    progress = 0f, addedAt = 1, reason = Reasons.MANUAL, title = title, artist = "",
)

// The two download counters each move on their own writes: doneVersion
// when the set of downloaded songs can change (a finish, a delete of a
// finished or running download, a relink, a refetch, a requeue of a gone
// file, a restore of a finished one), version on every write but a progress
// tick, which moves neither.
@RunWith(RobolectricTestRunner::class)
class DownloadVersionsTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    // What one write moved: (doneVersion, version).
    private suspend fun moved(write: suspend () -> Unit): Pair<Long, Long> {
        val done = book.doneVersion.value
        val any = book.version.value
        write()
        return (book.doneVersion.value - done) to (book.version.value - any)
    }

    @Test
    fun eachWriteMovesItsCounters() = timed(30) {
        val both = 1L to 1L
        val versionOnly = 0L to 1L
        val neither = 0L to 0L
        assertEquals(versionOnly, moved { book.upsert(listOf(downloadRow("a"), downloadRow("b"), downloadRow("c"), downloadRow("d"))) })
        assertEquals(versionOnly, moved { book.markDownloading("server:h", "a") })
        assertEquals(neither, moved { repeat(10) { book.setProgress("server:h", "a", it / 10f) } })
        assertEquals(0.9f, db.downloadDao().get("server:h", "a")!!.progress)
        assertEquals(both, moved { book.finish("server:h", "a", "/m/a.flac", 10, "audio/flac", 10) })
        assertEquals(versionOnly, moved { book.setState("server:h", "b", DownloadStatus.Failed) })
        assertEquals(versionOnly, moved { book.setState("server:h", "b", DownloadStatus.Queued) })
        assertEquals(versionOnly, moved { book.setReason("server:h", "b", Reasons.LIKED) })
        assertEquals(versionOnly, moved { book.noteFileSize("server:h", "a", 11) })
        assertEquals(both, moved { book.refetch("server:h", "a") })
        assertEquals(versionOnly, moved { book.requeueInterrupted() })
        book.finish("server:h", "a", "/m/a.flac", 10, "audio/flac", 10)
        assertEquals(both, moved { book.requeueGone(listOf(db.downloadDao().get("server:h", "a")!!)) })
        assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:h", "a")!!.state)

        // A delete moves doneVersion for a finished row, and for one that
        // was running when read, which may have finished since.
        book.finish("server:h", "c", "/m/c.flac", 10, "audio/flac", 10)
        assertEquals(both, moved { book.delete(db.downloadDao().get("server:h", "c")!!) })
        assertEquals(versionOnly, moved { book.delete(db.downloadDao().get("server:h", "b")!!) })
        assertEquals(both, moved { book.delete(downloadRow("d", state = DownloadStatus.Downloading)) })

        // A restore puts a finished row back.
        assertEquals(both, moved { book.upsert(listOf(downloadRow("c", state = DownloadStatus.Done, path = "/m/c.flac"))) })
        assertEquals(neither, moved { book.upsert(emptyList()) })
    }

    @Test
    fun aRelinkMovesBothOnlyWhenItWroteRows() = timed {
        assertEquals("nothing to relink", 0L to 0L, moved { book.relink() })
        book.upsert(listOf(downloadRow("device:s1", serverId = "n1")))
        db.sourceDao().replaceSource("server:h", listOf(song("server:h", "n1", "Song", "al")), emptyList(), emptyList())
        db.exec("UPDATE source_track SET mergedId = 'device:s2' WHERE id = 'server:h:n1'")
        assertEquals(1L to 1L, moved { book.relink() })
        assertEquals("device:s2", db.downloadDao().get("server:h", "n1")!!.trackId)
    }

    // A requeue of a gone file writes only a row still finished with that
    // file: one downloaded again meanwhile is never knocked back.
    @Test
    fun aRequeueLeavesARowDownloadedAgainMeanwhile() = timed {
        book.upsert(listOf(downloadRow("a", state = DownloadStatus.Done, path = "/m/old.flac"), downloadRow("b", state = DownloadStatus.Done, path = "/m/b.flac")))
        val read = listOf(db.downloadDao().get("server:h", "a")!!, db.downloadDao().get("server:h", "b")!!)
        book.refetch("server:h", "a")
        book.markDownloading("server:h", "a")
        book.finish("server:h", "a", "/m/new.flac", 10, "audio/flac", 10)
        assertEquals(1, book.requeueGone(read))
        assertEquals(DownloadStatus.Done, db.downloadDao().get("server:h", "a")!!.state)
        assertEquals("/m/new.flac", db.downloadDao().get("server:h", "a")!!.path)
        assertEquals(DownloadStatus.Queued, db.downloadDao().get("server:h", "b")!!.state)
        assertEquals(0L to 0L, moved { assertEquals(0, book.requeueGone(read.take(1))) })
    }

    // Live progress reaches its reader through progressOf, row by row.
    @Test
    fun progressIsReadPerDownload() = timed {
        book.upsert(listOf(downloadRow("a")))
        book.markDownloading("server:h", "a")
        book.setProgress("server:h", "a", 0.5f)
        assertEquals(0.5f, book.progressOf("server:h", "a").first())
        // A row no longer downloading keeps what it has.
        book.setState("server:h", "a", DownloadStatus.Failed)
        book.setProgress("server:h", "a", 0.75f)
        assertEquals(0f, book.progressOf("server:h", "a").first())
        assertEquals(0f, book.progressOf("server:h", "gone").first())
    }
}
