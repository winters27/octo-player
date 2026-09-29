package app.winters.octo.desktop.server

import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.ScanStatus
import app.winters.octo.subsonic.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What the Servers section says about each server and the one in use.
class ServerOverviewTest {
    private val octo = SavedServer("https://music.example.com/", "winters", serverType = "octo", serverVersion = "0.9.3", id = "h")
    private val now = 1_790_000_000_000L

    private fun overview(
        server: SavedServer = octo,
        scan: ScanStatus? = null,
        user: User? = null,
        answerMs: Long? = null,
        songs: Int? = null,
    ) = overviewOf(server, lyrics = true, adds = true, songs = songs, albums = songs?.let { 2 }, artists = songs?.let { 1 }, playlists = songs?.let { 3 }, scan = scan, user = user, answerMs = answerMs, now = now)

    @Test
    fun theServerInUseIsDescribedWithoutMachinery() {
        val shown = overview(songs = 1_204, answerMs = 38)
        assertEquals("Octo 0.9.3", shown.kind)
        assertEquals("Offers synced lyrics and adding songs you find online to your library.", shown.offers)
        assertEquals("1,204 songs, 2 albums, 1 artist and 3 playlists", shown.counts)
        assertEquals("Answered in 38 ms", shown.answer)
    }

    @Test
    fun whatIsNotKnownYetIsLeftOutRatherThanGuessed() {
        val shown = overview()
        assertNull(shown.counts)
        assertNull(shown.answer)
        assertNull(shown.scan)
        assertFalse("not offered until the server says the user is an admin", shown.canScan)
        assertTrue("a password may be changed unless the server says no", shown.canChangePassword)
    }

    @Test
    fun onlyAnAdminIsOfferedAScan() {
        assertTrue(overview(user = User("winters", adminRole = true)).canScan)
        assertFalse(overview(user = User("winters", adminRole = false)).canScan)
    }

    @Test
    fun aScanRunningOrDoneIsSaid() {
        val running = overview(scan = ScanStatus(scanning = true, count = 5_000))
        assertTrue(running.scanning)
        assertEquals("Reading its folders now, 5,000 files so far.", running.scan)
        assertEquals("Last read its folders 2 hours ago.", overview(scan = ScanStatus(lastScan = java.time.Instant.ofEpochMilli(now - 2 * 3_600_000).toString())).scan)
    }

    @Test
    fun aPasswordCannotBeChangedWithAKeyOrWhenTheServerSaysNo() {
        val key = overview(server = octo.copy(authMode = AuthMode.ApiKey))
        assertFalse(key.canChangePassword)
        assertEquals("You signed in with an API key, so there's no password here to change.", key.passwordNote)
        val refused = overview(user = User("winters", settingsRole = false))
        assertFalse(refused.canChangePassword)
        assertEquals("Your server doesn't let this account change its password.", refused.passwordNote)
    }

    @Test
    fun eachRowSaysWhoIsSignedInWhere() {
        assertEquals("winters at music.example.com", accountLine(octo))
        assertEquals("winters at 192.168.1.20:4533", accountLine(octo.copy(address = "http://192.168.1.20:4533/")))
        assertEquals("music.example.com", accountLine(octo.copy(username = "")))
    }

    @Test
    fun eachRowSaysQuietlyHowItAnswered() {
        assertEquals("Octo 0.9.3", statusLine(octo, null))
        assertEquals("Octo 0.9.3 · Answered in 38 ms", statusLine(octo, ServerCheck(Reach.Answers, 38)))
        assertEquals("Octo 0.9.3 · Out of reach right now", statusLine(octo, ServerCheck(Reach.Unreachable)))
        assertEquals("Octo 0.9.3 · Didn't take the saved password", statusLine(octo, ServerCheck(Reach.WrongPassword)))
        assertEquals("Signed out. Sign in again to use it.", statusLine(octo.copy(signedOut = true), ServerCheck(Reach.Answers, 38)))
        assertEquals("Switching to it now", statusLine(octo, null, switching = true))
        assertEquals("Subsonic server", statusLine(SavedServer("https://x/", "a"), ServerCheck(Reach.Unknown)))
    }
}
