package app.winters.octo.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// How far down the resampler holds everything that would fold into the
// music, in dB below full scale.
const val RESAMPLER_STOPBAND_DB = 100.0

// The highest frequency the resampler keeps flat.
const val RESAMPLER_PASS_HZ = 20_000.0

// A polyphase windowed-sinc resampler (Kaiser window), float stereo in and
// out, from a song's rate to the mix rate. Output frame n sits exactly at
// input position n * inRate / outRate on its grid: the filter is centered,
// so nothing comes out late, and the frames still to come at the end of a
// stream are pushed out by finish(). Equal rates pass through bit for bit.
//
// `gridFrom` puts the stream's first input frame at that input index of an
// earlier stream's grid, so a stream that starts again partway through the
// same song (a player's own restart) makes output frames that land exactly
// on the earlier stream's: the two join sample for sample.
//
// Not thread-safe; one writer thread. `heldInput` may be read from any
// thread.
class OctoResampler(
    val inRate: Int,
    val outRate: Int,
    stopbandDb: Double = RESAMPLER_STOPBAND_DB,
    gridFrom: Long = 0,
) {
    val bypass = inRate == outRate

    private val up: Long
    private val down: Long

    // Output positions are counted in steps of 1 / `scale` input frames.
    private val scale: Long
    private val phases: Int

    val taps: Int
    private val half: Int
    private val coeffs: FloatArray

    // Input frames kept for the filter: input index [bufStart, bufStart + bufFrames).
    private var buf = FloatArray(4_096 * 2)
    private var bufStart: Long
    private var bufFrames: Int

    // The next input index taken and the next output index made, both on
    // the grid, so input index i is this stream's frame i - gridFrom.
    private var inIndex: Long = gridFrom
    private var outIndex: Long
    private val firstOut: Long
    private val gridFrom = gridFrom
    private var out = FloatArray(4_096 * 2)
    private var finished = false

    // Input frames taken that no output frame has passed yet, published for
    // the mixer: once a deck has stopped feeding, they count as heard.
    @Volatile var heldInput = 0L
        private set

    // Input frames taken since the start.
    val inputTaken: Long get() = inIndex - gridFrom

    // Output frames made since the start.
    val outputMade: Long get() = outIndex - firstOut

    // Output frames finish() can still add, so a ring keeps room for them.
    val tailFrames: Int

    init {
        require(inRate > 0 && outRate > 0)
        val g = gcd(inRate.toLong(), outRate.toLong())
        up = outRate / g
        down = inRate / g
        // A pair of rates with no small common step (none of the usual ones)
        // takes its phase from a fine fixed table instead.
        scale = if (up <= MOST_PHASES) up else MOST_PHASES.toLong()
        phases = if (bypass) 1 else scale.toInt()
        val fsLow = min(inRate, outRate).toDouble()
        // 20 kHz stays in the passband down to a 44.1 kHz side.
        val pass = min(RESAMPLER_PASS_HZ, 0.4536 * fsLow)
        val stop = fsLow - pass
        val width = 2 * PI * (stop - pass) / inRate
        var n = ceil((stopbandDb - 8) / (2.285 * width)).toInt()
        n = max(16, n + (n and 1))
        taps = if (bypass) 0 else n
        half = taps / 2
        tailFrames = if (bypass) 0 else ((half.toLong() * up + down - 1) / down).toInt() + 2
        coeffs = if (bypass) FloatArray(0) else design(stopbandDb, fsLow / 2 / inRate)
        firstOut = if (bypass) gridFrom else ceilDiv(gridFrom * up, down)
        outIndex = firstOut
        bufStart = gridFrom - (half - 1).coerceAtLeast(0)
        bufFrames = (half - 1).coerceAtLeast(0)
    }

    // Each phase row is a sinc at the low rate's Nyquist, shifted by the
    // phase's fraction of an input frame and windowed; every row passes DC
    // at exactly unity.
    private fun design(stopbandDb: Double, fc: Double): FloatArray {
        val beta = when {
            stopbandDb > 50 -> 0.1102 * (stopbandDb - 8.7)
            stopbandDb >= 21 -> 0.5842 * (stopbandDb - 21).pow(0.4) + 0.07886 * (stopbandDb - 21)
            else -> 0.0
        }
        val i0b = besselI0(beta)
        val table = FloatArray(phases * taps)
        val row = DoubleArray(taps)
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            for (k in 0 until taps) {
                val tau = (k - (half - 1)) - frac
                val u = tau / half
                val w = if (abs(u) <= 1.0) besselI0(beta * sqrt(1 - u * u)) / i0b else 0.0
                val x = 2 * fc * tau
                val s = if (abs(x) < 1e-12) 2 * fc else 2 * fc * sin(PI * x) / (PI * x)
                row[k] = s * w
                sum += row[k]
            }
            for (k in 0 until taps) table[p * taps + k] = (row[k] / sum).toFloat()
        }
        return table
    }

    // How many more input frames fit when there is room for `freeOut`
    // output frames, the tail finish() adds kept free.
    fun maxInputFor(freeOut: Int): Int {
        val room = freeOut - tailFrames
        if (room <= 0) return 0
        if (bypass) return room
        // Output frame m is made once input index floor(m * down / up) +
        // half has arrived, so input up to just before the one that makes
        // output outIndex + room makes at most `room` more.
        val lastIn = floorDiv((outIndex + room) * down, up) + half - 1
        return (lastIn - inIndex + 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    // This stream's input frames that output frames before `outFrames`
    // (counted from the start) have passed: the frames heard once those are.
    fun inputAt(outFrames: Long): Long {
        if (bypass) return outFrames
        val pos = (firstOut + outFrames) * down
        return (ceilDiv(pos, up) - gridFrom).coerceIn(0L, inputTaken)
    }

    // This stream's first output frame (counted from the start) at or after
    // input frame `input` of this stream.
    fun firstOutputAtOrAfter(input: Long): Long {
        if (bypass) return input
        return ceilDiv((input + gridFrom) * up, down) - firstOut
    }

    // The input position of this stream's output frame `outFrame` (counted
    // from the start), in input frames from this stream's first frame.
    fun inputPositionOf(outFrame: Long): Double {
        if (bypass) return outFrame.toDouble()
        return ((firstOut + outFrame) * down).toDouble() / up - gridFrom
    }

    // Takes `frames` stereo frames from `input` (from `offset` frames in)
    // and hands every output frame it can make to `sink`.
    fun process(input: FloatArray, frames: Int, sink: (FloatArray, Int) -> Unit, offset: Int = 0) {
        if (frames <= 0 || finished) return
        if (bypass) {
            inIndex += frames
            outIndex += frames
            if (offset == 0) {
                sink(input, frames)
            } else {
                if (out.size < frames * 2) out = FloatArray(frames * 2)
                System.arraycopy(input, offset * 2, out, 0, frames * 2)
                sink(out, frames)
            }
            return
        }
        append(input, offset, frames)
        inIndex += frames
        run(inIndex - 1, sink)
    }

    // Pushes out every frame that lies within the input, at the end of a
    // stream. Nothing is taken after it.
    fun finish(sink: (FloatArray, Int) -> Unit) {
        if (bypass || finished) {
            finished = true
            return
        }
        val zeros = FloatArray(half * 2)
        append(zeros, 0, half)
        val last = inIndex - 1 + half
        // Only frames inside the input: the padding is not music.
        runUntil(last, inIndex, sink)
        finished = true
        heldInput = 0
    }

    private fun run(lastIndex: Long, sink: (FloatArray, Int) -> Unit) = runUntil(lastIndex, Long.MAX_VALUE, sink)

    // Makes output frames whose last needed input index is at most
    // `lastIndex` and whose own position is before `endIndex`.
    private fun runUntil(lastIndex: Long, endIndex: Long, sink: (FloatArray, Int) -> Unit) {
        var made = 0
        while (true) {
            val pos = outIndex * down
            val i = floorDiv(pos, up)
            if (i + half > lastIndex || i >= endIndex) break
            val p = phaseOf(pos - i * up)
            val base = (i - (half - 1) - bufStart).toInt() * 2
            var l = 0f
            var r = 0f
            val c = p * taps
            var b = base
            for (k in 0 until taps) {
                val w = coeffs[c + k]
                l += w * buf[b]
                r += w * buf[b + 1]
                b += 2
            }
            if ((made + 1) * 2 > out.size) out = out.copyOf(out.size * 2)
            out[made * 2] = l
            out[made * 2 + 1] = r
            made++
            outIndex++
        }
        // Drops input no later output needs.
        val keepFrom = floorDiv(outIndex * down, up) - (half - 1)
        val drop = (keepFrom - bufStart).coerceIn(0L, bufFrames.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop * 2, buf, 0, (bufFrames - drop) * 2)
            bufFrames -= drop
            bufStart += drop
        }
        heldInput = max(0L, inIndex - ceilDiv(outIndex * down, up))
        if (made > 0) sink(out, made)
    }

    // The phase row for a position `rem` / up past an input frame.
    private fun phaseOf(rem: Long): Int = if (scale == up) rem.toInt() else ((rem * scale + up / 2) / up).toInt().coerceAtMost(phases - 1)

    private fun append(input: FloatArray, offset: Int, frames: Int) {
        val need = (bufFrames + frames) * 2
        if (need > buf.size) buf = buf.copyOf(max(need, buf.size * 2))
        System.arraycopy(input, offset * 2, buf, bufFrames * 2, frames * 2)
        bufFrames += frames
    }

    companion object {
        private const val MOST_PHASES = 4_096

        fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

        fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            for (k in 1..80) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-17) break
            }
            return sum
        }
    }
}
