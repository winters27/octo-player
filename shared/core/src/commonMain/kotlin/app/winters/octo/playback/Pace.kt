package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// How fast music can play, from half speed to double.
const val SLOWEST_SPEED = 0.5f
const val FASTEST_SPEED = 2.0f

// Speeds the slider settles on when the finger lands near them.
val SpeedDetents = listOf(0.75f, 1f, 1.25f, 1.5f)

// How near a detent the finger has to be for the slider to settle on it.
private const val DETENT_PULL = 0.04f

// Between detents the speed moves in steps this size.
private const val SPEED_STEP = 0.05f

// How far the pitch can be moved either way, in semitones.
const val PITCH_RANGE_SEMITONES = 6

// A speed the player can use: inside the range, on a 0.05 step, and pulled
// onto a detent when close to one, so 1.0 is easy to find again.
fun snapSpeed(raw: Float): Float {
    val inRange = raw.coerceIn(SLOWEST_SPEED, FASTEST_SPEED)
    SpeedDetents.firstOrNull { abs(it - inRange) <= DETENT_PULL }?.let { return it }
    val stepped = (inRange / SPEED_STEP).roundToInt() * SPEED_STEP
    // Rounded to two places, so 1.1500001 is saved and shown as 1.15.
    return ((stepped * 100).roundToInt() / 100f).coerceIn(SLOWEST_SPEED, FASTEST_SPEED)
}

// Where a speed sits along the slider, 0 at the slowest and 1 at the fastest.
fun speedFraction(speed: Float): Float =
    ((speed - SLOWEST_SPEED) / (FASTEST_SPEED - SLOWEST_SPEED)).coerceIn(0f, 1f)

// The speed at a point along the slider.
fun speedAt(fraction: Float): Float =
    snapSpeed(SLOWEST_SPEED + fraction.coerceIn(0f, 1f) * (FASTEST_SPEED - SLOWEST_SPEED))

// The pitch shift at a point along its slider, in whole semitones.
fun semitonesAt(fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * 2 * PITCH_RANGE_SEMITONES).roundToInt() - PITCH_RANGE_SEMITONES

fun semitonesFraction(semitones: Int): Float =
    (semitones.coerceIn(-PITCH_RANGE_SEMITONES, PITCH_RANGE_SEMITONES) + PITCH_RANGE_SEMITONES) / (2f * PITCH_RANGE_SEMITONES)

// What the player is told: the speed, and the pitch as a multiple of the
// original. With "keep pitch" on the voice stays where it was; with it off
// the pitch rises and falls with the speed, like a record. Semitones move
// it further either way.
data class Pace(val speed: Float, val pitch: Float)

fun paceOf(speed: Float, keepPitch: Boolean, semitones: Int): Pace {
    val clean = snapSpeed(speed)
    val shift = 2f.pow(semitones.coerceIn(-PITCH_RANGE_SEMITONES, PITCH_RANGE_SEMITONES) / 12f)
    return Pace(clean, (if (keepPitch) 1f else clean) * shift)
}

// A speed as the player shows it, like "1.25x" or "2x".
fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val part = hundredths % 100
    val text = when {
        part == 0 -> "$whole"
        part % 10 == 0 -> "$whole.${part / 10}"
        else -> "$whole.${part.toString().padStart(2, '0')}"
    }
    return "${text}x"
}

// A pitch shift as its slider shows it, like "+2 st" or "0 st".
fun semitonesLabel(semitones: Int): String = if (semitones > 0) "+$semitones st" else "$semitones st"
