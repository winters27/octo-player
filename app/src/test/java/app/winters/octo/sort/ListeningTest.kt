package app.winters.octo.sort

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTest {
    private fun track(id: String, album: String = "al", artist: String = "ar") = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id, artist = artist,
        artistId = artist, album = album, albumId = album, trackNo = null, discNo = null, year = null, durationMs = 1_000,
        addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun album(id: String) = AlbumEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id, artist = "ar",
        artistId = "ar", year = null, songCount = 1, durationMs = 1_000, addedAt = 0, artwork = null,
    )

    // Base order a, b, c, d, e: the order SQL gave, by name.
    private val base = listOf("a", "b", "c", "d", "e")
    private val heard = mapOf(
        "b" to Listening(plays = 3, lastPlayedAt = 100),
        "d" to Listening(plays = 7, lastPlayedAt = 50),
        "e" to Listening(plays = 3, lastPlayedAt = 300),
    )

    private fun order(mostPlayed: Boolean, descending: Boolean) =
        byListening(base, { it }, heard, mostPlayed, descending)

    @Test
    fun mostPlayedFirstThenTheRestByName() {
        // d has the most plays; b and e tie on plays, e was played later.
        assertEquals(listOf("d", "e", "b", "a", "c"), order(mostPlayed = true, descending = true))
    }

    @Test
    fun leastPlayedFirstPutsTheUnplayedOnTop() {
        assertEquals(listOf("a", "c", "b", "e", "d"), order(mostPlayed = true, descending = false))
    }

    @Test
    fun recentlyPlayedGoesByTheLastPlay() {
        assertEquals(listOf("e", "b", "d", "a", "c"), order(mostPlayed = false, descending = true))
        assertEquals(listOf("a", "c", "d", "b", "e"), order(mostPlayed = false, descending = false))
    }

    @Test
    fun fullTiesKeepTheBaseOrder() {
        val same = Listening(plays = 2, lastPlayedAt = 10)
        val listening = mapOf("c" to same, "a" to same, "e" to same)
        assertEquals(listOf("a", "c", "e", "b", "d"), byListening(base, { it }, listening, mostPlayed = true, descending = true))
        assertEquals(listOf("b", "d", "a", "c", "e"), byListening(base, { it }, listening, mostPlayed = true, descending = false))
    }

    @Test
    fun anAlbumSeenOnlyOnTheServerCountsForRecentButNotForMost() {
        val listening = mapOf("b" to Listening(plays = 0, lastPlayedAt = 500))
        assertEquals(listOf("b", "a", "c", "d", "e"), byListening(base, { it }, listening, mostPlayed = false, descending = true))
        assertEquals(base, byListening(base, { it }, listening, mostPlayed = true, descending = true))
    }

    @Test
    fun albumsAddUpTheirSongsAndTakeTheLatestPlay() {
        val played = listOf(
            PlayedTrack(track("1", album = "x"), plays = 2, lastPlayedAt = 10),
            PlayedTrack(track("2", album = "x"), plays = 5, lastPlayedAt = 40),
            PlayedTrack(track("3", album = "y"), plays = 1, lastPlayedAt = 20),
        )
        val server = listOf(PlayedAlbum(album("y"), lastPlayedAt = 90), PlayedAlbum(album("z"), lastPlayedAt = 30))
        assertEquals(
            mapOf(
                "x" to Listening(7, 40),
                "y" to Listening(1, 90),
                "z" to Listening(0, 30),
            ),
            albumListening(played, server),
        )
    }

    @Test
    fun artistsAddUpTheSongsOnTheirAlbums() {
        val played = listOf(
            PlayedTrack(track("1", artist = "p"), plays = 2, lastPlayedAt = 10),
            PlayedTrack(track("2", artist = "p"), plays = 4, lastPlayedAt = 5),
            PlayedTrack(track("3", artist = "q"), plays = 1, lastPlayedAt = 20),
        )
        assertEquals(mapOf("p" to Listening(6, 10), "q" to Listening(1, 20)), artistListening(played))
    }

    @Test
    fun songsKeepTheirCombinedCounts() {
        val played = listOf(PlayedTrack(track("1"), plays = 9, lastPlayedAt = 77))
        assertEquals(mapOf("1" to Listening(9, 77)), songListening(played))
    }
}
