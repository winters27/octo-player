package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.keptFind
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A find's length, learned from the player when the server sent none.
class FindLengthsTest {
    private fun find(id: String, ms: Long, requestedAt: Long = 0, adoptedId: String = "") = OnlineSongEntity(
        id = id, sourceId = "server:x", nativeId = id.removePrefix("find:"), title = "Song", artist = "Artist",
        album = "", albumId = null, artistId = null, durationMs = ms, coverId = null, mimeType = "audio/mp4",
        bitrate = 128, seenAt = 1, requestedAt = requestedAt, adoptedId = adoptedId,
    )

    @Test
    fun onlyAnUnknownLengthIsLearned() {
        assertEquals(215_000L, lengthToLearn(knownMs = 0, playerMs = 215_000))
        assertNull(lengthToLearn(knownMs = 200_000, playerMs = 215_000))
    }

    @Test
    fun aLengthThatIsNotASongsIsNotKept() {
        assertNull(lengthToLearn(0, playerMs = 0))
        assertNull(lengthToLearn(0, playerMs = -1))
        assertNull(lengthToLearn(0, playerMs = 400))
        assertNull(lengthToLearn(0, playerMs = 4 * 60 * 60_000L))
    }

    @Test
    fun aRowShowsTheKnownLengthFirst() {
        assertEquals(200_000L, shownLengthMs(200_000, learnedMs = 215_000))
        assertEquals(215_000L, shownLengthMs(0, learnedMs = 215_000))
        assertEquals(0L, shownLengthMs(0, learnedMs = null))
    }

    @Test
    fun theServerSendingNoLengthAgainKeepsTheLearnedOne() {
        val stored = find("find:a", ms = 215_000, requestedAt = 5, adoptedId = "t-1")
        val kept = keptFind(find("find:a", ms = 0), stored)
        assertEquals(215_000L, kept.durationMs)
        assertEquals(5L, kept.requestedAt)
        assertEquals("t-1", kept.adoptedId)
        // A length the server does send wins.
        assertEquals(190_000L, keptFind(find("find:a", ms = 190_000), stored).durationMs)
    }

    // What the server sends again for a find already stored, as the app
    // reads it: a placeholder of exactly three minutes reads as unknown.
    private fun refreshed(seconds: Int) = Song(id = "a", title = "Song", artist = "Artist", duration = seconds).toFind("server:x", now = 2)

    @Test
    fun aKnownLengthFromTheServerReplacesAnUnknownOne() {
        val stored = find("find:a", ms = 0)
        assertEquals(212_000L, keptFind(refreshed(212), stored).durationMs)
    }

    @Test
    fun noLengthOrThePlaceholderNeverReplacesAKnownOne() {
        // Learned from the player, or sent by the server before.
        val stored = find("find:a", ms = 215_000)
        assertEquals(215_000L, keptFind(refreshed(0), stored).durationMs)
        assertEquals(215_000L, keptFind(refreshed(180), stored).durationMs)
        // And an unknown one stays unknown.
        assertEquals(0L, keptFind(refreshed(180), find("find:a", ms = 0)).durationMs)
    }

    // Storage that, like the real one, only fills a length that is zero.
    private class Lengths(vararg rows: Pair<String, Long>) {
        val rows = mutableMapOf(*rows)
        var asked = 0

        suspend fun fill(id: String, ms: Long): Int {
            asked++
            if (rows[id] != 0L) return 0
            rows[id] = ms
            return 1
        }
    }

    @Test
    fun aLearnedLengthIsStoredAndShownOnlyInPlaceOfZero() = runBlocking {
        val storage = Lengths("find:a" to 0L, "find:b" to 180_000L)
        val lengths = FindLengths(storage::fill)
        lengths.learn("find:a", 215_000)
        lengths.learn("find:b", 215_000)
        assertEquals(215_000L, storage.rows["find:a"])
        // One that had a length keeps it, in storage and on screen.
        assertEquals(180_000L, storage.rows["find:b"])
        assertEquals(mapOf("find:a" to 215_000L), lengths.learned.value)
    }

    @Test
    fun librarySongsAndRepeatsAskNothing() = runBlocking {
        val storage = Lengths("find:a" to 0L)
        val lengths = FindLengths(storage::fill)
        lengths.learn("t-42", 215_000)
        lengths.learn("find:a", 215_000)
        lengths.learn("find:a", 216_000)
        assertEquals(1, storage.asked)
        assertEquals(215_000L, storage.rows["find:a"])
    }
}
