package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

// One envelope value per this many milliseconds of sound.
const val ENVELOPE_HOP_MS = 10

// The level written for a hop with no sound at all, in dBFS.
const val SILENT_DB = -100f

// Where the bass band of the envelope ends: kick drums and bass lines.
const val BASS_CUTOFF_HZ = 150.0

// Where the middle band of the onsets ends and the high band begins.
const val TREBLE_FROM_HZ = 2_500.0

// The onsets look at the last this many samples at the end of every hop.
const val ONSET_FFT_SIZE = 1024

// Magnitudes are compressed as ln(1 + ONSET_COMPRESSION * magnitude), with
// a full-scale sine reading magnitude 1 in its bin.
const val ONSET_COMPRESSION = 100.0

// Loudness over time for one stretch of a song, one value per hop. `db` is
// the whole band, `lowDb` only the sound below BASS_CUTOFF_HZ, both as RMS
// in dBFS. `onset` is the spectral flux: for each of three bands (below
// BASS_CUTOFF_HZ, up to TREBLE_FROM_HZ, and above), the mean rise of the
// compressed FFT magnitudes since the hop before, rises only, added up.
// `lowOnset` is the bass band's part of it. Onsets peak on drum hits and
// note starts. Times are song times: hop i covers startMs + i * hopMs
// onwards.
class SectionEnvelope(
    val startMs: Long,
    val hopMs: Int,
    val db: FloatArray,
    val lowDb: FloatArray,
    val onset: FloatArray,
    val lowOnset: FloatArray,
) {
    init {
        require(hopMs > 0) { "hopMs must be positive" }
        require(db.size == lowDb.size && db.size == onset.size && db.size == lowOnset.size) {
            "the envelope's arrays must be the same length"
        }
    }

    val size: Int get() = db.size

    val endMs: Long get() = startMs + size.toLong() * hopMs

    fun timeOf(index: Int): Long = startMs + index.toLong() * hopMs

    // The hop that holds a song time; below 0 or at size and above when the
    // time lies outside the envelope.
    fun indexAt(ms: Double): Int = floor((ms - startMs) / hopMs).toInt()
}

