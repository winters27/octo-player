package app.winters.octo.home

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RediscoveryTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    private fun album(id: String, songs: Int = 10, played: Int = 0, plays: Long = played.toLong(), daysAgo: Long? = null, addedDaysAgo: Long? = null) =
        AlbumListening(id, songs, played, plays, daysAgo?.let { now - it * day }, addedDaysAgo?.let { now - it * day })

    private fun ids(list: List<AlbumListening>) = list.map { it.id }

    // Not played in 6 months

    @Test
    fun onlyAlbumsPlayedBeforeButNotInSixMonthsAreQuiet() {
        val albums = listOf(
            album("recent", played = 10, daysAgo = 3),
            album("old", played = 10, plays = 4, daysAgo = 200),
            album("never"),
            album("justInside", played = 10, daysAgo = 179),
            album("loved", played = 10, plays = 40, daysAgo = 400),
        )
        assertEquals(listOf("loved", "old"), ids(notPlayedLately(albums, { it }, now)))
    }

    @Test
    fun quietAlbumsPlayedAsOftenGoLongestAgoFirst() {
        val albums = listOf(album("b", played = 5, daysAgo = 300), album("a", played = 5, daysAgo = 500), album("c", played = 5, daysAgo = 300))
        assertEquals(listOf("a", "b", "c"), ids(notPlayedLately(albums, { it }, now)))
    }

    // Albums you never finished

    @Test
    fun anAlbumIsUnfinishedWhenSomeButNotAllOfItsSongsWerePlayed() {
        val albums = listOf(
            album("done", songs = 8, played = 8, daysAgo = 1),
            album("half", songs = 8, played = 4, daysAgo = 10),
            album("one", songs = 8, played = 1, daysAgo = 2),
            album("none", songs = 8),
            album("single", songs = 1, played = 1, daysAgo = 1),
        )
        assertEquals("most recently played first", listOf("one", "half"), ids(neverFinished(albums, { it })))
    }

    // Unplayed albums

    @Test
    fun unplayedAlbumsComeNewestAddedFirst() {
        val albums = listOf(
            album("old", addedDaysAgo = 300),
            album("played", played = 2, daysAgo = 1, addedDaysAgo = 1),
            album("new", addedDaysAgo = 2),
            album("undated"),
            album("countedOnly", plays = 3),
        )
        assertEquals(listOf("new", "old", "undated"), ids(neverPlayed(albums, { it })))
    }

    // Most played

    @Test
    fun mostPlayedLeavesOutTheUnplayedAndBreaksTiesByTheLatestPlay() {
        val albums = listOf(album("a", played = 3, plays = 9, daysAgo = 20), album("b", plays = 0), album("c", played = 3, plays = 9, daysAgo = 2), album("d", played = 1, plays = 30, daysAgo = 90))
        assertEquals(listOf("d", "c", "a"), ids(mostPlayedAlbums(albums, { it })))
    }

    // The shelves together

    @Test
    fun anAlbumShowsOnOneShelfOnlyAndEachShelfKeepsToItsLimit() {
        val albums = listOf(
            album("oldHalf", songs = 10, played = 5, daysAgo = 300),
            album("newHalf", songs = 10, played = 5, daysAgo = 5),
            album("oldFull", songs = 10, played = 10, daysAgo = 250),
            album("u1", addedDaysAgo = 1),
            album("u2", addedDaysAgo = 2),
            album("u3", addedDaysAgo = 3),
        )
        val shelves = rediscovery(albums, { it }, now, limit = 2)
        assertEquals("the most played first", listOf("oldFull", "oldHalf"), ids(shelves.notPlayedLately))
        assertEquals("the old unfinished album is already on the shelf above", listOf("newHalf"), ids(shelves.neverFinished))
        assertEquals(listOf("u1", "u2"), ids(shelves.neverPlayed))
    }

    @Test
    fun anEmptyLibraryHasNoShelves() {
        assertTrue(rediscovery(emptyList<AlbumListening>(), { it }, now, 20).isEmpty)
    }

    @Test
    fun theSameLibraryAlwaysGivesTheSameShelves() {
        val albums = (1..40).map { album("a$it", songs = 10, played = it % 11, plays = (it % 7).toLong(), daysAgo = if (it % 11 == 0) null else (it * 13L), addedDaysAgo = (it * 3L) % 17) }
        val first = rediscovery(albums, { it }, now, 12)
        repeat(3) { assertEquals(first, rediscovery(albums.shuffled(), { it }, now, 12)) }
    }

    // From a Subsonic library

    @Test
    fun listeningIsReadFromTheSongs() {
        val albums = listOf(
            Album("a1", songCount = 99, created = "2026-01-02T00:00:00Z"),
            Album("a2", songCount = 5, playCount = 7, played = "2026-03-01T00:00:00Z"),
        )
        val songs = listOf(
            Song("s1", albumId = "a1", playCount = 3, played = "2026-09-01T10:00:00Z"),
            Song("s2", albumId = "a1", playCount = 0),
            Song("s3", albumId = "a1", played = "2026-09-20T10:00:00Z"),
            Song("s4", albumId = null, playCount = 50),
        )
        val listening = albumListening(albums, songs)
        val a1 = listening.getValue("a1")
        assertEquals("the songs listed, not the album's own count", 3, a1.songs)
        assertEquals(2, a1.playedSongs)
        assertEquals(3L, a1.plays)
        assertEquals(Instant.parse("2026-09-20T10:00:00Z").toEpochMilli(), a1.lastPlayed)
        assertEquals(Instant.parse("2026-01-02T00:00:00Z").toEpochMilli(), a1.added)
        val a2 = listening.getValue("a2")
        assertEquals("no songs listed: the album's own counts", 5, a2.songs)
        assertEquals(7L, a2.plays)
        assertEquals(Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(), a2.lastPlayed)
        assertTrue(a2.played)
    }
}
