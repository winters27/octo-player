package app.winters.octo.server

import app.winters.octo.subsonic.NowPlayingEntry
import app.winters.octo.subsonic.RadioStationDetails
import app.winters.octo.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServerControlsTest {
    @Test
    fun expiryIsNowPlusItsLength() {
        val now = 1_000_000L
        assertNull(shareExpiresAt(ShareExpiry.Never, now))
        assertEquals(now + 86_400_000L, shareExpiresAt(ShareExpiry.Day, now))
        assertEquals(now + 7 * 86_400_000L, shareExpiresAt(ShareExpiry.Week, now))
        assertEquals(now + 30 * 86_400_000L, shareExpiresAt(ShareExpiry.Month, now))
        assertEquals(listOf("Never", "1 day", "1 week", "1 month"), ShareExpiry.entries.map { it.label })
    }

    @Test
    fun knowsWhenAServerDoesNotShare() {
        assertTrue(sharingUnavailable(SubsonicException.Server(50, "not authorized")))
        assertTrue(sharingUnavailable(SubsonicException.NotFound("sharing disabled")))
        assertTrue(sharingUnavailable(SubsonicException.NotSubsonic("HTTP 501 from createShare")))
        assertFalse(sharingUnavailable(SubsonicException.Server(0, "busy")))
        assertFalse(sharingUnavailable(SubsonicException.Unreachable(IOException("offline"))))
        assertFalse(sharingUnavailable(SubsonicException.NotSubsonic("HTTP 404 from createShare")))
    }

    @Test
    fun findsTheServerAlbumBehindALibraryAlbum() {
        assertEquals("al-1", serverAlbumIdOf("server:music.example", "server:music.example:al-1"))
        // An album made up from loose songs is not on the server.
        assertNull(serverAlbumIdOf("server:music.example", "server:music.example:album:some title"))
        // Nor is another source's.
        assertNull(serverAlbumIdOf("server:music.example", "device:123"))
    }

    private fun listener(user: String, player: String?) = NowPlayingEntry(id = "s", username = user, playerName = player)

    @Test
    fun listeningNowLeavesOutThisPhone() {
        val entries = listOf(listener("winters", "Octo"), listener("winters", "Desktop"), listener("sam", "Octo"), listener("sam", null))
        assertEquals(
            listOf(listener("winters", "Desktop"), listener("sam", "Octo"), listener("sam", null)),
            othersListening(entries, "Winters", "Octo"),
        )
    }

    @Test
    fun octoStationsCarryTheirOwnIdAsCover() {
        val made = RadioStationDetails(id = "or123", name = "Your Mix", coverArt = "or123")
        val added = RadioStationDetails(id = "r1", name = "FIP", coverArt = "ra-r1")
        assertTrue(looksGenerated(made, isOcto = true))
        assertFalse(looksGenerated(made, isOcto = false))
        assertFalse(looksGenerated(added, isOcto = true))
    }
}
