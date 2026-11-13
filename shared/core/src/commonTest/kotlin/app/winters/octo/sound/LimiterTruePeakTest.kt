package app.winters.octo.sound

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// The mix limiter reading the sound between samples (its true peak), and
// every limiter built without it holding samples exactly as before.
class LimiterTruePeakTest {
    private val rate = 48_000
    private val ceiling = 10f.pow(LIMITER_CEILING_DB / 20f)

    // Two songs at 12 kHz, a quarter of the sample rate, whose samples fall
    // halfway between the waveform's peaks: every sample of their sum stays
    // at -2 dBFS, a dB under the ceiling, while the sound between samples
    // reaches +1 dBFS. The two channels are opposite.
    private fun quarterRateSum(frames: Int): FloatArray = FloatArray(frames * 2) { i ->
        val wave = sin(PI / 2 * (i / 2) + PI / 4)
        val sum = (0.6 * wave + 0.52 * wave).toFloat()
        if (i % 2 == 0) sum else -sum
    }

    // Every frame through `limiter`, the delay drained at the end, so output
    // frame i is input frame i.
    private fun limited(limiter: Limiter, input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        val written = limiter.process(input, input.size / 2, out)
        val tail = FloatArray(limiter.latency * 2)
        val drained = limiter.drain(tail)
        tail.copyInto(out, written * 2, 0, drained * 2)
        return out
    }

    // The true peak of stereo from frame `from` to `to`: the samples, and the
    // sound read at 7 points between each two, each through a windowed sinc
    // (Blackman) 48 samples either side. Finer and longer than the
    // limiter's own reading, so it can check it.
    private fun truePeakDb(stereo: FloatArray, from: Int, to: Int): Double {
        val steps = 8
        val half = 48
        val frames = stereo.size / 2
        val taps = Array(steps - 1) { p ->
            val along = (p + 1).toDouble() / steps
            val raw = DoubleArray(half * 2) { j ->
                val d = along - (j - half + 1)
                val window = 0.42 + 0.5 * cos(PI * d / half) + 0.08 * cos(2 * PI * d / half)
                sin(PI * d) / (PI * d) * window
            }
            val sum = raw.sum()
            DoubleArray(raw.size) { raw[it] / sum }
        }
        var peak = 0.0
        for (ch in 0 until 2) {
            for (i in from until minOf(to, frames)) {
                peak = max(peak, abs(stereo[i * 2 + ch].toDouble()))
                for (phase in taps) {
                    var y = 0.0
                    for (j in phase.indices) {
                        val at = i + j - half + 1
                        if (at in 0 until frames) y += stereo[at * 2 + ch] * phase[j]
                    }
                    peak = max(peak, abs(y))
                }
            }
        }
        return 20 * log10(peak)
    }

    @Test
    fun theSumsSoundBetweenSamplesStaysUnderTheCeiling() {
        val sum = quarterRateSum(9_600)
        assertTrue("every sample a dB under the ceiling", sum.all { abs(it) < ceiling * 0.9f })
        assertTrue(truePeakDb(sum, 0, 9_600) > 0.9)
        for (ramped in listOf(false, true)) {
            val out = limited(Limiter(2, rate, rampedAttack = ramped, truePeak = true), sum)
            val peak = truePeakDb(out, 2_400, 7_200)
            assertTrue("ramped $ramped: true peak $peak dBTP", peak <= LIMITER_CEILING_DB + 0.02)
            // Turned down by what the sound between samples needs, no further.
            assertTrue("ramped $ramped: true peak $peak dBTP", peak > LIMITER_CEILING_DB - 0.05)
        }
    }

