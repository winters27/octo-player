package app.winters.octo.discovery

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistExtrasTest {
    @Test
    fun theReadMoreLinkAndTagsGo() {
        val bio = "Vincent Belorgey, known as <b>Kavinsky</b>, is a French musician. " +
            "<a target='_blank' href=\"https://www.last.fm/music/Kavinsky\" rel=\"nofollow\">Read more on Last.fm</a>"
        assertEquals("Vincent Belorgey, known as Kavinsky, is a French musician.", cleanBiography(bio))
    }

    @Test
    fun aLinkInsideTheTextKeepsItsWords() {
        val bio = "Signed to <a href=\"https://example.test/label\">Record Makers</a> in 2006."
        assertEquals("Signed to Record Makers in 2006.", cleanBiography(bio))
    }

    @Test
    fun charactersAreWrittenOut() {
        assertEquals(
            "Daft Punk & Justice \"live\" at the café's <best> night",
            cleanBiography("Daft Punk &amp; Justice &quot;live&quot; at the caf&#233;&#x27;s &lt;best&gt;&nbsp;night"),
        )
        // "&amp;lt;" is the text "&lt;", not a "<".
        assertEquals("a &lt; b", cleanBiography("a &amp;lt; b"))
    }

    @Test
    fun spacingIsTidied() {
        assertEquals(
            "First paragraph.\n\nSecond one,\nwith a break.",
            cleanBiography("  First   paragraph.<br/><br />\n  Second one,<br>with a break.  "),
        )
    }

    @Test
    fun nothingLeftIsNoBiography() {
        assertNull(cleanBiography(""))
        assertNull(cleanBiography("   "))
        assertNull(cleanBiography("<a href=\"https://www.last.fm/music/X\">Read more on Last.fm</a>"))
    }

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
