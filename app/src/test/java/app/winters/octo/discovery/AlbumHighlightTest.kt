package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumHighlightTest {
    private fun track(id: String, title: String, artist: String = "Drake") = TrackEntity(
        id = id, sourceId = "server", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "ar1", album = "HABIBTI (FOMO)", albumId = "a1", trackNo = null, discNo = null, year = null,
        durationMs = 180_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "",
    )

    private val album = listOf(track("t1", "Rusty Intro"), track("t2", "WNBA"), track("t3", "Gen 5"), track("t4", "Quebec"))

    @Test
    fun theAlbumSongFirstInTheArtistsTopSongsIsTheMainOne() {
        val ranked = listOf(RankedSong("x1", "Hotline Bling", "Drake"), RankedSong("t3", "Gen 5", "Drake"), RankedSong("t2", "WNBA", "Drake"))
        assertEquals("t3", albumHighlight(album, ranked))
    }

    @Test
    fun aSongTheRankingKnowsByAnotherIdIsMatchedByTitleAndArtist() {
        // A song found online has its own id; features in either title are left out.
        val ranked = listOf(RankedSong("online-9", "Quebec (feat. Future)", "Drake"))
        assertEquals("t4", albumHighlight(album, ranked))
    }

    @Test
    fun aGuestCreditInsideAVersionTagStillFindsThatVersion() {
        // The credit goes and the radio edit stays, so the album's radio
        // edit is the match and its plain song is not.
        val tracks = listOf(track("t1", "Get Lucky"), track("t2", "Get Lucky (Radio Edit)"))
        val ranked = listOf(RankedSong("online-1", "Get Lucky (Radio Edit - feat. Pharrell Williams)", "Drake"))
        assertEquals("t2", albumHighlight(tracks, ranked))
        val plain = listOf(RankedSong("online-2", "Get Lucky feat. Pharrell Williams", "Drake"))
        assertEquals("t1", albumHighlight(tracks, plain))
    }

    @Test
    fun theSameTitleByAnotherArtistIsNotTheAlbumsSong() {
        val ranked = listOf(RankedSong("z9", "Quebec", "Someone Else"))
        assertNull(albumHighlight(album, ranked))
    }

    @Test
    fun noAlbumSongAmongTheTopSongsMeansNoStar() {
        assertNull(albumHighlight(album, listOf(RankedSong("x1", "Hotline Bling", "Drake"))))
        assertNull(albumHighlight(album, emptyList()))
    }
}
