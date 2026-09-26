package app.winters.octo.ui.server

import app.winters.octo.server.ScanState
import app.winters.octo.subsonic.Share
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormatTest {
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    @Test
    fun scanWords() {
        assertEquals("Scan library now", scanLabel(ScanState.Idle))
        assertEquals("Scanning, 1,234 songs", scanLabel(ScanState.Scanning(1234)))
        assertEquals("Scanning", scanLabel(ScanState.Scanning(0)))
        assertEquals("Scan finished", scanLabel(ScanState.Finished))
        assertEquals("Only an admin can scan", scanLabel(ScanState.Failed("Only an admin can scan")))
    }

    @Test
    fun expiryWords() {
        val now = 10 * day
        assertEquals("Never expires", expiryLabel(null, now))
        assertEquals("Expired", expiryLabel(now - 1, now))
        assertEquals("Expires within the hour", expiryLabel(now + hour / 2, now))
        assertEquals("Expires in 5 hours", expiryLabel(now + 5 * hour, now))
        assertEquals("Expires in 1 day", expiryLabel(now + day, now))
        assertEquals("Expires in 7 days", expiryLabel(now + 7 * day - hour, now))
    }

    @Test
    fun shareLines() {
        val now = 1_790_416_800_000L
        val share = Share(
            id = "sh1",
            description = "",
            expires = "2026-09-29T10:00:00Z",
            visitCount = 12,
            entry = listOf(Song("s1", title = "Nightcall"), Song("s2", title = "Odd Look")),
        )
        assertEquals("Nightcall", shareTitle(share))
        assertEquals("2 songs • Expires in 3 days • 12 visits", shareDetails(share, now))
        assertEquals("Shared link", shareTitle(Share(id = "sh2")))
        assertEquals("Never expires • No visits", shareDetails(Share(id = "sh2"), now))
    }

    @Test
    fun minutesAgoWords() {
        assertEquals("Just now", minutesAgo(0))
        assertEquals("1 minute ago", minutesAgo(1))
        assertEquals("45 minutes ago", minutesAgo(45))
        assertEquals("1 hour ago", minutesAgo(90))
        assertEquals("3 hours ago", minutesAgo(200))
    }

    @Test
    fun streamAddresses() {
        assertTrue(isStreamAddress("https://stream.example/fip.mp3"))
        assertTrue(isStreamAddress(" http://192.168.50.21:8000/live "))
        assertFalse(isStreamAddress("stream.example/fip.mp3"))
        assertFalse(isStreamAddress(""))
        assertTrue(StationDraft(null, "FIP", "https://stream.example/fip.mp3", "").ready)
        assertFalse(StationDraft(null, " ", "https://stream.example/fip.mp3", "").ready)
        assertFalse(StationDraft(null, "FIP", "https://stream.example/fip.mp3", "not a page").ready)
    }
}
