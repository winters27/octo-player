package app.winters.octo.listening

import app.winters.octo.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScrobblesTest {
    @Test
    fun waitingPlaysGoOldestFirst() {
        val queue = emptyList<PendingPlay>()
            .plusPlay(PendingPlay("b", 200))
            .plusPlay(PendingPlay("a", 100))
        assertEquals(listOf(100L, 200L), queue.map { it.startedAt })
    }

    @Test
    fun theSamePlayWaitsOnce() {
        val queue = emptyList<PendingPlay>().plusPlay(PendingPlay("a", 100)).plusPlay(PendingPlay("a", 100))
        assertEquals(1, queue.size)
    }

    @Test
    fun aFullQueueLetsTheOldestGo() {
        val queue = (1..MAX_PENDING_PLAYS + 5).fold(emptyList<PendingPlay>()) { q, i -> q.plusPlay(PendingPlay("s", i.toLong())) }
        assertEquals(MAX_PENDING_PLAYS, queue.size)
        assertEquals(6L, queue.first().startedAt)
    }

    @Test
    fun waitingPlaysRoundTrip() {
        val plays = listOf(PendingPlay("a", 100), PendingPlay("id with spaces", 200))
        assertEquals(plays, decodePending(encodePending(plays)))
    }

    @Test
    fun brokenWaitingLinesAreSkipped() {
        assertEquals(emptyList<PendingPlay>(), decodePending(setOf("", "x a", "100", "100 ")))
    }

    @Test
    fun onlyPassingTroubleIsRetried() {
        assertTrue(worthRetrying(SubsonicException.Unreachable(IOException("offline"))))
        assertTrue(worthRetrying(SubsonicException.NotSubsonic("HTTP 502 from scrobble")))
        assertTrue(worthRetrying(SubsonicException.WrongCredentials("Wrong username or password")))
        assertFalse(worthRetrying(SubsonicException.NotFound("Song not found")))
        assertFalse(worthRetrying(SubsonicException.Server(0, "Failed")))
    }
}
