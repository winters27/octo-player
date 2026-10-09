package app.winters.octo.ui.downloads

import app.winters.octo.discovery.DownloadPhase
import app.winters.octo.subsonic.Acquisition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Brandon: "just to be able to open it quickly without having to navigate to
// the library". The pill shows only while songs are on their way, and says
// how many.
class DownloadsPillTest {
    @Test
    fun onlySongsStillOnTheirWayCount() {
        assertTrue(onItsWay(DownloadPhase.Queued))
        assertTrue(onItsWay(DownloadPhase.Downloading(0.4f)))
        assertTrue(onItsWay(DownloadPhase.Downloading(null)))
        assertTrue(onItsWay(DownloadPhase.Adding))
        assertFalse(onItsWay(DownloadPhase.None))
        assertFalse(onItsWay(DownloadPhase.Done))
    }

    @Test
    fun theWordsCountTheSongs() {
        assertEquals("1 downloading", comingText(1))
        assertEquals("3 downloading", comingText(3))
    }

    // Brandon: "downloading pill should update dynamically to the steps its on".
    @Test
    fun theRingFollowsTheServersStep() {
        fun row(state: String, progress: Float? = null) =
            rowOf(Acquisition(id = "a", title = "Da Funk", artist = "Daft Punk", state = state, progress = progress, key = "s:a", kind = "download"))
        assertEquals(DownloadPhase.Queued, ringPhase(row("queued")))
        assertEquals(DownloadPhase.Queued, ringPhase(row("searching")))
        assertEquals(DownloadPhase.Downloading(0.4f), ringPhase(row("downloading", 0.4f)))
        assertEquals(DownloadPhase.Adding, ringPhase(row("verifying")))
        assertEquals(DownloadPhase.Adding, ringPhase(row("importing")))
    }
}
