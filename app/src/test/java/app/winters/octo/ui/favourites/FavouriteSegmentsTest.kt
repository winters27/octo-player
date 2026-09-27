package app.winters.octo.ui.favourites

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouriteSegmentsTest {
    @Test
    fun songsComeFirst() {
        assertEquals(listOf("Songs", "Albums", "Artists"), FavouriteSegment.entries.map { it.label })
    }

    @Test
    fun someoneWhoHeartsSongsOpensOnTheirSongs() {
        // Seven hearted songs and no favourite albums or artists: the page
        // used to open on an empty Albums list.
        assertEquals(FavouriteSegment.Songs, firstSegment(songs = 7, albums = 0, artists = 0))
        assertEquals(FavouriteSegment.Songs, firstSegment(songs = 7, albums = 3, artists = 2))
    }

    @Test
    fun withoutLikedSongsItOpensOnWhatThereIs() {
        assertEquals(FavouriteSegment.Albums, firstSegment(songs = 0, albums = 3, artists = 0))
        assertEquals(FavouriteSegment.Artists, firstSegment(songs = 0, albums = 0, artists = 2))
    }

    @Test
    fun withNothingItOpensOnSongsAndSaysHowToAdd() {
        assertEquals(FavouriteSegment.Songs, firstSegment(songs = 0, albums = 0, artists = 0))
    }
}
