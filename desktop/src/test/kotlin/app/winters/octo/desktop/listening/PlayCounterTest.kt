package app.winters.octo.desktop.listening

import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayCounterTest {
    private var now = 0L
    private val started = mutableListOf<String>()
    private val counted = mutableListOf<HeardPlay>()
    private val counter = PlayCounter({ started += it.id }, { counted += it }, clock = { now }, wallClock = { 1_000_000 + now })

    // Songs of 200 seconds, so half of one is 100 seconds.
    private val a = QueueEntry(1, Song("a", "A", duration = 200))
    private val b = QueueEntry(2, Song("b", "B", duration = 200))

    private fun on(entry: QueueEntry?, playing: Boolean = true, buffering: Boolean = false, rounds: Int = 0) =
        counter.update(PlayerState(current = entry, playing = playing, buffering = buffering, rounds = rounds))

    @Test
    fun halfASongHeardCountsWhenItMovesOn() {
        on(a)
        now += 100_000
        on(b)
        assertEquals(listOf("a"), counted.map { it.song.id })
        assertEquals(100_000, counted.single().heardMs)
        assertEquals(1_000_000, counted.single().startedAt)
    }

    // Octo lists many songs found online at 3:00, a guess; half of the
    // file's own length is what counts.
    @Test
    fun aGuessedLengthGivesWayToTheFiles() {
        val found = QueueEntry(3, Song("f", "Found online", duration = 180))
        counter.update(PlayerState(current = found, playing = true, durationMs = 400_000))
        now += 100_000
        on(b)
        assertEquals(emptyList<HeardPlay>(), counted)
    }

    // A song listed longer than its sound: the player's length, the
    // sound's own, is what a play is measured by and reported with.
    @Test
    fun thePlayersLengthBeatsAWrongListing() {
        val listed = QueueEntry(4, Song("o", "Outside", duration = 296))
        counter.update(PlayerState(current = listed, playing = true, durationMs = 291_000))
        assertEquals(145_500L, counter.msUntilCounted())
        now += 145_500
        counter.check()
        assertEquals(291_000, counted.single().durationMs)
    }

    @Test
    fun lessThanHalfDoesNotCount() {
        on(a)
        now += 99_000
        on(b)
        assertEquals(emptyList<HeardPlay>(), counted)
    }

    @Test
    fun pausesAndWaitingForTheNetworkAreNotHearing() {
        on(a)
        now += 60_000
        on(a, playing = false)
        now += 600_000
        on(a, buffering = true)
        now += 30_000
        on(a)
        now += 60_000
        counter.flush()
        assertEquals(120_000, counted.single().heardMs)
    }

    @Test
    fun eachTimeRoundOnRepeatIsItsOwnPlay() {
        on(a)
        now += 200_000
        on(a, rounds = 1)
        now += 200_000
        on(a, rounds = 2)
        assertEquals(2, counted.size)
        assertEquals(listOf("a", "a", "a"), started)
    }

    @Test
    fun aSongIsAnnouncedOnceWhenFirstHeard() {
        on(a, playing = false)
        assertEquals(emptyList<String>(), started)
        on(a)
        on(a, playing = false)
        on(a)
        assertEquals(listOf("a"), started)
    }

    @Test
    fun fourMinutesCountForALongSong() {
        val long = QueueEntry(3, Song("long", "Long", duration = 3_600))
        on(long)
        now += 240_000
        counter.flush()
        assertEquals(listOf("long"), counted.map { it.song.id })
    }

    @Test
    fun aPlayCountsTheMomentEnoughIsHeardAndOnlyOnce() {
        on(a)
        assertEquals(100_000L, counter.msUntilCounted())
        now += 100_000
        counter.check()
        assertEquals(listOf("a"), counted.map { it.song.id })
        assertEquals(100_000, counted.single().heardMs)
        assertNull(counter.msUntilCounted())
        now += 50_000
        on(b)
        assertEquals(listOf("a"), counted.map { it.song.id })
    }

    @Test
    fun nothingIsDueWhilePausedAndTheWaitResumes() {
        on(a)
        now += 30_000
        on(a, playing = false)
        assertNull(counter.msUntilCounted())
        on(a)
        assertEquals(70_000L, counter.msUntilCounted())
    }

    @Test
    fun checkingTooEarlyCountsNothing() {
        on(a)
        now += 99_000
        counter.check()
        assertEquals(emptyList<HeardPlay>(), counted)
    }

    @Test
    fun eachRoundOnRepeatCountsOnceAtItsMoment() {
        on(a)
        now += 100_000
        counter.check()
        now += 100_000
        on(a, rounds = 1)
        now += 100_000
        counter.check()
        on(b)
        assertEquals(2, counted.size)
    }

    @Test
    fun shortClipsNeverCount() {
        val clip = QueueEntry(4, Song("clip", "Clip", duration = 20))
        on(clip)
        now += 20_000
        on(null)
        assertEquals(emptyList<HeardPlay>(), counted)
    }
}
