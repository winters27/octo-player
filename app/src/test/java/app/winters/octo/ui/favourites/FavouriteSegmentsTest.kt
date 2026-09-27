package app.winters.octo.ui.favourites

import app.winters.octo.ui.nav.FavouritesRoute
import app.winters.octo.ui.nav.LikedRoute
import app.winters.octo.ui.nav.PlaylistsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun likedSongsOpensFavouritesOnTheSongs() {
        // Liked songs is no longer a page of its own; every way in that
        // means hearted songs lands on the Favourites page's Songs.
        assertEquals(FavouriteSegment.Songs, openingSegment(LikedRoute))
    }

    @Test
    fun theAlbumsShelfOpensOnTheAlbums() {
        assertEquals(FavouriteSegment.Albums, openingSegment(FavouritesRoute(albums = true)))
    }

    @Test
    fun theLibraryRowLeavesItToWhatThereIs() {
        assertNull(openingSegment(FavouritesRoute()))
        // No other page asks for a part.
        assertNull(openingSegment(PlaylistsRoute))
    }
}
