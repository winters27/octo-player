package app.winters.octo.lyrics

import app.winters.octo.admin.DownloadRecord
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.discovery.downloadMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A clean edit shares the song's lyrics (see LyricsMatchTest in shared
// core), but it is still another download.
class LyricsMatchDownloadTest {
    @Test
    fun aCleanEditIsStillAnotherDownload() {
        val find = OnlineSongEntity("find:e1", "s", "e1", "Movie Star", "Jack Harlow", "", null, null, 0, null, null, null, 0)
        assertFalse(downloadMatches(find, DownloadRecord(artist = "Jack Harlow", title = "Movie Star (Clean)")))
        assertTrue(downloadMatches(find, DownloadRecord(artist = "Jack Harlow", title = "Movie Star (Explicit)")))
    }
}
