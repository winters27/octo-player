package app.winters.octo.sound

import app.winters.octo.playback.BEATS_PER_BAR
import app.winters.octo.playback.HIGHEST_CUTOFF_SHARE
import app.winters.octo.playback.TransitionPlan
import app.winters.octo.playback.steppedOutgoingLowPassHz
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The volume never moves faster than from silence to full in this long, so
// an armed or dropped transition never clicks.
const val TRANSITION_SLEW_MS = 30.0

// Filters come in and go out over this long, mixed with the sound as it
// was. Switching them off at once would step the waveform by the filter's
// phase shift: a click on bass notes.
const val FILTER_ENGAGE_MS = 10.0

// The volume and filter cutoffs are worked out again every this many frames.
const val AUTOMATION_BLOCK_FRAMES = 32

// The outgoing song fades out at once (as fast as the volume may move)
// when it stays below its silence gate this long during the blend.
const val EARLY_FADE_MS = 300.0

// The headroom comes in over this share of the blend at its start and goes
// out over the same share at its end.
const val HEADROOM_RAMP = 0.15

// What one deck does through a transition, worked out from that deck's
// own song time. The outgoing deck follows the plan from its start
// (startMs in its song); the incoming deck from its entry (entryMs in its
// song), at beatMatchRate when it is set, so both reach the same place in
// the blend at the same moment once their songs are lined up. `entryId`
// is the queue entry it belongs to: other songs on the same deck are left
// alone. `stepped` lets the low-pass step on the beat when the plan is
// locked to the bar. `gateDb`, for the outgoing deck, is the level below
// which the song counts as silent.
class DeckTransition(
    val entryId: String?,
    val plan: TransitionPlan,
    val incoming: Boolean,
    val stepped: Boolean = plan.barLocked,
    val gateDb: Double? = null,
) {
    // The beats inside the blend, as places through it, and how long the
    // low-pass glides between steps, in the same units.
    internal val beats: DoubleArray = if (stepped && !incoming) plan.beatProgress() else DoubleArray(0)
    internal val glide: Double =
        if (beats.isEmpty() || plan.overlapMs <= 0) 0.0 else (plan.beatMs ?: 0.0) * BEATS_PER_BAR / 8 / plan.overlapMs

    private val headroom = 10.0.pow(-plan.headroomDb / 20)

    // How far through the blend this deck is at `songMs` of its own song:
    // below 0 before it, 1 and above after it.
    fun progressAt(songMs: Double): Double {
        val since = if (incoming) (songMs - plan.entryMs) / (plan.beatMatchRate ?: 1.0) else songMs - plan.startMs
        val length = plan.overlapMs.toDouble()
        if (length <= 0) return if (since >= 0) 1.0 else -1.0
        return since / length
    }

    // The volume at `t` through the blend, before an early fade.
    fun gainAt(t: Double): Double = when {
        incoming && t < 0 -> 0.0
        incoming && t >= 1 -> 1.0
        incoming -> plan.inGain(t) * headroomAt(t)
        t < 0 -> 1.0
        t >= 1 -> 0.0
        else -> plan.outGain(t) * headroomAt(t)
    }

    // The headroom's share of its full cut at `t`: ramped in and out at the
    // ends of the blend, where one song is nearly silent anyway.
    private fun headroomAt(t: Double): Double {
        val w = min(1.0, min(t, 1 - t) / HEADROOM_RAMP).coerceAtLeast(0.0)
        return if (w >= 1.0) headroom else headroom.pow(w)
    }

    // Whether the filters run at `t`: only inside the blend, and only with
    // sweeps in the plan.
    fun filtersAt(t: Double): Boolean = plan.filterStrength > 0 && t >= 0 && t < 1

    // The low-pass cutoff, outgoing only.
    fun lowPassAt(t: Double): Double =
        if (beats.isEmpty()) plan.outgoingLowPassAt(t) else steppedOutgoingLowPassHz(t, plan.filterStrength, beats, glide)

    // The high-pass cutoff: the bass swap going out, the thin start coming in.
    fun highPassAt(t: Double): Double = if (incoming) plan.incomingHighPassAt(t) else plan.outgoingHighPassAt(t)

    // Whether this deck has nothing left to do in the transition from `t` on.
    fun doneAt(t: Double): Boolean = t >= 1
}

