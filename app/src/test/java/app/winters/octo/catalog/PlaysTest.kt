package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysTest {
    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun album(id: String) = AlbumEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", year = null, songCount = 1, durationMs = 0, addedAt = 0, artwork = null,
    )

    private fun played(id: String, plays: Int, at: Long) = PlayedTrack(track(id), plays, at)

    @Test
    fun albumsComeNewestPlayFirst() {
        val albums = listOf(PlayedAlbum(album("a"), 100), PlayedAlbum(album("b"), 300), PlayedAlbum(album("c"), 200))
        assertEquals(listOf("b", "c", "a"), byLatestPlay(albums, 20).map { it.id })
    }

    @Test
    fun albumsStopAtTheLimit() {
        val albums = (1..30).map { PlayedAlbum(album("a$it"), it.toLong()) }
        val shown = byLatestPlay(albums, 20)
        assertEquals(20, shown.size)
        assertEquals("a30", shown.first().id)
    }

    @Test
    fun songsPlayedMoreComeFirst() {
        val songs = listOf(played("a", 2, 900), played("b", 5, 100), played("c", 3, 500))
        assertEquals(listOf("b", "c", "a"), byPlayCount(songs, 20).map { it.id })
    }

    @Test
    fun aTieGoesToTheMoreRecentPlay() {
        val songs = listOf(played("a", 4, 100), played("b", 4, 300), played("c", 4, 200))
        assertEquals(listOf("b", "c", "a"), byPlayCount(songs, 20).map { it.id })
    }

    @Test
    fun aFullTieKeepsASteadyOrder() {
        val songs = listOf(played("b", 1, 100), played("a", 1, 100))
        assertEquals(listOf("a", "b"), byPlayCount(songs, 20).map { it.id })
        assertEquals(listOf("a", "b"), byPlayCount(songs.reversed(), 20).map { it.id })
    }

    @Test
    fun songsStopAtTheLimit() {
        val songs = (1..30).map { played("s$it", it, 0) }
        assertEquals(listOf("s30", "s29"), byPlayCount(songs, 2).map { it.id })
    }

    @Test
    fun nothingPlayedShowsNothing() {
        assertTrue(byLatestPlay(emptyList(), 20).isEmpty())
        assertTrue(byPlayCount(emptyList(), 20).isEmpty())
    }
}
