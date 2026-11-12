package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// The pool's sample is fair: with 6,000 songs that could fit, over 300
// seeded draws every song is kept about two times in three, each count
// within six standard deviations of the binomial mean; and while no more
// than RADIO_CANDIDATES are offered it keeps them all, in order, drawing
// no random number, so the picks after it are today's. Guard of a declared
// change: today shuffled the whole list and took the first 4,000.
class RadioSampleTest {
    @Test
    fun everySongIsEquallyLikelyToBeKept() {
        val ids = (0 until 6_000).map { "s$it" }
        val kept = HashMap<String, Int>()
        repeat(300) { seed ->
            val sample = Reservoir(4_000, Random(seed))
            ids.forEach(sample::offer)
            val picked = sample.kept()
            assertEquals(4_000, picked.toSet().size)
            picked.forEach { kept[it] = (kept[it] ?: 0) + 1 }
        }
        val mean = 300 * 4_000.0 / 6_000
        val sd = kotlin.math.sqrt(300 * (4_000.0 / 6_000) * (2_000.0 / 6_000))
        ids.forEach { id ->
            val n = kept[id] ?: 0
            assertTrue("$id kept $n times, mean $mean", kotlin.math.abs(n - mean) <= 6 * sd)
        }
    }

    @Test
    fun aSmallPoolDrawsNothing() {
        var draws = 0
        val counting = object : Random() {
            override fun nextBits(bitCount: Int): Int {
                draws++
                return 0
            }
        }
        val sample = Reservoir(4_000, counting)
        val ids = (0 until 4_000).map { "s$it" }
        ids.forEach(sample::offer)
        assertEquals(ids, sample.kept())
        assertEquals(0, draws)
        sample.offer("one more")
        assertEquals(4_001, sample.offered)
        assertTrue(draws > 0)
    }
}
