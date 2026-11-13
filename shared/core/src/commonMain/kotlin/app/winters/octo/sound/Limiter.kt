package app.winters.octo.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Just below full scale, where the limiter holds peaks.
const val LIMITER_CEILING_DB = -1f

// How far ahead the limiter looks, and how slowly the level comes back.
const val LIMITER_LOOKAHEAD_MS = 1.5f
const val LIMITER_RELEASE_MS = 100f

// With the true peak read, the sound between two samples is read at this
// many points a sample (a quarter, a half and three quarters of the way
// along, besides the samples themselves), each from this many samples on
// either side.
const val TRUE_PEAK_STEPS = 4
const val TRUE_PEAK_HALF_TAPS = 16
private const val TRUE_PEAK_TAPS = TRUE_PEAK_HALF_TAPS * 2

// For each point read between two samples, a windowed sinc (Blackman) over
// the TRUE_PEAK_TAPS samples around it, oldest first, with the point between
// the middle two; scaled so a steady level reads as itself.
private val BETWEEN_TAPS: Array<FloatArray> = Array(TRUE_PEAK_STEPS - 1) { step ->
    val along = (step + 1).toDouble() / TRUE_PEAK_STEPS
    val half = TRUE_PEAK_HALF_TAPS.toDouble()
    val raw = DoubleArray(TRUE_PEAK_TAPS) { j ->
        // How far the point read lies from sample j.
        val d = along + half - 1.0 - j
        val window = 0.42 + 0.5 * cos(PI * d / half) + 0.08 * cos(2.0 * PI * d / half)
        sin(PI * d) / (PI * d) * window
    }
    val sum = raw.sum()
    FloatArray(TRUE_PEAK_TAPS) { (raw[it] / sum).toFloat() }
}

