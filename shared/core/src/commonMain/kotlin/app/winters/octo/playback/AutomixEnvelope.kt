package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// One envelope value per this many milliseconds of sound.
const val ENVELOPE_HOP_MS = 10

// The level written for a hop with no sound at all, in dBFS.
const val SILENT_DB = -100f

// Where the bass band of the envelope ends: kick drums and bass lines.
const val BASS_CUTOFF_HZ = 150.0

// Loudness over time for one stretch of a song, one value per hop. `db` is
// the whole band, `lowDb` only the sound below BASS_CUTOFF_HZ, both as RMS
// in dBFS. `onset` is how much the two rose since the hop before (each rise
// counted only when positive), which peaks on drum hits and note starts.
// Times are song times: hop i covers startMs + i * hopMs onwards.
class SectionEnvelope(
    val startMs: Long,
    val hopMs: Int,
    val db: FloatArray,
    val lowDb: FloatArray,
    val onset: FloatArray,
) {
    init {
        require(hopMs > 0) { "hopMs must be positive" }
        require(db.size == lowDb.size && db.size == onset.size) { "the envelope's arrays must be the same length" }
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
// from the next block.
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

    // A second-order low-pass (Butterworth, Q = 1/sqrt 2) at BASS_CUTOFF_HZ.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    init {
        val w0 = 2 * PI * BASS_CUTOFF_HZ / sampleRate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 - c) / 2 / a0
        b1 = (1 - c) / a0
        b2 = (1 - c) / 2 / a0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

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

    private var lastDb = SILENT_DB
    private var lastLowDb = SILENT_DB

    private var db = FloatArray(1024)
    private var lowDb = FloatArray(1024)
    private var onset = FloatArray(1024)
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
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        sumSquares += x * x
        lowSquares += y * y
        frame++
        if (frame == hopEnd) closeHop()
    }

    private fun closeHop() {
        val frames = (frame - hopStart).toDouble()
        val full = dbOf(sumSquares / frames)
        val low = dbOf(lowSquares / frames)
        if (count == db.size) {
            db = db.copyOf(count * 2)
            lowDb = lowDb.copyOf(count * 2)
            onset = onset.copyOf(count * 2)
        }
        db[count] = full
        lowDb[count] = low
        onset[count] = if (count == 0) 0f else max(0f, full - lastDb) + max(0f, low - lastLowDb)
        count++
        lastDb = full
        lastLowDb = low
        sumSquares = 0.0
        lowSquares = 0.0
        hopStart = frame
        hop++
        hopEnd = hopEndOf(hop)
    }

    // The envelope of every whole hop fed so far; a last partial hop is left out.
    fun build(): SectionEnvelope =
        SectionEnvelope(startMs, hopMs, db.copyOf(count), lowDb.copyOf(count), onset.copyOf(count))
}

// RMS level in dBFS from a mean square, never below SILENT_DB.
internal fun dbOf(meanSquare: Double): Float =
    if (meanSquare <= 1e-10) SILENT_DB else max(SILENT_DB.toDouble(), 10 * log10(meanSquare)).toFloat()
