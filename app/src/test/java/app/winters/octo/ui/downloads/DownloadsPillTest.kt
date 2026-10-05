package app.winters.octo.ui.downloads

import app.winters.octo.discovery.DownloadPhase
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
}
