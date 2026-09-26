package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesTest {
    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun wholeGroupsPlayInTheOrderShown() {
        val places = listOf(
            TrackPlace("1", "x", "p"),
            TrackPlace("2", "x", "p"),
            TrackPlace("3", "y", "q"),
            TrackPlace("4", "z", "p"),
        )
        assertEquals(listOf("3", "1", "2", "4"), songsInGroupOrder(places, { it.albumId }, listOf("y", "x", "z")))
        assertEquals(listOf("3", "1", "2", "4"), songsInGroupOrder(places, { it.artistId }, listOf("q", "p")))
        // A group not shown is left out.
        assertEquals(listOf("3"), songsInGroupOrder(places, { it.albumId }, listOf("y")))
    }

    @Test
    fun anArtistsSongsStayInAlbumOrderUntilPlayed() {
        val songs = listOf(track("a"), track("b"), track("c"))
        assertEquals(listOf("a", "b", "c"), artistSongOrder(songs, emptyList()).map { it.id })
    }

    @Test
    fun theMostPlayedComeFirstThenTheRestInAlbumOrder() {
        val songs = listOf(track("a"), track("b"), track("c"), track("d"))
        val played = listOf(PlayedTrack(track("c"), 5, 10), PlayedTrack(track("b"), 2, 50), PlayedTrack(track("d"), 5, 90))
        assertEquals(listOf("d", "c", "b", "a"), artistSongOrder(songs, played).map { it.id })
    }

    @Test
    fun playsOfOtherArtistsDoNotCount() {
        val songs = listOf(track("a"), track("b"))
        val played = listOf(PlayedTrack(track("z"), 9, 10))
        assertEquals(listOf("a", "b"), artistSongOrder(songs, played).map { it.id })
    }
}
