package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private var playing = true
    private val records = mutableListOf<Pair<String, Long>>()
    private var waiting: Pair<Long, () -> Unit>? = null
    private val tracker = PlayTracker(
        started = {},
        // As PlayStore does: only a play that counts is kept.
        record = { id, _, heard, length -> if (countsAsPlay(heard, length)) records += id to heard },
        isPlaying = { playing },
        clock = { now },
        wallClock = { now },
        later = { ms, block -> waiting = ms to block; { waiting = null } },
    )

    @Test
    fun aSongCountsTheMomentHalfIsHeardAndOnlyOnce() {
        tracker.moved("a", 200_000)
        assertEquals(100_000L, waiting?.first)
        now += 100_000
        waiting!!.second()
        assertEquals(listOf("a" to 100_000L), records)
        now += 50_000
        tracker.moved("b", 200_000)
        assertEquals(1, records.size)
    }

    @Test
    fun pausingCallsTheWaitOffAndPlayingResumesIt() {
        tracker.moved("a", 200_000)
        now += 30_000
        playing = false
        tracker.playingChanged(false)
        assertNull(waiting)
        playing = true
        tracker.playingChanged(true)
        assertEquals(70_000L, waiting?.first)
    }

    @Test
    fun aSongSkippedEarlyNeverCounts() {
        tracker.moved("a", 200_000)
        now += 10_000
        tracker.moved("b", 200_000)
        assertEquals(emptyList<Pair<String, Long>>(), records)
    }
}
