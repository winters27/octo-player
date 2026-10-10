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

    // Listed at 296 s, the song's sound is 291 s: once the player has
    // measured it, half of 291 s counts, and the play carries 291 s.
    @Test
    fun theMeasuredLengthReplacesTheListedOne() {
        val lengths = mutableListOf<Long>()
        val tracker = PlayTracker(
            started = {},
            record = { id, _, heard, length -> if (countsAsPlay(heard, length)) { records += id to heard; lengths += length } },
            isPlaying = { playing },
            clock = { now },
            wallClock = { now },
            later = { ms, block -> waiting = ms to block; { waiting = null } },
        )
        tracker.moved("a", 296_000)
        assertEquals(148_000L, waiting?.first)
        // Another song's length changes nothing.
        tracker.lengthFound("b", 200_000)
        assertEquals(148_000L, waiting?.first)
        tracker.lengthFound("a", 291_000)
        assertEquals(145_500L, waiting?.first)
        now += 145_500
        waiting!!.second()
        assertEquals(listOf("a" to 145_500L), records)
        assertEquals(listOf(291_000L), lengths)
    }

    @Test
    fun aSongSkippedEarlyNeverCounts() {
        tracker.moved("a", 200_000)
        now += 10_000
        tracker.moved("b", 200_000)
        assertEquals(emptyList<Pair<String, Long>>(), records)
    }
}
