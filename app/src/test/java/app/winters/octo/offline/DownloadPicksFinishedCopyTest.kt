package app.winters.octo.offline

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The way a new download comes to be written over a finished one: the
// library still holds the other server's copy of a song as its own, the
// server in use has no download of the song, and a download of it picks
// that copy. The finished download it replaces leaves the downloaded set,
// so doneVersion moves.
@RunWith(RobolectricTestRunner::class)
class DownloadPicksFinishedCopyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aDownloadThatPicksAnotherServersFinishedCopyMovesDoneVersion() = timed {
        val song = "$IN_USE:t"
        db.putLibrary(listOf(librarySong(song)), listOf(serverCopy(song, "x", ELSEWHERE)))
        val rig = RulesRig(db)
        rig.book.upsert(listOf(keptRow(song, "x", setOf(Reasons.LIKED), ELSEWHERE, DownloadStatus.Done, "/m/t.flac")))
        val done = rig.book.doneVersion.value

        rig.rules.download(listOf(song), refused = null)
        assertEquals(done + 1, rig.book.doneVersion.value)
        assertEquals(DownloadStatus.Queued, db.downloadDao().get(ELSEWHERE, "x")!!.state)
    }
}