// The two kinds of filter the sweeps use.
enum class FilterKind { LowPass, HighPass }

// A second-order section's coefficients for a cutoff, normalized so a0 is
// 1, in the order b0, b1, b2, a1, a2: the same Butterworth "cookbook"
// filters as the shared Biquad, with the cutoff held between 1 Hz and
// HIGHEST_CUTOFF_SHARE of the rate.
fun sweepCoefficients(kind: FilterKind, sampleRate: Int, hz: Double, into: DoubleArray = DoubleArray(5)): DoubleArray {
    val cutoff = hz.coerceIn(1.0, sampleRate * HIGHEST_CUTOFF_SHARE)
    val w0 = 2 * PI * cutoff / sampleRate
    val c = cos(w0)
    val alpha = sin(w0) / (2 * 0.7071067811865476)
    val a0 = 1 + alpha
    if (kind == FilterKind.LowPass) {
        into[0] = (1 - c) / 2 / a0
        into[1] = (1 - c) / a0
    } else {
        into[0] = (1 + c) / 2 / a0
        into[1] = -(1 + c) / a0
    }
    into[2] = into[0]
    into[3] = -2 * c / a0
    into[4] = (1 - alpha) / a0
    return into
}

// A second-order filter whose cutoff can move while it runs, one state per
// channel, so a sweep never restarts the filter.
class SweptFilter(private val kind: FilterKind, private val sampleRate: Int, private val channels: Int) {
    private val k = DoubleArray(5)
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)
    private var hz = -1.0

    // Moves the cutoff; a change under a thousandth is not worth working out.
    fun setCutoff(next: Double) {
        if (hz > 0 && abs(next - hz) <= hz * 1e-3) return
        hz = next
        sweepCoefficients(kind, sampleRate, next, k)
    }

    fun process(x: Double, channel: Int): Double {
        val y = k[0] * x + k[1] * x1[channel] + k[2] * x2[channel] - k[3] * y1[channel] - k[4] * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = x
        y2[channel] = y1[channel]
        y1[channel] = y
        return y
    }

    fun reset() {
        x1.fill(0.0)
        x2.fill(0.0)
        y1.fill(0.0)
        y2.fill(0.0)
        hz = -1.0
    }
}

// Runs a deck's part of a transition on its sound: the volume curve with
// its headroom, the filter sweeps, and for the outgoing song an early fade
// once it has gone silent. Every sample is keyed to the song time it
// belongs to, so the decks follow the plan however far ahead of the
// speaker their sound is worked out. With no transition, or once it is
// done and the volume is back at full, the sound passes through untouched.
class TransitionShaper(val sampleRate: Int, val channels: Int) {
    private val lowPass = SweptFilter(FilterKind.LowPass, sampleRate, channels)
    private val highPass = SweptFilter(FilterKind.HighPass, sampleRate, channels)
    private val slew = 1.0 / (sampleRate * TRANSITION_SLEW_MS / 1000)
    private val engageStep = 1.0 / max(1.0, sampleRate * FILTER_ENGAGE_MS / 1000)

    private var current: DeckTransition? = null
    private var gain = 1.0
    private var wet = 0.0
    private var lowPassOn = false

    // The early fade: how long the song has been below its gate, and
    // whether it has faded out for good.
    private val levelFrames = max(1, sampleRate / 100)
    private var levelSquares = 0.0
    private var levelCount = 0
    private var quietMs = 0.0
    private var faded = false

