package app.winters.octo.sound

import kotlin.math.abs

// Filters at or above this share of the sample rate are left out: the
// formulas bend out of shape that close to the top of the range.
const val HIGHEST_BAND_SHARE = 0.45f

// State this small is set to zero, so a fading tail never slows the
// processor down with tiny numbers.
private const val TINY = 1e-25

// A chain of filters run one after another over interleaved audio. Each
// channel keeps its own filter memory, in doubles, so deep bass and quiet
// passages stay clean. The filters use the transposed direct form II.
class FilterBank(val channels: Int, val coefficients: List<Coefficients>) {
    private val count = coefficients.size
    private val b0 = DoubleArray(count) { coefficients[it].b0 }
    private val b1 = DoubleArray(count) { coefficients[it].b1 }
    private val b2 = DoubleArray(count) { coefficients[it].b2 }
    private val a1 = DoubleArray(count) { coefficients[it].a1 }
    private val a2 = DoubleArray(count) { coefficients[it].a2 }

    // Two memory slots per filter per channel.
    private val z1 = DoubleArray(count * channels)
    private val z2 = DoubleArray(count * channels)

    val isEmpty: Boolean get() = count == 0

    // Filters `frames` frames of `samples` in place.
    fun process(samples: FloatArray, frames: Int) {
        if (count == 0) return
        var i = 0
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                var x = samples[i].toDouble()
                var slot = ch * count
                for (k in 0 until count) {
                    val y = b0[k] * x + z1[slot]
                    z1[slot] = b1[k] * x - a1[k] * y + z2[slot]
                    z2[slot] = b2[k] * x - a2[k] * y
                    x = y
                    slot++
                }
                samples[i] = x.toFloat()
                i++
            }
        }
        for (s in z1.indices) {
            if (abs(z1[s]) < TINY) z1[s] = 0.0
            if (abs(z2[s]) < TINY) z2[s] = 0.0
        }
    }

    // Carries on from another bank's memory when the filters line up, so a
    // small change to the curve does not restart them from silence.
    fun continueFrom(other: FilterBank) {
        if (other.count != count || other.channels != channels) return
        other.z1.copyInto(z1)
        other.z2.copyInto(z2)
    }

    fun clear() {
        z1.fill(0.0)
        z2.fill(0.0)
    }
}

// The filters to run for these settings at this sample rate.
fun filterBank(filters: List<EqFilter>, sampleRate: Int, channels: Int): FilterBank =
    FilterBank(channels, bandCoefficients(filters, sampleRate))

fun bandCoefficients(filters: List<EqFilter>, sampleRate: Int): List<Coefficients> =
    filters.filter { it.frequency < sampleRate * HIGHEST_BAND_SHARE }.map { coefficients(it, sampleRate) }
