package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.keptFind
import app.winters.octo.playback.RealLengths
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// A find's length, learned from the player when the server sent none.
class FindLengthsTest {
    private fun find(id: String, ms: Long, requestedAt: Long = 0, adoptedId: String = "") = OnlineSongEntity(
        id = id, sourceId = "server:x", nativeId = id.removePrefix("find:"), title = "Song", artist = "Artist",
        album = "", albumId = null, artistId = null, durationMs = ms, coverId = null, mimeType = "audio/mp4",
        bitrate = 128, seenAt = 1, requestedAt = requestedAt, adoptedId = adoptedId,
    )

    // A row shows the real length once playing the song found its listing
    // wrong, and the listing until then.
    @Test
    fun aRowShowsTheRealLengthFirst() {
        assertEquals(215_000L, shownLengthMs(200_000, learnedMs = 215_000))
        assertEquals(215_000L, shownLengthMs(0, learnedMs = 215_000))
        assertEquals(200_000L, shownLengthMs(200_000, learnedMs = null))
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

    // Storage of finds' lengths, as the real one keeps them.
    private class Lengths(vararg rows: Pair<String, Long>) {
        val rows = mutableMapOf(*rows)
        var asked = 0

        suspend fun store(id: String, ms: Long): Int {
            asked++
            if (id !in rows) return 0
            rows[id] = ms
            return 1
        }
    }

    // A find listed with no length, or with one more than a second off its
    // sound, takes the player's length, in storage and on screen.
    @Test
    fun aMissingOrWrongLengthIsStoredAndShown() = runBlocking {
        val storage = Lengths("find:a" to 0L, "find:b" to 296_000L, "find:c" to 213_000L)
        val lengths = FindLengths(storage::store, RealLengths())
        lengths.learn("find:a", listedMs = 0, playerMs = 215_000)
        lengths.learn("find:b", listedMs = 296_000, playerMs = 291_000)
        lengths.learn("find:c", listedMs = 213_000, playerMs = 213_400)
        assertEquals(215_000L, storage.rows["find:a"])
        assertEquals(291_000L, storage.rows["find:b"])
        // Within a second: the listing was right.
        assertEquals(213_000L, storage.rows["find:c"])
        assertEquals(mapOf("find:a" to 215_000L, "find:b" to 291_000L), lengths.learned.value)
    }

    @Test
    fun aLengthThatIsNotASongsIsNotKept() = runBlocking {
        val storage = Lengths("find:a" to 0L)
        val lengths = FindLengths(storage::store, RealLengths())
        lengths.learn("find:a", 0, playerMs = 0)
        lengths.learn("find:a", 0, playerMs = -1)
        lengths.learn("find:a", 0, playerMs = 400)
        lengths.learn("find:a", 0, playerMs = 25 * 60 * 60_000L)
        assertEquals(0, storage.asked)
        assertEquals(emptyMap<String, Long>(), lengths.learned.value)
    }

    // A library song's wrong listing is corrected on screen only (the next
    // library read would undo a stored one); repeats store nothing again.
    @Test
    fun librarySongsAndRepeatsStoreNothing() = runBlocking {
        val storage = Lengths("find:a" to 0L)
        val real = RealLengths()
        val lengths = FindLengths(storage::store, real)
        lengths.learn("t-42", listedMs = 200_000, playerMs = 215_000)
        lengths.learn("find:a", listedMs = 0, playerMs = 215_000)
        lengths.learn("find:a", listedMs = 0, playerMs = 215_000)
        assertEquals(1, storage.asked)
        assertEquals(215_000L, storage.rows["find:a"])
        assertEquals(215_000L, real.lengthMs("t-42", 200_000))
    }
}
