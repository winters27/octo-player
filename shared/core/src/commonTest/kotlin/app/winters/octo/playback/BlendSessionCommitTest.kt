package app.winters.octo.playback

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The commit word main and the audio thread share, and the memory of a
// spare song that failed.
class BlendSessionCommitTest {
    @Test
    fun theWordPacksTheGenerationAndTheState() {
        for (generation in listOf(0L, 1L, 77L, (1L shl 61) - 1)) {
            for (state in CommitState.entries) {
                val word = commitWordOf(generation, state)
                assertTrue(word >= 0)
                assertEquals(generation, commitGenerationOf(word))
                assertEquals(state, commitStateOf(word))
            }
        }
    }

    @Test
    fun aStartMovesOnlyForwardAndAStaleGenerationNeverMatches() {
        val word = CommitWord()
        assertEquals(CommitState.CANCELLED, word.stateOf(0))
        assertNull(word.stateOf(1))

        word.open(1)
        assertFalse("not proposed yet", word.ack(1))
        assertTrue(word.propose(1))
        assertFalse(word.propose(1))
        assertTrue(word.ack(1))
        assertFalse("handed over", word.cancel(1))
        assertEquals(CommitState.ACKED, word.stateOf(1))

        word.open(2)
        assertNull(word.stateOf(1))
        assertFalse(word.ack(1))
        assertFalse(word.cancel(1))
        assertTrue("an unproposed start cancels", word.cancel(2))
        assertFalse(word.propose(2))
        assertFalse(word.cancel(2))

        word.open(3)
        assertTrue(word.propose(3))
        assertTrue("a proposed start cancels", word.cancel(3))
        assertFalse(word.ack(3))
        assertEquals(CommitState.CANCELLED, word.stateOf(3))
    }

    @Test
    fun mainsAckAndTheFloorsCancelNeverBothWin() {
        val word = CommitWord()
        val rounds = 5_000
        val acked = BooleanArray(rounds)
        val cancelled = BooleanArray(rounds)
        var generation = 0L
        // Each round opens and proposes a fresh generation, then lets both
        // threads go at once.
        val start = CyclicBarrier(2) {
            generation++
            word.open(generation)
            word.propose(generation)
        }
        val main = thread {
            for (round in 0 until rounds) {
                start.await()
                acked[round] = word.ack(round + 1L)
            }
        }
        val audio = thread {
            for (round in 0 until rounds) {
                start.await()
                cancelled[round] = word.cancel(round + 1L)
            }
        }
        main.join()
        audio.join()
        for (round in 0 until rounds) assertNotEquals("round $round", acked[round], cancelled[round])
    }

    @Test
    fun aFailedSpareIsNotLoadedAgainUntilTheSongChanges() {
        val memory = BlendFailedSpare()
        assertTrue(memory.mayLoad("b"))
        memory.failed("b")
        assertFalse(memory.mayLoad("b"))
        assertTrue(memory.mayLoad("c"))
        memory.songChanged()
        assertTrue(memory.mayLoad("b"))
    }
}
