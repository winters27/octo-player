package app.winters.octo.playback

import app.winters.octo.data.KeptServers
import app.winters.octo.data.StoredServer
import app.winters.octo.data.formerSourceIds
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A song streams from the server in use only when it is that server's, or
// was kept under an address that server had before an edit. Any other
// source's song is refused: another kept server's, and one of a server
// removed since, whose id would name some other song here, or none. A kept
// server's former addresses are the ones its edits left behind.
class StreamsSourceTest {
    private val inUse = "server:music.example"

    private fun server(url: String, id: String, formers: List<String> = emptyList()) =
        StoredServer(serverUrl = url, username = "winters", id = id, formerSourceIds = formers)

    private val kept = KeptServers(
        servers = listOf(
            server("https://music.example/", "home", formers = listOf("server:old.example", "server:192.168.1.5:4533")),
            server("https://friend.example/", "friend"),
        ),
        active = "home",
    )

    @Test
    fun aRemovedServersSongIsRefused() {
        // No kept server has this address any more.
        assertTrue(refusedSource("server:gone.example", inUse, kept))
    }

    @Test
    fun anotherKeptServersSongIsRefused() {
        assertTrue(refusedSource("server:friend.example", inUse, kept))
    }

    @Test
    fun theServerInUseAndItsFormerAddressesPlay() {
        assertFalse(refusedSource(inUse, inUse, kept))
        assertFalse(refusedSource("server:old.example", inUse, kept))
        assertFalse(refusedSource("server:192.168.1.5:4533", inUse, kept))
        // A song that names no source goes to the server in use, as before.
        assertFalse(refusedSource(null, inUse, kept))
    }

    @Test
    fun anEditKeepsTheAddressItLeft() {
        val first = server("http://192.168.1.5:4533/", "home")
        val moved = formerSourceIds(first, "https://old.example/".toHttpUrl())
        assertEquals(listOf("server:192.168.1.5:4533"), moved)
        val again = formerSourceIds(first.copy(serverUrl = "https://old.example/", formerSourceIds = moved), "https://music.example/".toHttpUrl())
        assertEquals(listOf("server:192.168.1.5:4533", "server:old.example"), again)
        // Back to an old address: that one is the server's own again.
        val back = formerSourceIds(first.copy(serverUrl = "https://music.example/", formerSourceIds = again), "https://old.example/".toHttpUrl())
        assertEquals(listOf("server:192.168.1.5:4533", "server:music.example"), back)
        // A sign-in that keeps the address adds none; a new server has none.
        assertEquals(moved, formerSourceIds(first.copy(serverUrl = "https://old.example/", formerSourceIds = moved), "https://old.example/".toHttpUrl()))
        assertEquals(emptyList<String>(), formerSourceIds(null, "https://new.example/".toHttpUrl()))
    }
}
