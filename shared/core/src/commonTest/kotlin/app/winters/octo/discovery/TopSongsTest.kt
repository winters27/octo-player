package app.winters.octo.discovery

import app.winters.octo.subsonic.TOP_SONGS_DEEZER
import app.winters.octo.subsonic.TOP_SONGS_LASTFM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopSongsTest {
    private val artists = listOf("Daft Punk Tribute Band", "Daft Punk", "The Weeknd", "Bob Marley & The Wailers")

    @Test
    fun theArtistSearchedForIsTheOneNamedWhole() {
        assertEquals("Daft Punk", searchedArtist("daft punk", artists) { it })
        assertEquals("Daft Punk", searchedArtist("  DAFT PUNK ", artists) { it })
        assertEquals("The Weeknd", searchedArtist("weeknd", artists) { it })
        assertEquals("The Weeknd", searchedArtist("the weeknd", artists) { it })
    }

    @Test
    fun aPartOfANameOrASongIsNoArtist() {
        assertNull(searchedArtist("daft", artists) { it })
        assertNull(searchedArtist("bob marley", artists) { it })
        assertNull(searchedArtist("get lucky", artists) { it })
        assertNull(searchedArtist("", artists) { it })
    }

    @Test
    fun playsAreShort() {
        assertEquals("1 play", playsText(1))
        assertEquals("940 plays", playsText(940))
        assertEquals("1K plays", playsText(1_000))
        assertEquals("1.5K plays", playsText(1_460))
        assertEquals("12K plays", playsText(12_345))
        assertEquals("13K plays", playsText(12_960))
        assertEquals("2.5M plays", playsText(2_536_925))
        assertEquals("1M plays", playsText(999_960))
        assertEquals("120M plays", playsText(119_800_000))
        assertEquals("1.2B plays", playsText(1_234_000_000))
    }

    @Test
    fun theRankingIsNamed() {
        assertEquals("Most played on Last.fm", rankedBy(TOP_SONGS_LASTFM))
        assertEquals("Most popular on Deezer", rankedBy(TOP_SONGS_DEEZER))
        assertNull(rankedBy(""))
    }
}
