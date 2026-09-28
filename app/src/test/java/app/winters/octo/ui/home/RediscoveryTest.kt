package app.winters.octo.ui.home

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RediscoveryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun track(id: String, albumId: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = albumId, trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun album(id: String, songs: Int, addedDaysAgo: Long = 0) = AlbumEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", year = null, songCount = songs, durationMs = 0, addedAt = now - addedDaysAgo * day, artwork = null,
    )

    private fun played(id: String, albumId: String, plays: Int, daysAgo: Long) = PlayedTrack(track(id, albumId), plays, now - daysAgo * day)

    @Test
    fun theSharedShelvesReadThePhonesAlbumsAndPlays() {
        val albums = listOf(album("full", 2), album("half", 3), album("old", 1), album("new", 5, addedDaysAgo = 1), album("older", 5, addedDaysAgo = 50))
        val plays = listOf(
            played("f1", "full", 3, 2), played("f2", "full", 1, 3),
            played("h1", "half", 2, 10),
            played("o1", "old", 9, 400),
        )
        val shelves = homeRediscovery(albums, plays, now)
        assertEquals(listOf("old"), shelves.notPlayedLately.map { it.id })
        assertEquals(listOf("half"), shelves.neverFinished.map { it.id })
        assertEquals(listOf("new", "older"), shelves.neverPlayed.map { it.id })
    }

    @Test
    fun aSongListedTwiceCountsOnce() {
        val albums = listOf(album("a", 2))
        val plays = listOf(played("s1", "a", 1, 1), played("s1", "a", 1, 1))
        assertEquals(1, albumListening(albums, plays)(albums[0]).playedSongs)
    }
}
