package app.winters.octo.ui.common

import app.winters.octo.discovery.DownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Test

// What TalkBack says a download button is doing.
class DownloadButtonTest {
    @Test
    fun eachPhaseIsSaidPlainly() {
        assertEquals("Not in your library", downloadStateText(DownloadPhase.None))
        assertEquals("Queued", downloadStateText(DownloadPhase.Queued))
        assertEquals("Downloading, 42 percent", downloadStateText(DownloadPhase.Downloading(0.42f)))
        assertEquals("Downloading", downloadStateText(DownloadPhase.Downloading(null)))
        assertEquals("Adding to your library", downloadStateText(DownloadPhase.Adding))
        assertEquals("In your library", downloadStateText(DownloadPhase.Done))
        assertEquals("Could not download", downloadStateText(DownloadPhase.Failed("No source had this song")))
    }
}
