package app.winters.octo.server

import app.winters.octo.playback.QueueSnapshot
import app.winters.octo.subsonic.PlayQueue
import app.winters.octo.subsonic.PlayQueueByIndex
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSyncTest {
    private fun snapshot(ids: List<String>, index: Int, positionMs: Long = 5_000, shuffle: Boolean = false, order: List<Int> = emptyList()) =
        QueueSnapshot(ids, order, index, positionMs, repeatMode = 0, shuffle = shuffle)

    // Songs "a" to "e" are on the server as "sa" to "se", except the ones named here.
    private fun server(vararg missing: String): (String) -> String? = { id -> if (id in missing) null else "s$id" }

    @Test
    fun theQueueGoesAsItIs() {
        assertEquals(
            ServerQueue(listOf("sa", "sb", "sc"), 1, 5_000),
            serverQueueOf(snapshot(listOf("a", "b", "c"), 1), server()),
        )
    }

    @Test
    fun songsNotOnTheServerAreLeftOut() {
        assertEquals(
            ServerQueue(listOf("sa", "sc"), 1, 5_000),
            serverQueueOf(snapshot(listOf("a", "b", "c"), 2), server("b")),
        )
    }

    @Test
    fun aCurrentSongNotOnTheServerHandsOverToTheNextOneFromItsStart() {
        assertEquals(
            ServerQueue(listOf("sa", "sc"), 1, 0),
            serverQueueOf(snapshot(listOf("a", "b", "c"), 1), server("b")),
        )
        // With nothing after it, the last one that is there.
        assertEquals(
            ServerQueue(listOf("sa"), 0, 0),
            serverQueueOf(snapshot(listOf("a", "b", "c"), 2), server("b", "c")),
        )
    }

    @Test
    fun nothingOnTheServerSavesNothing() {
        assertNull(serverQueueOf(snapshot(listOf("a", "b"), 0), server("a", "b")))
        assertNull(serverQueueOf(snapshot(emptyList(), 0), server()))
    }

    @Test
    fun aShuffledQueueGoesInTheOrderItWillPlay() {
        // Playing "c" (position 2), which is second in shuffle order.
        val shuffled = snapshot(listOf("a", "b", "c", "d"), 2, shuffle = true, order = listOf(3, 2, 0, 1))
        assertEquals(ServerQueue(listOf("sd", "sc", "sa", "sb"), 1, 5_000), serverQueueOf(shuffled, server()))
        // An order that does not fit the queue is ignored.
        val broken = snapshot(listOf("a", "b", "c"), 0, shuffle = true, order = listOf(0, 0, 1))
        assertEquals(ServerQueue(listOf("sa", "sb", "sc"), 0, 5_000), serverQueueOf(broken, server()))
    }

    @Test
    fun duplicatesStayInTheQueue() {
        assertEquals(
            ServerQueue(listOf("sa", "sb", "sa"), 2, 5_000),
            serverQueueOf(snapshot(listOf("a", "b", "a"), 2), server()),
        )
    }

    @Test
    fun aLongQueueIsCutDownAroundTheCurrentSong() {
        val ids = List(1000) { "s$it" }
        val middle = ServerQueue(ids, 500, 7_000).trimmed(400)
        assertEquals(400, middle.ids.size)
        assertEquals("s450", middle.ids.first())
        assertEquals("s500", middle.ids[middle.index])
        assertEquals(7_000L, middle.positionMs)
        // Near the start or the end, the window stays full.
        assertEquals("s0", ServerQueue(ids, 10, 0).trimmed(400).ids.first())
        val end = ServerQueue(ids, 990, 0).trimmed(400)
        assertEquals("s999", end.ids.last())
        assertEquals("s990", end.ids[end.index])
        // A short queue is left alone.
        val short = ServerQueue(listOf("a", "b"), 1, 0)
        assertEquals(short, short.trimmed(400))
    }

    private fun song(id: String) = Song(id = id, title = id.uppercase())

    @Test
    fun readsARemoteQueueEitherWay() {
        val byIndex = remoteQueueOf(
            PlayQueueByIndex(listOf(song("x"), song("y")), currentIndex = 1, position = 30_000, changed = "2026-09-26T10:00:00Z", changedBy = "Desktop"),
        )
        assertEquals("y", byIndex.current?.id)
        assertEquals(30_000L, byIndex.positionMs)
        assertEquals(1_790_416_800_000L, byIndex.changedAt)

        val legacy = remoteQueueOf(PlayQueue(listOf(song("x"), song("y")), current = "y", position = 12_000, changedBy = "Web"))
        assertEquals(1, legacy.index)
        assertEquals(12_000L, legacy.positionMs)

        // A current song that is not in the list starts the queue from the top.
        val lost = remoteQueueOf(PlayQueue(listOf(song("x")), current = "gone", position = 12_000))
        assertEquals(0, lost.index)
        assertEquals(0L, lost.positionMs)
    }

    @Test
    fun readsServerTimes() {
        assertEquals(1_790_416_800_000L, serverTime("2026-09-26T10:00:00Z"))
        assertEquals(1_790_416_800_500L, serverTime("2026-09-26T10:00:00.5Z"))
        assertEquals(1_790_416_800_000L, serverTime("2026-09-26T12:00:00+02:00"))
        assertNull(serverTime("yesterday"))
        assertNull(serverTime(null))
    }

    private fun remote(changedAt: Long?, by: String?, songs: List<Song> = listOf(Song("x"))) =
        RemoteQueue(songs, 0, 0, changedAt, by)

    @Test
    fun offersAQueueSavedLaterElsewhere() {
        assertTrue(shouldOfferResume(remote(2_000, "Desktop"), lastSavedAt = 1_000, answeredUpTo = 0, ownStamp = 500))
        // Another phone running this app has the same name, and still counts.
        assertTrue(shouldOfferResume(remote(2_000, "Octo"), 1_000, 0, ownStamp = 500))
        // A saver with no name is still offered.
        assertTrue(shouldOfferResume(remote(2_000, null), 1_000, 0, ownStamp = null))
    }

    @Test
    fun doesNotOfferOurOwnQueue() {
        assertFalse(shouldOfferResume(remote(2_000, "Octo"), 1_000, 0, ownStamp = 2_000))
    }

    @Test
    fun doesNotOfferAQueueOlderThanOurLastSave() {
        assertFalse(shouldOfferResume(remote(1_000, "Desktop"), 2_000, 0, null))
        assertFalse(shouldOfferResume(remote(1_000, "Desktop"), 1_000, 0, null))
    }

    @Test
    fun doesNotOfferAQueueAlreadyAnswered() {
        assertFalse(shouldOfferResume(remote(2_000, "Desktop"), 1_000, answeredUpTo = 2_000, ownStamp = null))
        // A newer one from the same device is offered again.
        assertTrue(shouldOfferResume(remote(3_000, "Desktop"), 1_000, answeredUpTo = 2_000, ownStamp = null))
    }

    @Test
    fun doesNotOfferWhatCannotBeToldApart() {
        assertFalse(shouldOfferResume(null, 0, 0, null))
        assertFalse(shouldOfferResume(remote(null, "Desktop"), 0, 0, null))
        assertFalse(shouldOfferResume(remote(2_000, "Desktop", songs = emptyList()), 0, 0, null))
    }
}
