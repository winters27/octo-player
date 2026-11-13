package app.winters.octo.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class OctoResamplerTest {
    // A stereo sine, the same on both sides.
    private fun sine(rate: Int, hz: Double, frames: Int, amplitude: Double = 0.9, from: Long = 0): FloatArray =
        FloatArray(frames * 2) { i -> (amplitude * sin(2 * PI * hz * (from + i / 2) / rate)).toFloat() }

    private fun resample(r: OctoResampler, input: FloatArray, chunk: Int = 1_000, finish: Boolean = true): FloatArray {
        val out = ArrayList<Float>()
        val frames = input.size / 2
        var at = 0
        while (at < frames) {
            val n = minOf(chunk, frames - at)
            r.process(input, n, { data, made -> for (i in 0 until made * 2) out += data[i] }, offset = at)
            at += n
        }
        if (finish) r.finish { data, made -> for (i in 0 until made * 2) out += data[i] }
        return out.toFloatArray()
    }

    private fun left(stereo: FloatArray, from: Int, count: Int) = DoubleArray(count) { stereo[(from + it) * 2].toDouble() }

    // Every component's level in dBFS, through a Kaiser window whose side
    // lobes sit far below what is measured.
    private class Spectrum(signal: DoubleArray, val rate: Int) {
        val n = signal.size
        val db: DoubleArray

        init {
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            var sumW = 0.0
            val beta = 20.0
            val i0b = i0(beta)
            for (i in 0 until n) {
                val u = 2.0 * i / (n - 1) - 1
                val w = i0(beta * sqrt(max(0.0, 1 - u * u))) / i0b
                re[i] = signal[i] * w
                sumW += w
            }
            fft(re, im)
            db = DoubleArray(n / 2) { k ->
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * 2 / sumW
                20 * log10(max(mag, 1e-12))
            }
        }

        fun bin(hz: Double): Int = (hz * n / rate).toInt()

        // The loudest component in [loHz, hiHz], leaving out `skip` bins
        // around each of `around`.
        fun loudest(loHz: Double, hiHz: Double, around: List<Double> = emptyList(), skip: Int = 24): Double {
            var best = -300.0
            for (k in bin(loHz)..minOf(bin(hiHz), db.size - 1)) {
                if (around.any { abs(k - bin(it)) <= skip }) continue
                best = max(best, db[k])
            }
            return best
        }

        fun peak(hz: Double): Double {
            var best = -300.0
            for (k in bin(hz) - 3..bin(hz) + 3) best = max(best, db[k])
            return best
        }

        companion object {
            fun i0(x: Double): Double {
                var sum = 1.0
                var term = 1.0
                val q = x * x / 4
                for (k in 1..200) {
                    term *= q / (k.toDouble() * k)
                    sum += term
                    if (term < sum * 1e-18) break
                }
                return sum
            }

            fun fft(re: DoubleArray, im: DoubleArray) {
                val n = re.size
                var j = 0
                for (i in 1 until n) {
                    var bit = n shr 1
                    while (j and bit != 0) {
                        j = j xor bit
                        bit = bit shr 1
                    }
                    j = j xor bit
                    if (i < j) {
                        var t = re[i]; re[i] = re[j]; re[j] = t
                        t = im[i]; im[i] = im[j]; im[j] = t
                    }
                }
                var len = 2
                while (len <= n) {
                    val ang = -2 * PI / len
                    for (i in 0 until n step len) {
                        for (k in 0 until len / 2) {
                            val wr = cos(ang * k)
                            val wi = sin(ang * k)
                            val ar = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                            val ai = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                            re[i + k + len / 2] = re[i + k] - ar
                            im[i + k + len / 2] = im[i + k] - ai
                            re[i + k] += ar
                            im[i + k] += ai
                        }
                    }
                    len = len shl 1
                }
            }
        }
    }

    private val fftSize = 16_384

    private class Through(val spectrum: Spectrum, val levelDb: Double)

    private fun through(inRate: Int, outRate: Int, hz: Double, amplitude: Double = 0.9): Through {
        val inFrames = ((fftSize + 8_000).toLong() * inRate / outRate).toInt()
        val out = resample(OctoResampler(inRate, outRate), sine(inRate, hz, inFrames, amplitude), chunk = 777)
        val part = left(out, 4_000, fftSize)
        return Through(Spectrum(part, outRate), 20 * log10(amplitudeOf(part, hz, outRate, 4_000)))
    }

    // A tone's amplitude by a least-squares fit at its frequency, so the
    // level is exact wherever the tone falls between bins.
    private fun amplitudeOf(signal: DoubleArray, hz: Double, rate: Int, from: Int): Double {
        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        for (i in signal.indices) {
            val w = 2 * PI * hz * (from + i) / rate
            val s = sin(w)
            val c = cos(w)
            ss += s * s
            cc += c * c
            sc += s * c
            ys += signal[i] * s
            yc += signal[i] * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        return sqrt(a * a + b * b)
    }

    @Test
    fun upsamplingKeepsThePassbandFlatAndItsImagesBelowTheFloor() {
        val ref = 20 * log10(0.9)
        for (hz in listOf(40.0, 1_000.0, 5_000.0, 10_000.0, 15_000.0, 18_000.0, 19_500.0, 20_000.0)) {
            val t = through(44_100, 48_000, hz)
            assertEquals("level at $hz Hz", ref, t.levelDb, 0.1)
            val worst = t.spectrum.loudest(10.0, 23_990.0, listOf(hz))
            assertTrue("an image at $hz Hz reaches $worst dBFS", worst <= -96.0)
        }
    }

    @Test
    fun downsamplingKeepsThePassbandFlatAndHoldsBackWhatWouldFoldIn() {
        val ref = 20 * log10(0.9)
        for (hz in listOf(40.0, 1_000.0, 10_000.0, 19_000.0, 20_000.0)) {
            val t = through(96_000, 48_000, hz)
            assertEquals("level at $hz Hz", ref, t.levelDb, 0.1)
            assertTrue("a stray component at $hz Hz", t.spectrum.loudest(10.0, 20_000.0, listOf(hz)) <= -96.0)
        }
        // Tones above the mix rate's band would fold back into the music.
        for (hz in listOf(28_500.0, 32_000.0, 40_000.0, 46_000.0)) {
            val worst = through(96_000, 48_000, hz).spectrum.loudest(10.0, 20_000.0)
            assertTrue("$hz Hz folds in at $worst dBFS", worst <= -96.0)
        }
    }

    @Test
    fun theMixRateDownToASongRateAlsoMeetsTheFloor() {
        val t = through(48_000, 44_100, 12_000.0)
        assertEquals(20 * log10(0.9), t.levelDb, 0.1)
        assertTrue(t.spectrum.loudest(10.0, 22_000.0, listOf(12_000.0)) <= -96.0)
    }

    @Test
    fun equalRatesPassBitForBit() {
        val input = sine(48_000, 997.0, 10_000)
        val r = OctoResampler(48_000, 48_000)
        val out = resample(r, input, chunk = 333)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
        assertEquals(0, r.tailFrames)
    }

    @Test
    fun anOutputFrameSitsExactlyAtItsInputTime() {
        // A ramp in, so each output sample is its own input time.
        val inRate = 44_100
        val frames = 4_410
        val input = FloatArray(frames * 2) { i -> ((i / 2) / inRate.toDouble()).toFloat() }
        val out = resample(OctoResampler(inRate, 48_000), input)
        assertEquals(4_800, out.size / 2)
        for (n in 200 until 4_600) assertEquals(n / 48_000.0, out[n * 2].toDouble(), 2e-6)
    }

    @Test
    fun chunkSizeAndAGaplessJoinChangeNothing() {
        // Two songs at 44.1 kHz on one output are one continuous resample.
        val a = sine(44_100, 12_000.0, 30_001)
        val b = sine(44_100, 12_000.0, 20_013, from = 30_001)
        val whole = resample(OctoResampler(44_100, 48_000), a + b, chunk = 50_014)
        val r = OctoResampler(44_100, 48_000)
        val joined = resample(r, a, chunk = 731, finish = false) + resample(r, b, chunk = 89)
        assertEquals(whole.size, joined.size)
        for (i in whole.indices) assertEquals(whole[i], joined[i], 0f)
    }

    @Test
    fun aRateStepBetweenSongsLeavesNoGapAndNoDoubledFrame() {
        // 12 kHz in a 44.1 kHz song, then the same tone in a 96 kHz song:
        // each output ends where its song ends, so the two meet on the mix
        // grid with the tone continuous within the floor away from the join.
        val mix = 48_000
        val aFrames = 44_100
        val a = resample(OctoResampler(44_100, mix), sine(44_100, 12_000.0, aFrames))
        assertEquals(mix, a.size / 2)
        val b = resample(OctoResampler(96_000, mix), sine(96_000, 12_000.0, 96_000, from = 96_000))
        assertEquals(mix, b.size / 2)
        val joined = a + b
        val edge = 80
        var worst = 0.0
        for (n in 2_000 until 2 * mix - 2_000) {
            if (abs(n - mix) < edge) continue
            val ideal = 0.9 * sin(2 * PI * 12_000.0 * n / mix)
            worst = max(worst, abs(joined[n * 2] - ideal))
        }
        assertTrue("error ${20 * log10(worst)} dBFS", 20 * log10(worst) <= -96.0)
    }

    @Test
    fun aStreamOnAnEarlierGridJoinsItSampleForSample() {
        // A player's own restart: the same song again from input frame
        // 12,345, its output landing on the first stream's frames.
        val song = sine(44_100, 3_000.0, 40_000)
        val first = resample(OctoResampler(44_100, 48_000), song, finish = false)
        val from = 12_345
        val restart = OctoResampler(44_100, 48_000, gridFrom = from.toLong())
        val again = resample(restart, song.copyOfRange(from * 2, song.size), finish = false)
        // The restart's first output frame is the first stream's frame at or
        // after input 12,345; past the filter's reach they are equal.
        val offset = OctoResampler.ceilDiv(from.toLong() * 160, 147).toInt()
        val skip = restart.taps
        for (n in skip until again.size / 2 - 10) {
            assertEquals(first[(offset + n) * 2], again[n * 2], 0f)
        }
        assertEquals(from + restart.inputPositionOf(0), offset * 147.0 / 160, 1e-9)
    }

    @Test
    fun theRoomItAsksForIsNeverExceeded() {
        val r = OctoResampler(44_100, 48_000)
        var made = 0L
        val input = sine(44_100, 440.0, 4_096)
        repeat(50) {
            val free = 997 + it * 13
            val take = minOf(r.maxInputFor(free), 4_096)
            var got = 0
            r.process(input, take, { _, n -> got += n })
            assertTrue("made $got with room $free", got <= free - r.tailFrames)
            made += got
        }
        assertEquals(made, r.outputMade)
    }
}
