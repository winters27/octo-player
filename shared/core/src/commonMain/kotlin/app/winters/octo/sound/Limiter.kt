package app.winters.octo.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

// Just below full scale, where the limiter holds peaks.
const val LIMITER_CEILING_DB = -1f

// How far ahead the limiter looks, and how slowly the level comes back.
const val LIMITER_LOOKAHEAD_MS = 1.5f
const val LIMITER_RELEASE_MS = 100f

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
class Limiter(
    val channels: Int,
    sampleRate: Int,
    ceilingDb: Float = LIMITER_CEILING_DB,
    lookaheadMs: Float = LIMITER_LOOKAHEAD_MS,
    releaseMs: Float = LIMITER_RELEASE_MS,
    val rampedAttack: Boolean = false,
) {
    // Frames of delay.
    val latency: Int = max(1, (sampleRate * lookaheadMs / 1_000f).roundToInt())

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
            pushNeed(wanted)
            if (rampedAttack) needOf[(inCount % needOf.size).toInt()] = wanted
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
        require(other.channels == channels && other.latency == latency && other.rampedAttack == rampedAttack) { "limiters built differently" }
        other.held.copyInto(held)
        head = other.head
        heldFrames = other.heldFrames
        other.need.copyInto(need)
        other.needAt.copyInto(needAt)
        needFront = other.needFront
        needSize = other.needSize
        other.needOf.copyInto(needOf)
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
    // comes into view, down to its need as it comes out.
    private fun rampTarget(): Float {
        var target = 1f
        var frame = outCount
        while (frame < inCount) {
            val wanted = needOf[(frame % needOf.size).toInt()]
            if (wanted < 1f) {
                val ahead = (frame - outCount).toFloat()
                target = minOf(target, wanted + (1f - wanted) * ahead / latency)
            }
            frame++
        }
        return target
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
