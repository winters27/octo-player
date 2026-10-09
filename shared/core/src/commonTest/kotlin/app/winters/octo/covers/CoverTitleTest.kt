package app.winters.octo.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// The words a cover sets: the same cases as the server's covers.
class CoverTitleTest {
    private val setter = FakeTypesetter()

    @Test
    fun aMixNamedMixReadsAsTwoLinesWhateverItsKind() {
        assertEquals(CoverTitle("Discovery", "Mix"), coverTitle("Discovery Mix", STATION_COVER_LINE))
        assertEquals(CoverTitle("Bad Bunny", "Station"), coverTitle("Bad Bunny Radio", STATION_COVER_LINE))
        assertEquals(CoverTitle("Your Mix", null), coverTitle("Your Mix", STATION_COVER_LINE))
        assertEquals(CoverTitle("Top 50", "Chart"), coverTitle("Top 50 Chart", PLAYLIST_COVER_LINE))
        assertEquals(CoverTitle("Rock", "Station"), coverTitle("Rock Radio", STATION_COVER_LINE))
        assertEquals(CoverTitle("Discovery", "Mix"), coverTitle("Discovery MIX", PLAYLIST_COVER_LINE))
    }

    @Test
    fun possessivesStayWhole() {
        for (name in listOf("Your Mix", "My Mix", "Our Radio")) {
            assertEquals(CoverTitle(name, null), coverTitle(name, PLAYLIST_COVER_LINE))
        }
        // Kept whole, so the list's own line still says what it is.
        assertEquals(CoverTitle("your chart", "Playlist"), coverTitle("your chart", PLAYLIST_COVER_LINE))
    }

    // "New" and "Trending" are names, not kinds, on a playlist.
    @Test
    fun newAndTrendingAreNotKindWords() {
        assertEquals(CoverTitle("Something New", "Playlist"), coverTitle("Something New", PLAYLIST_COVER_LINE))
        assertEquals(CoverTitle("Trending", "Playlist"), coverTitle("Trending", PLAYLIST_COVER_LINE))
    }

    @Test
    fun aNameTooShortOrAWordOnlyEndingSoStaysWhole() {
        assertEquals(CoverTitle("A Mix", null), coverTitle("A Mix", PLAYLIST_COVER_LINE))
        assertEquals(CoverTitle("Mix", null), coverTitle("Mix", PLAYLIST_COVER_LINE))
        assertEquals(CoverTitle("Gym Mixtape", "Playlist"), coverTitle("Gym Mixtape", PLAYLIST_COVER_LINE))
        assertEquals(CoverTitle("Road Trip Playlist", null), coverTitle("Road Trip Playlist", PLAYLIST_COVER_LINE))
    }

    @Test
    fun theCoverSaysChart() {
        assertEquals(CoverTitle("Popular", "Chart"), coverTitle("Popular right now", CHART_COVER_LINE, "Popular"))
        // The cover's own words are set as they are, never trimmed of a kind
        // word, and one that says what it is needs no second line.
        assertEquals(CoverTitle("Top Mix", null), coverTitle("Top Mix right now", CHART_COVER_LINE, "Top Mix"))
    }

    @Test
    fun theCoverSetsTheShortWordsAndKeepsTheListsOwnBackground() {
        val palette = seededPalette("og-pop")
        val spec = CoverSpec("og-pop", "Popular right now", CHART_COVER_LINE, null, palette, coverTitle = "Popular")
        val words = coverWords(spec, 600, setter)
        assertEquals(listOf("Popular", "Chart"), words.map { it.text })
        assertEquals(chooseBackground("og-pop", palette), planCover(spec, 600, setter).background)
        val mix = coverWords(CoverSpec("og-d", "Discovery Mix", STATION_COVER_LINE, null, palette), 600, setter)
        assertEquals(listOf("Discovery", "Mix"), mix.map { it.text })
    }

    @Test
    fun theCoverTitleIsPartOfTheKey() {
        val palette = seededPalette("og-pop")
        val plain = CoverSpec("og-pop", "Popular right now", CHART_COVER_LINE, null, palette)
        assertNotEquals(coverArtKey(plain, 160), coverArtKey(plain.copy(coverTitle = "Popular"), 160))
    }
}
