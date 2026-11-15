package app.winters.octo.playback

import app.winters.octo.subsonic.StreamVersionChange
import app.winters.octo.subsonic.StreamVersionChanges
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.versions.StreamVersionCursor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// The change feed: every outside song goes on the first read and on a new
// epoch or an incomplete answer, the listed ids after; one read at a time,
// askers waiting for it; a failed read keeps the cursor; each server keeps
// its own; a play is checked once before it reads.
class StreamVersionFeedTest {
    private class Port : FeedPort {
        override val signedIn = MutableStateFlow<String?>(null)
        var source: String? = "server:a"
        val asked = mutableListOf<Triple<String, String?, Long?>>()
        var answer: suspend () -> StreamVersionChanges = { error("no answer") }
        val cursors = HashMap<String, StreamVersionCursor>()

        override fun server(): String? = source

        override suspend fun changes(sourceId: String, epoch: String?, since: Long?): StreamVersionChanges {
            asked += Triple(sourceId, epoch, since)
            return answer()
        }

        override fun cursor(sourceId: String): StreamVersionCursor? = cursors[sourceId]

        override fun keep(sourceId: String, cursor: StreamVersionCursor) {
            cursors[sourceId] = cursor
        }
    }

    private class Dropper : VersionDropper {
        val dropped = mutableListOf<Pair<String, Set<String>>>()
        val all = mutableListOf<String>()

        override suspend fun drop(sourceId: String, ids: Collection<String>, now: Boolean) {
            dropped += sourceId to ids.toSet()
        }

        override suspend fun dropOutside(sourceId: String) {
            all += sourceId
        }
    }

    @Test
    fun theFirstReadDropsEveryOutsideSongThenOnlyWhatChanged() = runTest {
        val port = Port()
        val dropper = Dropper()
        val feed = StreamVersionFeed(port, dropper, backgroundScope)
        port.answer = { StreamVersionChanges("e1", 7, true, listOf(StreamVersionChange(6, listOf("ext-1", "ext-2")))) }
        feed.refresh()
        assertEquals(listOf(Triple("server:a", null, null)), port.asked)
        assertEquals(listOf("server:a"), dropper.all)
        assertEquals("server:a" to setOf("ext-1", "ext-2"), dropper.dropped.last())
        assertEquals(StreamVersionCursor("e1", 7), port.cursors["server:a"])
        port.answer = { StreamVersionChanges("e1", 8, true, listOf(StreamVersionChange(8, listOf("ext-3")))) }
        feed.refresh()
        assertEquals(Triple<String, String?, Long?>("server:a", "e1", 7L), port.asked.last())
        assertEquals(listOf("server:a"), dropper.all)
        assertEquals("server:a" to setOf("ext-3"), dropper.dropped.last())
    }

    @Test
    fun anotherEpochOrAnIncompleteAnswerDropsEveryOutsideSong() = runTest {
        val port = Port().apply { cursors["server:a"] = StreamVersionCursor("e0", 9) }
        val dropper = Dropper()
        val feed = StreamVersionFeed(port, dropper, backgroundScope)
        port.answer = { StreamVersionChanges("e1", 3, true) }
        feed.refresh()
        port.answer = { StreamVersionChanges("e1", 900, false, listOf(StreamVersionChange(600, listOf("ext-1")))) }
        feed.refresh()
        assertEquals(listOf("server:a", "server:a"), dropper.all)
        assertEquals(StreamVersionCursor("e1", 900), port.cursors["server:a"])
    }

    @Test
    fun oneReadAtATimeAndAskersWaitForIt() = runTest {
        val port = Port()
        val feed = StreamVersionFeed(port, Dropper(), backgroundScope)
        val gate = CompletableDeferred<StreamVersionChanges>()
        port.answer = { gate.await() }
        val first = async { feed.refresh() }
        val second = async { feed.refresh() }
        runCurrent()
        assertEquals(1, port.asked.size)
        assertFalse(first.isCompleted || second.isCompleted)
        gate.complete(StreamVersionChanges("e1", 1, true))
        first.await()
        second.await()
        assertEquals(1, port.asked.size)
        feed.refresh()
        assertEquals(2, port.asked.size)
    }

    @Test
    fun aFailedReadKeepsTheCursorAndDropsNothing() = runTest {
        val port = Port().apply { cursors["server:a"] = StreamVersionCursor("e1", 4) }
        val dropper = Dropper()
        val feed = StreamVersionFeed(port, dropper, backgroundScope)
        port.answer = { throw SubsonicException.Unreachable(IOException("down")) }
        feed.refresh()
        assertEquals(StreamVersionCursor("e1", 4), port.cursors["server:a"])
        assertTrue(dropper.all.isEmpty() && dropper.dropped.isEmpty())
    }

    @Test
    fun eachServerKeepsItsOwnCursorAndNoServerReadsNothing() = runTest {
        val port = Port()
        val dropper = Dropper()
        val feed = StreamVersionFeed(port, dropper, backgroundScope)
        port.answer = { StreamVersionChanges("e1", 2, true) }
        feed.refresh()
        port.source = "server:b"
        feed.refresh()
        assertEquals(Triple<String, String?, Long?>("server:b", null, null), port.asked.last())
        assertEquals(listOf("server:a", "server:b"), dropper.all)
        port.source = null
        feed.refresh()
        assertEquals(2, port.asked.size)
    }

    @Test
    fun aSignInReadsAndAPlayIsCheckedOnce() = runTest {
        val port = Port()
        val feed = StreamVersionFeed(port, Dropper(), backgroundScope)
        port.answer = { StreamVersionChanges("e1", 1, true) }
        feed.start()
        runCurrent()
        assertTrue(port.asked.isEmpty())
        port.signedIn.value = "server:a"
        runCurrent()
        assertEquals(1, port.asked.size)
        assertTrue(feed.firstOpen("e1.0"))
        assertFalse(feed.firstOpen("e1.0"))
        assertTrue(feed.firstOpen("e1.1"))
    }
}