    // Takes up a transition, or drops it with null. An incoming song armed
    // fresh takes its first volume at once, so it starts silent instead of
    // fading down. An outgoing song is audible already, so a transition
    // armed after its start glides to the curve instead of jumping.
    fun arm(transition: DeckTransition?, songMs: Double) {
        if (transition === current) return
        val fresh = current == null && gain == 1.0
        current = transition
        faded = false
        quietMs = 0.0
        levelSquares = 0.0
        levelCount = 0
        if (transition != null && fresh && transition.incoming) gain = transition.gainAt(transition.progressAt(songMs))
    }

    // Changes `frames` frames of `samples` in place. The first frame is at
    // `songMs` in its song and each frame moves the song on `msPerFrame`.
    fun process(samples: FloatArray, frames: Int, songMs: Double, msPerFrame: Double) {
        val transition = current
        if (gain == 1.0 && wet == 0.0) {
            if (transition == null) return
            // An outgoing song before its blend is left exactly as it is.
            if (!transition.incoming && transition.progressAt(songMs + frames * msPerFrame) < 0) return
        }
        var frame = 0
        while (frame < frames) {
            val count = min(AUTOMATION_BLOCK_FRAMES, frames - frame)
            val at = songMs + frame * msPerFrame
            val t = transition?.progressAt(at)
            var target = if (transition == null || t == null) 1.0 else transition.gainAt(t)
            val filtersOn = transition != null && t != null && transition.filtersAt(t)
            if (filtersOn) {
                val outgoing = !transition!!.incoming
                lowPassOn = outgoing
                if (outgoing) lowPass.setCutoff(transition.lowPassAt(t!!))
                highPass.setCutoff(transition.highPassAt(t!!))
            }
            if (transition != null && t != null && !transition.incoming && transition.gateDb != null) {
                watchSilence(samples, frame, count, t, transition.gateDb)
            }
            if (faded) target = 0.0
            val wetTarget = if (filtersOn) 1.0 else 0.0
            for (i in 0 until count) {
                gain += (target - gain).coerceIn(-slew, slew)
                if (wet != wetTarget) wet = if (wetTarget > wet) min(wetTarget, wet + engageStep) else max(wetTarget, wet - engageStep)
                val g = gain
                val base = (frame + i) * channels
                for (ch in 0 until channels) {
                    val x = samples[base + ch].toDouble()
                    var y = x
                    if (wet > 0) {
                        var f = highPass.process(x, ch)
                        if (lowPassOn) f = lowPass.process(f, ch)
                        y = x + (f - x) * wet
                    }
                    samples[base + ch] = (y * g).toFloat()
                }
            }
            if (wet == 0.0 && !filtersOn) {
                lowPass.reset()
                highPass.reset()
                lowPassOn = false
            }
            frame += count
        }
        if (transition != null && gain == 1.0 && wet == 0.0 && transition.incoming && transition.doneAt(transition.progressAt(songMs + frames * msPerFrame))) {
            // An incoming song past its blend is simply playing now.
            current = null
        }
    }

    // Measures the outgoing song in 10 ms steps through the blend, before
    // any volume change, and fades it out once it has stayed below its gate
    // for EARLY_FADE_MS.
    private fun watchSilence(samples: FloatArray, from: Int, count: Int, t: Double, gateDb: Double) {
        if (faded || t < 0 || t >= 1) return
        for (i in 0 until count) {
            val base = (from + i) * channels
            var sum = 0.0
            for (ch in 0 until channels) sum += samples[base + ch]
            val mono = sum / channels
            levelSquares += mono * mono
            if (++levelCount == levelFrames) {
                val db = if (levelSquares <= 1e-10 * levelCount) -100.0 else 10 * log10(levelSquares / levelCount)
                quietMs = if (db < gateDb) quietMs + levelCount * 1000.0 / sampleRate else 0.0
                levelSquares = 0.0
                levelCount = 0
                if (quietMs >= EARLY_FADE_MS) {
                    faded = true
                    return
                }
            }
        }
    }
}