// Keeps peaks from going over the ceiling. The sound runs a moment late, so
// the limiter sees a peak coming and turns the level down in time instead of
// clipping it, then lets the level back up slowly so the change is not heard.
// All channels move together, so the stereo picture stays put.
//
// The delay holds back the first moment of each stream and hands it out at
// the end, so nothing is lost or added between songs.
//
// Coming back up, the level lands exactly on unity once the remaining step
// is lost in float rounding, so sound that needs no limiting after a peak
// passes through bit for bit.
//
// With `rampedAttack` the level goes down in a straight line across the
// lookahead, reaching each frame's need just as that frame comes out,
// instead of dropping at once when the peak comes into view. The sum of two
// songs is limited this way, so the limiter never steps the level.
//
// With `truePeak` the sound between samples is held under the ceiling too:
// as each frame comes in, the points between the two samples
// TRUE_PEAK_HALF_TAPS frames back are read, and what they need holds the
// level down over every sample the reading used. The sum of two songs is
// limited this way, so the sound between its samples stays under the
// ceiling, not only the samples. Without it, only samples count, exactly as
// before. It needs a lookahead of at least 2 * TRUE_PEAK_HALF_TAPS frames
// (0.7 ms at 48 kHz) and is left out with a shorter one.
class Limiter(
    val channels: Int,
    sampleRate: Int,
    ceilingDb: Float = LIMITER_CEILING_DB,
    lookaheadMs: Float = LIMITER_LOOKAHEAD_MS,
    releaseMs: Float = LIMITER_RELEASE_MS,
    val rampedAttack: Boolean = false,
    val truePeak: Boolean = false,
) {
    // Frames of delay.
    val latency: Int = max(1, (sampleRate * lookaheadMs / 1_000f).roundToInt())

    private val readsBetween = truePeak && latency >= TRUE_PEAK_TAPS

    // Taken here, so the shared table is made when a limiter is built, not
    // on the mix thread at the first reading.
    private val betweenTaps = BETWEEN_TAPS

    // The last TRUE_PEAK_TAPS frames in, each channel's kept twice over so
    // its newest frames run on from `recentAt` without wrapping.
    private val recent = FloatArray(if (readsBetween) channels * TRUE_PEAK_TAPS * 2 else 0)
    private var recentAt = 0

    // Off, the level returns to unity and the sound only passes through
    // the delay.
    var enabled = true

    private val ceiling = 10f.pow(ceilingDb / 20f)
    private val release = 1f - exp(-1f / (sampleRate * releaseMs / 1_000f))

    // The delayed frames, oldest first from `head`.
    private val held = FloatArray(latency * channels)
    private var head = 0
    private var heldFrames = 0

    // The level each frame needs, kept as a rising queue so the lowest one
    // in the lookahead window is always at the front.
    private val need = FloatArray(latency + 2)
    private val needAt = LongArray(latency + 2)
    private var needFront = 0
    private var needSize = 0

    // With the ramped attack: every frame's need, by frame number, for the
    // frames in view (a nearer need that is not the lowest can still set
    // the slope).
    private val needOf = if (rampedAttack) FloatArray(latency + 1) { 1f } else FloatArray(0)

    // With the ramped attack and the true peak: what the sound between
    // samples read as each frame came in needs, by that frame's number.
    private val betweenOf = if (rampedAttack && readsBetween) FloatArray(latency + 1) { 1f } else FloatArray(0)

    // Counts frames in and out, to know which needs are still in view.
    private var inCount = 0L
    private var outCount = 0L

    // The level applied now.
    private var gain = 1f

    // The level applied to the last frame out, for checks and snapshots.
    val level: Float get() = gain

    // Takes `frames` frames from `input` and writes the frames that come
    // out of the delay to `output`. Returns how many frames came out: fewer
    // than went in only while the delay is filling.
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        var inIndex = 0
        var outIndex = 0
        var written = 0
        for (frame in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(input[inIndex + ch]))
            val wanted = if (enabled && peak > ceiling) ceiling / peak else 1f
            var between = 1f
            if (readsBetween) {
                // Kept while off too, so the reading can start at any frame.
                remember(input, inIndex)
                if (enabled) {
                    val read = peakBetween()
                    if (read > ceiling) between = ceiling / read
                }
            }
            // A need counts from the frame that brought it in, so the
            // reading's holds the level down from `latency` frames before
            // this one: over every sample it used.
            pushNeed(if (between < wanted) between else wanted)
            if (rampedAttack) {
                val slot = (inCount % needOf.size).toInt()
                needOf[slot] = wanted
                if (readsBetween) betweenOf[slot] = between
            }
            inCount++

            val slot = ((head + heldFrames) % latency) * channels
            if (heldFrames < latency) {
                input.copyInto(held, slot, inIndex, inIndex + channels)
                heldFrames++
            } else {
                // The oldest frame leaves and the new one takes its place.
                emit(output, outIndex)
                input.copyInto(held, head * channels, inIndex, inIndex + channels)
                head = (head + 1) % latency
                outIndex += channels
                written++
            }
            inIndex += channels
        }
        return written
    }

    // Hands out every frame still in the delay, at the end of a stream.
    // `output` needs room for `latency` frames.
    fun drain(output: FloatArray): Int {
        var outIndex = 0
        val count = heldFrames
        repeat(count) {
            emit(output, outIndex)
            head = (head + 1) % latency
            heldFrames--
            outIndex += channels
        }
        head = 0
        return count
    }

    // Forgets the audio in the delay, after a jump to another place. The
    // level is kept.
    fun clear() {
        head = 0
        heldFrames = 0
        needFront = 0
        needSize = 0
        inCount = 0
        outCount = 0
        needOf.fill(1f)
        betweenOf.fill(1f)
        recent.fill(0f)
        recentAt = 0
    }

    // Forgets the audio in the delay and puts the level back at unity, for
    // a stream that starts over from a fresh place.
    fun reset() {
        clear()
        gain = 1f
    }

    // Takes over everything `other` holds (delayed frames, needs in view
    // and level), so a stretch can be run again from the same state. Both
    // must be built alike.
    fun copyFrom(other: Limiter) {
        require(
            other.channels == channels && other.latency == latency && other.rampedAttack == rampedAttack &&
                other.truePeak == truePeak,
        ) { "limiters built differently" }
        other.held.copyInto(held)
        head = other.head
        heldFrames = other.heldFrames
        other.need.copyInto(need)
        other.needAt.copyInto(needAt)
        needFront = other.needFront
        needSize = other.needSize
        other.needOf.copyInto(needOf)
        other.betweenOf.copyInto(betweenOf)
        other.recent.copyInto(recent)
        recentAt = other.recentAt
        inCount = other.inCount
        outCount = other.outCount
        gain = other.gain
        enabled = other.enabled
    }

    // Writes the oldest held frame, turned down as far as any frame in view
    // needs. Down is instant (or a straight line across the lookahead with
    // the ramped attack); back up is slow.
    private fun emit(output: FloatArray, at: Int) {
        while (needSize > 0 && needAt[needFront] < outCount) popFront()
        val target = if (rampedAttack) rampTarget() else if (needSize > 0) need[needFront] else 1f
        gain = if (target < gain) {
            target
        } else {
            val next = gain + (target - gain) * release
            // The step lost in rounding would leave the level just short
            // of its target for good (0.99986 for unity at 48 kHz).
            if (next == gain) target else next
        }
        val from = head * channels
        for (ch in 0 until channels) {
            var y = held[from + ch] * gain
            if (enabled) y = y.coerceIn(-ceiling, ceiling)
            output[at + ch] = y
        }
        outCount++
    }

    // The lowest of the straight lines from unity, as each frame in view
    // comes into view, down to its need as it comes out. A need read
    // between samples comes into view as late as the frame that completed
    // the reading, so its line reaches it as the first sample the reading
    // used comes out, and holds it from there.
    private fun rampTarget(): Float {
        var target = 1f
        var frame = outCount
        val span = latency - (TRUE_PEAK_TAPS - 1)
        while (frame < inCount) {
            val slot = (frame % needOf.size).toInt()
            val wanted = needOf[slot]
            if (wanted < 1f) {
                val ahead = (frame - outCount).toFloat()
                target = minOf(target, wanted + (1f - wanted) * ahead / latency)
            }
            if (readsBetween) {
                val between = betweenOf[slot]
                if (between < 1f) {
                    val ahead = max(0L, frame - (TRUE_PEAK_TAPS - 1) - outCount).toFloat()
                    target = minOf(target, between + (1f - between) * ahead / span)
                }
            }
            frame++
        }
        return target
    }

    private fun remember(input: FloatArray, at: Int) {
        for (ch in 0 until channels) {
            val slot = ch * TRUE_PEAK_TAPS * 2 + recentAt
            recent[slot] = input[at + ch]
            recent[slot + TRUE_PEAK_TAPS] = input[at + ch]
        }
        recentAt = (recentAt + 1) % TRUE_PEAK_TAPS
    }

    // The loudest point read between the two samples in the middle of the
    // frames remembered, on any channel.
    private fun peakBetween(): Float {
        var peak = 0f
        for (ch in 0 until channels) {
            val from = ch * TRUE_PEAK_TAPS * 2 + recentAt
            for (taps in betweenTaps) {
                var y = 0f
                for (j in 0 until TRUE_PEAK_TAPS) y += recent[from + j] * taps[j]
                peak = max(peak, abs(y))
            }
        }
        return peak
    }

    private fun pushNeed(level: Float) {
        // Anything asking for more level than this one can never be the
        // lowest while this one is in view.
        while (needSize > 0 && need[(needFront + needSize - 1) % need.size] >= level) needSize--
        val slot = (needFront + needSize) % need.size
        need[slot] = level
        needAt[slot] = inCount
        needSize++
    }

    private fun popFront() {
        needFront = (needFront + 1) % need.size
        needSize--
    }
}
