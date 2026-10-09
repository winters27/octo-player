package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// The Q of a Butterworth second-order section: flat, no bump at the cutoff.
const val BUTTERWORTH_Q = 0.7071067811865476

// A cutoff is kept below this share of the sample rate so the filter stays
// stable at any rate.
const val HIGHEST_CUTOFF_SHARE = 0.45

// A second-order filter section (the common "cookbook" low-pass and
// high-pass), run one sample at a time. Coefficients are normalized so a0
// is 1: y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2.
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {
        fun lowPass(sampleRate: Int, hz: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val (c, alpha) = shape(sampleRate, hz, q)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(sampleRate: Int, hz: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val (c, alpha) = shape(sampleRate, hz, q)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        // cos(w0) and alpha for a cutoff, with the cutoff held between 1 Hz
        // and HIGHEST_CUTOFF_SHARE of the rate.
        private fun shape(sampleRate: Int, hz: Double, q: Double): Pair<Double, Double> {
            val cutoff = hz.coerceIn(1.0, sampleRate * HIGHEST_CUTOFF_SHARE)
            val w0 = 2 * PI * cutoff / sampleRate
            return cos(w0) to sin(w0) / (2 * q)
        }
    }
}

// A real-input FFT of a power-of-two size, done as a complex FFT of half the
// size. Only the magnitudes of bins 0 to size / 2 are given.
internal class RealFft(val size: Int) {
    private val half = size / 2
    private val re = DoubleArray(half)
    private val im = DoubleArray(half)
    private val cosHalf = DoubleArray(half / 2) { cos(2 * PI * it / half) }
    private val sinHalf = DoubleArray(half / 2) { sin(2 * PI * it / half) }
    private val cosFull = DoubleArray(half + 1) { cos(2 * PI * it / size) }
    private val sinFull = DoubleArray(half + 1) { sin(2 * PI * it / size) }
    private val reversed = IntArray(half)

    init {
        require(size >= 4 && size and (size - 1) == 0) { "size must be a power of two" }
        var bits = 0
        while (1 shl bits < half) bits++
        for (i in 0 until half) {
            var r = 0
            for (b in 0 until bits) if (i shr b and 1 == 1) r = r or (1 shl (bits - 1 - b))
            reversed[i] = r
        }
    }

    // `out` gets |X[k]| for k = 0 .. size / 2.
    fun magnitudes(input: DoubleArray, out: DoubleArray) {
        val re = re
        val im = im
        val cosHalf = cosHalf
        val sinHalf = sinHalf
        for (i in 0 until half) {
            val j = reversed[i]
            re[j] = input[2 * i]
            im[j] = input[2 * i + 1]
        }
        var length = 2
        while (length <= half) {
            val step = half / length
            val mid = length / 2
            for (j in 0 until mid) {
                val wr = cosHalf[j * step]
                val wi = -sinHalf[j * step]
                var a = j
                while (a < half) {
                    val b = a + mid
                    val br = re[b]
                    val bi = im[b]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr
                    val ar = re[a]
                    val ai = im[a]
                    re[b] = ar - tr
                    im[b] = ai - ti
                    re[a] = ar + tr
                    im[a] = ai + ti
                    a += length
                }
            }
            length *= 2
        }
        for (k in 0..half) {
            val front = if (k == half) 0 else k
            val back = if (k == 0) 0 else half - k
            val a = re[front]
            val b = im[front]
            val c = re[back]
            val d = im[back]
            val evenRe = (a + c) / 2
            val evenIm = (b - d) / 2
            val oddRe = (b + d) / 2
            val oddIm = -(a - c) / 2
            val wr = cosFull[k]
            val ws = sinFull[k]
            val xr = evenRe + wr * oddRe + ws * oddIm
            val xi = evenIm + wr * oddIm - ws * oddRe
            out[k] = sqrt(xr * xr + xi * xi)
        }
    }
}
