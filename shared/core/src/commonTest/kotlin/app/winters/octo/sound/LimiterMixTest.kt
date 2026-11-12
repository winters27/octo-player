package app.winters.octo.sound

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// The limiter as the mix of two songs uses it: a ramped attack, snapshots
// for running a stretch again, and a reset to unity.
class LimiterMixTest {
    private val rate = 48_000
    private val ceiling = 10f.pow(LIMITER_CEILING_DB / 20f)

    // Quiet, then stereo noise at twice full scale.
    private val loud: FloatArray = run {
        val noise = Random(7)
        FloatArray(9_600 * 2) { i ->
            val frame = i / 2
            if (frame < 4_800) (0.3 * sin(2 * PI * 440 * frame / rate)).toFloat() else 2f * (noise.nextFloat() * 2f - 1f)
        }
    }

    private class Run(val levels: FloatArray, val out: FloatArray)

    // One frame at a time, keeping the level after each, then the drain.
    private fun runFrames(limiter: Limiter, input: FloatArray, offWhen: Int = -1): Run {
        val frames = input.size / 2
        val levels = FloatArray(frames)
        val out = FloatArray(input.size + limiter.latency * 2)
        val one = FloatArray(2)
        val got = FloatArray(2)
        var written = 0
        for (frame in 0 until frames) {
            if (frame == offWhen) limiter.enabled = false
            one[0] = input[frame * 2]
            one[1] = input[frame * 2 + 1]
            if (limiter.process(one, 1, got) == 1) {
                out[written * 2] = got[0]
                out[written * 2 + 1] = got[1]
                written++
            }
            levels[frame] = limiter.level
        }
        val tail = FloatArray(limiter.latency * 2)
        val drained = limiter.drain(tail)
        tail.copyInto(out, written * 2, 0, drained * 2)
        return Run(levels, out)
    }

    private fun largestDrop(levels: FloatArray): Float {
        var drop = 0f
        for (i in 1 until levels.size) drop = max(drop, levels[i - 1] - levels[i])
        return drop
    }

    @Test
    fun theRampedAttackNeverStepsTheLevelAndHoldsTheCeiling() {
        val ramped = runFrames(Limiter(2, rate, rampedAttack = true), loud)
        val instant = runFrames(Limiter(2, rate), loud)
        // The deepest need is the ceiling over twice full scale; its straight
        // line down across the lookahead falls by this much a frame at most.
        val slope = (1f - ceiling / 2f) / Limiter(2, rate).latency
        assertTrue("ramped drop ${largestDrop(ramped.levels)}", largestDrop(ramped.levels) <= slope + 1e-6f)
        assertTrue("the instant attack does step", largestDrop(instant.levels) > 0.3f)
        assertTrue(ramped.out.all { abs(it) <= ceiling })
        assertTrue("held near the ceiling", ramped.out.maxOf { abs(it) } > ceiling * 0.95f)
    }

    @Test
    fun turningOffMidReductionNeverStepsTheLevel() {
        val limiter = Limiter(2, rate, rampedAttack = true)
        val levels = runFrames(limiter, loud, offWhen = 7_000).levels
        assertTrue(levels[6_999] < 0.9f)
        var rise = 0f
        for (i in 7_001 until levels.size) rise = max(rise, levels[i] - levels[i - 1])
        assertTrue("rise $rise", rise < 1e-3f)
        assertTrue(largestDrop(levels) <= (1f - ceiling / 2f) / limiter.latency + 1e-6f)
    }

    @Test
    fun aCopyRunsOnExactlyAsTheOriginal() {
        for (ramped in listOf(false, true)) {
            val original = Limiter(2, rate, rampedAttack = ramped)
            original.process(loud, 6_000, FloatArray(6_000 * 2))
            val copy = Limiter(2, rate, rampedAttack = ramped)
            copy.copyFrom(original)
            val rest = loud.copyOfRange(6_000 * 2, loud.size)
            val frames = rest.size / 2
            val a = FloatArray(rest.size)
            val b = FloatArray(rest.size)
            assertEquals(original.process(rest, frames, a), copy.process(rest, frames, b))
            assertArrayEquals(a, b, 0f)
            assertEquals(original.level, copy.level, 0f)
        }
    }

    @Test
    fun aCopyNeedsALimiterBuiltAlike() {
        assertThrows(IllegalArgumentException::class.java) { Limiter(2, rate).copyFrom(Limiter(2, rate, rampedAttack = true)) }
        assertThrows(IllegalArgumentException::class.java) { Limiter(2, rate).copyFrom(Limiter(1, rate)) }
    }

    @Test
    fun aResetStartsOverAtUnity() {
        val limiter = Limiter(2, rate, rampedAttack = true)
        limiter.process(loud, loud.size / 2, FloatArray(loud.size))
        assertTrue(limiter.level < 0.9f)
        limiter.reset()
        assertEquals(1f, limiter.level, 0f)
        val song = FloatArray(4_800 * 2) { (0.85 * sin(2 * PI * 1_000 * (it / 2) / rate)).toFloat() }
        val out = FloatArray(song.size)
        val written = limiter.process(song, song.size / 2, out)
        assertEquals(song.size / 2 - limiter.latency, written)
        // The delay fills from the song's own first frames, then the song
        // comes out untouched.
        for (i in 0 until written * 2) assertEquals("sample $i", song[i], out[i], 0f)
    }
}