    @Test
    fun theRampedAttackStillNeverStepsTheLevel() {
        // Quiet, then the quarter-rate sum: only the sound between samples
        // needs limiting, and it is seen 31 frames late.
        val input = FloatArray(4_800 * 2) + quarterRateSum(4_800)
        val limiter = Limiter(2, rate, rampedAttack = true, truePeak = true)
        val levels = FloatArray(input.size / 2)
        val one = FloatArray(2)
        val got = FloatArray(2)
        for (frame in levels.indices) {
            one[0] = input[frame * 2]
            one[1] = input[frame * 2 + 1]
            limiter.process(one, 1, got)
            levels[frame] = limiter.level
        }
        var drop = 0f
        for (i in 1 until levels.size) drop = max(drop, levels[i - 1] - levels[i])
        // Each need's line runs from unity across the lookahead less the
        // reading's span, so none falls faster than the line to the deepest
        // need, which the level reaches (where the tone starts, the sound
        // between samples overshoots the steady sum a little).
        val deepest = levels.min()
        val slope = (1f - deepest) / (limiter.latency - 31)
        assertTrue("largest drop $drop, line $slope", drop <= slope + 1e-6f)
        assertTrue("deepest $deepest", deepest < 0.8f)
        assertTrue(levels.last() < 0.81f)
    }

    @Test
    fun aCopyWithTheTruePeakRunsOnExactlyAsTheOriginal() {
        val input = quarterRateSum(9_600)
        for (ramped in listOf(false, true)) {
            val original = Limiter(2, rate, rampedAttack = ramped, truePeak = true)
            original.process(input, 3_000, FloatArray(3_000 * 2))
            val copy = Limiter(2, rate, rampedAttack = ramped, truePeak = true)
            copy.copyFrom(original)
            val rest = input.copyOfRange(3_000 * 2, input.size)
            val a = FloatArray(rest.size)
            val b = FloatArray(rest.size)
            assertEquals(original.process(rest, rest.size / 2, a), copy.process(rest, rest.size / 2, b))
            assertArrayEquals(a, b, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) { Limiter(2, rate).copyFrom(Limiter(2, rate, truePeak = true)) }
    }

    // The sample limiter written out plainly: each frame turned down as far
    // as any frame from it to `latency` ahead needs, down at once, back up
    // slowly and onto unity once the step is lost in rounding, then held at
    // the ceiling.
    private fun sampleLimited(input: FloatArray, latency: Int): FloatArray {
        val release = 1f - exp(-1f / (rate * LIMITER_RELEASE_MS / 1_000f))
        val frames = input.size / 2
        val need = FloatArray(frames) { i ->
            val peak = max(max(0f, abs(input[i * 2])), abs(input[i * 2 + 1]))
            if (peak > ceiling) ceiling / peak else 1f
        }
        var gain = 1f
        val out = FloatArray(input.size)
        for (i in 0 until frames) {
            var target = 1f
            for (j in i..minOf(i + latency, frames - 1)) target = minOf(target, need[j])
            gain = if (target < gain) {
                target
            } else {
                val next = gain + (target - gain) * release
                if (next == gain) target else next
            }
            for (ch in 0 until 2) out[i * 2 + ch] = (input[i * 2 + ch] * gain).coerceIn(-ceiling, ceiling)
        }
        return out
    }

    @Test
    fun withoutTheTruePeakEveryLimiterHoldsSamplesAsBefore() {
        // A song alone: noise bursts far over full scale between quiet
        // stretches, then the quarter-rate sum, whose samples need nothing.
        val noise = Random(11)
        val input = FloatArray(24_000 * 2) { i ->
            val n = noise.nextFloat() * 2f - 1f
            if ((i / 2 / 3_000) % 2 == 0) n * 2.5f else n * 0.2f
        } + quarterRateSum(9_600)
        val plain = Limiter(2, rate)
        assertArrayEquals(sampleLimited(input, plain.latency), limited(plain, input), 0f)
        // Plain and ramped alike, the sound between samples passes untouched.
        for (ramped in listOf(false, true)) {
            val sum = quarterRateSum(9_600)
            assertArrayEquals("ramped $ramped", sum, limited(Limiter(2, rate, rampedAttack = ramped), sum), 0f)
        }
    }
}
