package app.winters.octo.discovery

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistExtrasTest {
    @Test
    fun theServersIdComesFromItsOwnArtistRows() {
        val source = "server:music.example"
        assertEquals("ar1", serverArtistIdOf("server:music.example:ar1", source))
        // The library named this artist itself; the server gave no id.
        assertNull(serverArtistIdOf("server:music.example:artist:kavinsky", source))
        assertNull(serverArtistIdOf("device:artist:kavinsky", source))
        assertNull(serverArtistIdOf("server:other.example:ar1", source))
        assertNull(serverArtistIdOf("server:music.example:", source))
    }

    @Test
    fun topSongsByNameKeepOnlyTheArtistsOwn() {
        val songs = listOf(
            Song(id = "1", title = "Nightcall", artist = "Kavinsky"),
            Song(id = "2", title = "Odd Look", artist = "Kavinsky feat. The Weeknd"),
            Song(id = "3", title = "Something", artist = "Kavinsky Tribute Band"),
            Song(id = "4", title = "Nothing", artist = null),
        )
        assertEquals(listOf("1", "2"), ownSongs(songs, "Kavinsky").map { it.id })
    }
}
