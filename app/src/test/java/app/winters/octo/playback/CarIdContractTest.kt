package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

// The ids a car is sent read back as themselves, and an old car's Recent tab
// opens Home: the contract a car holds the app to, in words alone.
class CarIdContractTest {
    @Test
    fun everyListACarCanOpenReadsBackAsItself() {
        listOf(
            "tab:home", "tab:radio", "tab:playlists", "tab:library", "tab:more", "lib:songs", "lib:genres",
            "lib:favorites", "lib:downloads", "fav:albums", "fav:artists", "charts", "chart", "shelf:madeForYou",
            "livelist:ll-1", "genre:Hip-Hop%2FRap", "list:og1:Deep+Cuts", "station:or1:Discovery+Radio",
            "chartlist:trending:Trending+Songs", "oalbum:ext-1", "oartist:ext-2", "found:k3x9:songs", "top:k3x9",
            "retry:k3x9", "az:A:tab:albums", "part:3:playlist:3f2a-uuid", "song:find:ext-1|in:list:og1:Deep+Cuts",
            "folder:pfAbc123", "part:2:folder:pfAbc123",
        ).forEach { id -> assertEquals(id, parseCarId(id)?.let(::carId)) }
    }

    @Test
    fun anOldCarsRecentTabOpensHome() {
        assertEquals("tab:home", parseCarId("tab:recent")?.let(::carId))
    }
}