// Builds a SectionEnvelope from decoded sound fed to it in blocks of any
// size, so a decoder can hand over each block as it comes out. Samples are
// interleaved when there is more than one channel and are summed to mono.
// A block may end in the middle of a frame; the rest of that frame is taken
// from the next block. Each hop's onsets come from a Hann-windowed FFT of
// the last ONSET_FFT_SIZE mono samples up to the hop's end (zeros before
// the first sample).
class EnvelopeBuilder(
    private val sampleRate: Int,
    private val channels: Int,
    private val startMs: Long,
    private val hopMs: Int = ENVELOPE_HOP_MS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(hopMs > 0) { "hopMs must be positive" }
    }

    private val bass = Biquad.lowPass(sampleRate, BASS_CUTOFF_HZ)

    private var channel = 0
    private var frameSum = 0.0

    // Frames taken so far, and the frame at which the current hop ends:
    // hop k covers frames k * rate * hop / 1000 up to (k + 1) * rate * hop / 1000.
    private var frame = 0L
    private var hop = 0L
    private var hopEnd = hopEndOf(0)
    private var hopStart = 0L
    private var sumSquares = 0.0
    private var lowSquares = 0.0

    // The last ONSET_FFT_SIZE mono samples, oldest at `ringAt`.
    private val ring = DoubleArray(ONSET_FFT_SIZE)
    private var ringAt = 0
    private val window = DoubleArray(ONSET_FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / ONSET_FFT_SIZE) }
    private val fft = RealFft(ONSET_FFT_SIZE)
    private val frameIn = DoubleArray(ONSET_FFT_SIZE)
    private val magnitudes = DoubleArray(ONSET_FFT_SIZE / 2 + 1)
    private var compressed = DoubleArray(ONSET_FFT_SIZE / 2 + 1)
    private var lastCompressed = DoubleArray(ONSET_FFT_SIZE / 2 + 1)

    // Bins 1 until lowEnd are the bass band, lowEnd until highFrom the middle
    // band, highFrom up to size / 2 the high band.
    private val binHz = sampleRate.toDouble() / ONSET_FFT_SIZE
    private val lowEnd = bandEdge(BASS_CUTOFF_HZ)
    private val highFrom = max(lowEnd, bandEdge(TREBLE_FROM_HZ))

    private fun bandEdge(hz: Double): Int = min(ONSET_FFT_SIZE / 2 + 1, max(1, ceil(hz / binHz).toInt()))

    private var db = FloatArray(1024)
    private var lowDb = FloatArray(1024)
    private var onset = FloatArray(1024)
    private var lowOnset = FloatArray(1024)
    private var count = 0

    private fun hopEndOf(k: Long): Long = (k + 1) * sampleRate * hopMs / 1000

    // Samples between -1 and 1.
    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            frameSum += samples[i]
            i++
            if (++channel == channels) takeFrame()
        }
    }

    // 16-bit samples, as most decoders give them.
    fun push(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            frameSum += samples[i] / 32768.0
            i++
            if (++channel == channels) takeFrame()
        }
    }

    private fun takeFrame() {
        val x = frameSum / channels
        frameSum = 0.0
        channel = 0
        val y = bass.process(x)
        sumSquares += x * x
        lowSquares += y * y
        ring[ringAt] = x
        ringAt = if (ringAt == ONSET_FFT_SIZE - 1) 0 else ringAt + 1
        frame++
        if (frame == hopEnd) closeHop()
    }

    private fun closeHop() {
        val frames = (frame - hopStart).toDouble()
        if (count == db.size) {
            db = db.copyOf(count * 2)
            lowDb = lowDb.copyOf(count * 2)
            onset = onset.copyOf(count * 2)
            lowOnset = lowOnset.copyOf(count * 2)
        }
        for (i in 0 until ONSET_FFT_SIZE) {
            val at = ringAt + i
            frameIn[i] = ring[if (at >= ONSET_FFT_SIZE) at - ONSET_FFT_SIZE else at] * window[i]
        }
        fft.magnitudes(frameIn, magnitudes)
        val scale = 4.0 / ONSET_FFT_SIZE
        for (k in magnitudes.indices) compressed[k] = ln(1 + ONSET_COMPRESSION * magnitudes[k] * scale)
        val bassFlux = if (count == 0) 0.0 else flux(1, lowEnd)
        val allFlux = if (count == 0) 0.0 else bassFlux + flux(lowEnd, highFrom) + flux(highFrom, ONSET_FFT_SIZE / 2 + 1)
        val swap = lastCompressed
        lastCompressed = compressed
        compressed = swap

        db[count] = dbOf(sumSquares / frames)
        lowDb[count] = dbOf(lowSquares / frames)
        onset[count] = allFlux.toFloat()
        lowOnset[count] = bassFlux.toFloat()
        count++
        sumSquares = 0.0
        lowSquares = 0.0
        hopStart = frame
        hop++
        hopEnd = hopEndOf(hop)
    }

    // The mean rise of the compressed magnitudes over bins from until to.
    private fun flux(from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (k in from until to) {
            val rise = compressed[k] - lastCompressed[k]
            if (rise > 0) sum += rise
        }
        return sum / (to - from)
    }

    // How much sound the whole hops fed so far cover, in milliseconds.
    val heardMs: Long get() = count.toLong() * hopMs

    // The envelope of every whole hop fed so far; a last partial hop is left out.
    fun build(): SectionEnvelope = SectionEnvelope(
        startMs, hopMs, db.copyOf(count), lowDb.copyOf(count), onset.copyOf(count), lowOnset.copyOf(count),
    )
}

// RMS level in dBFS from a mean square, never below SILENT_DB.
internal fun dbOf(meanSquare: Double): Float =
    if (meanSquare <= 1e-10) SILENT_DB else max(SILENT_DB.toDouble(), 10 * log10(meanSquare)).toFloat()
