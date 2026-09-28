package app.winters.octo.desktop.library

import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongFiltersTest {
    private val now = 1_790_000_000_000L

    private val playlist = listOf(
        Song("a", "Airbag", suffix = "flac", starred = "2026-01-01T00:00:00Z"),
        Song("b", "Lucky", suffix = "mp3"),
        Song("c", "Let Down", suffix = "flac"),
        Song("a", "Airbag", suffix = "flac", starred = "2026-01-01T00:00:00Z"),
    )

    @Test
    fun aFilteredListKnowsWhereEachRowIsInTheWholeList() {
        val shown = Filtered.of(playlist, LibraryQuery(listOf(FilterPresets.Lossless)), ShownSongFields({ it.starred != null }, { 0 }), now)
        assertEquals(listOf("a", "c", "a"), shown.songs.map { it.id })
        // Removing the second row removes the playlist's third entry.
        assertEquals(listOf(0, 2, 3), (0..2).map(shown::placeOf))
    }

    @Test
    fun withNoFiltersEveryRowIsItsOwnPlace() {
        val shown = Filtered.of(playlist, LibraryQuery(), ShownSongFields({ false }, { 0 }), now)
        assertEquals(playlist, shown.songs)
        assertEquals(listOf(0, 1, 2, 3), (0..3).map(shown::placeOf))
    }

    @Test
    fun aFilterIgnoresTheQuerysOrderAndLimitSoTheTablesOrderStands() {
        val query = LibraryQuery(listOf(FilterPresets.Lossless), limit = 1, sort = app.winters.octo.query.QuerySort("Title", descending = true))
        val shown = Filtered.of(playlist, query, ShownSongFields({ false }, { 0 }), now)
        assertEquals(listOf("a", "c", "a"), shown.songs.map { it.id })
    }

    @Test
    fun heartsAndRatingsChangedHereCount() {
        // The server says "b" is no favourite, but it was just hearted here;
        // "a" was just un-hearted.
        val fields = ShownSongFields({ it.id == "b" }, { if (it.id == "c") 5 else 0 })
        assertEquals(listOf("b"), Filtered.of(playlist, LibraryQuery(listOf(FilterPresets.Favourites)), fields, now).songs.map { it.id })
        assertEquals(listOf("c"), Filtered.of(playlist, LibraryQuery(listOf(FilterPresets.ratingAtLeast(4))), fields, now).songs.map { it.id })
    }

    @Test
    fun theCountSaysHowManyOfHowMany() {
        assertEquals("2,835 songs", filteredCount(120, 2_835, filtered = false))
        assertEquals("120 of 2,835 songs", filteredCount(120, 2_835, filtered = true))
        assertEquals("0 of 1 song", filteredCount(0, 1, filtered = true))
    }
}
