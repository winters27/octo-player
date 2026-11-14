package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The curve weight of each kind of transition: how much of the S-shaped
// curve is mixed into the equal-power one. Higher swaps the songs faster
// around the middle.
const val CUT_CURVE = 0.75
const val LIFT_CURVE = 0.55
const val BLEND_CURVE = 0.2
const val CROSSFADE_CURVE = 0.4

// How deep the filter sweeps go, 0 for none and 1 for the full range.
const val FILTER_STRENGTH = 0.7

// The outgoing song's low-pass runs from OPEN_LOW_PASS_HZ down toward
// LOW_PASS_FLOOR_HZ.
const val OPEN_LOW_PASS_HZ = 18_000.0
const val LOW_PASS_FLOOR_HZ = 450.0

// The outgoing song's bass cut rises from OPEN_HIGH_PASS_HZ toward
// BASS_SWAP_HZ by the middle of the blend.
const val OPEN_HIGH_PASS_HZ = 10.0
const val BASS_SWAP_HZ = 300.0
const val BASS_SWAP_AT = 0.5

// The incoming song's high-pass starts at INCOMING_HIGH_PASS_HZ and opens to
// INCOMING_OPEN_HZ by INCOMING_OPEN_AT through the blend.
const val INCOMING_HIGH_PASS_HZ = 675.0
const val INCOMING_OPEN_HZ = 20.0
const val INCOMING_OPEN_AT = 0.6

// After the blend a tempo-matched incoming song eases back to its own speed
// over this long.
const val BEAT_MATCH_SETTLE_MS = 5_000.0

private fun sigmoid12(t: Double): Double = 1 / (1 + exp(-12 * (t - 0.5)))

private val SIGMOID_LOW = sigmoid12(0.0)
private val SIGMOID_HIGH = sigmoid12(1.0)

// The S curve scaled to run exactly from 0 at the start to 1 at the end.
fun sCurve(t: Double): Double = (sigmoid12(t.coerceIn(0.0, 1.0)) - SIGMOID_LOW) / (SIGMOID_HIGH - SIGMOID_LOW)

// The outgoing song's volume at `t` through the blend (0 to 1): an
// equal-power fade mixed with an S curve by weight `k`.
fun automixOutGain(t: Double, k: Double): Double {
    val p = t.coerceIn(0.0, 1.0)
    return (cos(p * PI / 2) * (1 - k) + (1 - sCurve(p)) * k).coerceIn(0.0, 1.0)
}

// The incoming song's volume: whatever keeps the two volumes' squares at one.
fun automixInGain(t: Double, k: Double): Double {
    val out = automixOutGain(t, k)
    return sqrt(max(0.0, 1 - out * out))
}

// Where a blend whose start is held is along its curve at `t` (0 to 1): the
// outgoing song alone at its own level until `holdShare`, while the
// incoming one's first sound is still to come, then the curve over the
// rest of the overlap. Below 0 while held, where every curve gives the
// outgoing song 1 and the incoming one 0.
fun pastHold(t: Double, holdShare: Double): Double = when {
    holdShare <= 0 -> t
    holdShare >= 1 -> -1.0
    else -> (t - holdShare) / (1 - holdShare)
}

// The outgoing song's volume at `t` through an intro (0 to 1): full until
// `holdShare`, then an equal-power fade to nothing at the end.
fun introOutGain(t: Double, holdShare: Double): Double {
    val p = t.coerceIn(0.0, 1.0)
    val hold = holdShare.coerceIn(0.0, 1.0)
    if (p <= hold) return 1.0
    return cos((p - hold) / (1 - hold) * PI / 2).coerceIn(0.0, 1.0)
}

// The incoming song's volume through an intro: rising as sin(pi/2 * t /
// rise) to `level` by `rise` (riseShare, ending by `holdShare` at the
// latest), then `level` until `holdShare`, so its quiet start plays under
// the outgoing song at that much of its own level; past the hold it comes
// up as the outgoing song's power leaves, sqrt(level^2 + (1 - level^2) *
// (1 - out^2)) with `out` the outgoing song's introOutGain, to its own
// level at the end. At level 1 it is full from the rise's end on.
fun introInGain(t: Double, riseShare: Double, holdShare: Double, level: Double): Double {
    val p = t.coerceIn(0.0, 1.0)
    val hold = holdShare.coerceIn(0.0, 1.0)
    val rise = riseShare.coerceIn(0.0, 1.0).coerceAtMost(hold)
    val g = level.coerceIn(0.0, 1.0)
    if (p < rise) return (sin(PI / 2 * p / rise) * g).coerceIn(0.0, 1.0)
    if (p <= hold) return g
    val out = introOutGain(p, holdShare)
    return sqrt(g * g + (1 - g * g) * (1 - out * out))
}

// Moves from `from` to `to` on a log scale as u goes from 0 to 1.
private fun logBetween(from: Double, to: Double, u: Double): Double = from * (to / from).pow(u.coerceIn(0.0, 1.0))

// The outgoing song's low-pass cutoff in Hz at `t`, falling smoothly.
fun outgoingLowPassHz(t: Double, strength: Double): Double {
    val end = OPEN_LOW_PASS_HZ * (LOW_PASS_FLOOR_HZ / OPEN_LOW_PASS_HZ).pow(strength.coerceIn(0.0, 1.0))
    return logBetween(OPEN_LOW_PASS_HZ, end, t)
}

// The same low-pass moving in steps on the beat: it holds each beat's
// cutoff until `glide` before the next beat, then glides to the next one.
// `beats` are the beats' places through the blend (0 to 1, rising) and
// `glide` is in the same units.
fun steppedOutgoingLowPassHz(t: Double, strength: Double, beats: DoubleArray, glide: Double): Double {
    val p = t.coerceIn(0.0, 1.0)
    var from = 0.0
    var to = 1.0
    for (beat in beats) {
        if (beat <= 0.0 || beat >= 1.0) continue
        if (beat <= p) from = beat else { to = beat; break }
    }
    val g = min(max(0.0, glide), to - from)
    val glideStart = to - g
    val at = if (p < glideStart || g <= 0.0) from else from + (p - glideStart) / g * (to - from)
    return outgoingLowPassHz(at, strength)
}

// The outgoing song's bass cut in Hz at `t`: rises to its top by the middle
// of the blend and holds there.
fun outgoingHighPassHz(t: Double, strength: Double): Double {
    val top = max(OPEN_HIGH_PASS_HZ, BASS_SWAP_HZ * strength.coerceIn(0.0, 1.0))
    return logBetween(OPEN_HIGH_PASS_HZ, top, t / BASS_SWAP_AT)
}

// The incoming song's high-pass cutoff in Hz at `t`: thin at first, fully
// open by INCOMING_OPEN_AT.
fun incomingHighPassHz(t: Double, strength: Double): Double {
    val start = max(INCOMING_OPEN_HZ, INCOMING_HIGH_PASS_HZ * strength.coerceIn(0.0, 1.0))
    return logBetween(start, INCOMING_OPEN_HZ, t / INCOMING_OPEN_AT)
}

// The incoming song's playback rate `sinceEntryMs` after it came in: `rate`
// through the blend, then eased back to 1 over BEAT_MATCH_SETTLE_MS.
fun beatMatchRateAt(rate: Double, sinceEntryMs: Double, overlapMs: Double): Double {
    if (sinceEntryMs <= overlapMs) return rate
    val u = (sinceEntryMs - overlapMs) / BEAT_MATCH_SETTLE_MS
    if (u >= 1) return 1.0
    return rate + (1 - rate) * (1 - cos(PI * u)) / 2
}
