package app.winters.octo.ui.album

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AlbumExtrasTest {
    private fun track(id: String, genre: String = "", rating: Int = 0) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        genre = genre, rating = rating,
    )

    @Test
    fun theGenreIsTheOneMostSongsHave() {
        assertEquals("Synthpop", albumGenre(listOf(track("1", "Synthpop"), track("2", "Rock"), track("3", "synthpop"))))
        // A tie goes to the genre met first; songs with none do not count.
        assertEquals("Rock", albumGenre(listOf(track("1"), track("2", "Rock"), track("3", "Jazz"))))
        assertNull(albumGenre(listOf(track("1"), track("2", " "))))
        assertNull(albumGenre(emptyList()))
    }

    @Test
    fun theAverageLeavesOutSongsWithNoRating() {
        assertEquals(4.5, averageRating(listOf(track("1", rating = 4), track("2"), track("3", rating = 5)))!!, 0.0001)
        assertNull(averageRating(listOf(track("1"), track("2"))))
    }

    @Test
    fun theAverageReadsToOnePlace() {
        assertEquals("4.3", starsText(13 / 3.0, Locale.US))
        assertEquals("4", starsText(4.0, Locale.US))
        assertEquals("5", starsText(4.96, Locale.US))
        assertEquals("Songs rated 1 star on average", averageRatingSpoken(1.0, Locale.US))
        assertEquals("Songs rated 3.5 stars on average", averageRatingSpoken(3.5, Locale.US))
    }
}
