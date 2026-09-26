package app.winters.octo.ui.sound

import app.winters.octo.sound.EqFilter
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.EqPreset
import app.winters.octo.sound.FilterType
import app.winters.octo.sound.GAIN_RANGE_DB
import app.winters.octo.sound.GRAPHIC_Q
import app.winters.octo.sound.GraphicBands
import app.winters.octo.sound.SoundSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The changes the Sound page makes to the settings, kept apart from the
// page so they can be tested on their own.

// How many filters a parametric curve can have.
const val MAX_FILTERS = 16

// The range the curve is drawn over, and a filter can be moved across.
const val MIN_HZ = 20f
const val MAX_HZ = 20_000f

// How narrow or wide a filter can be made.
const val MIN_Q = 0.1f
const val MAX_Q = 10f

// What the label for an unnamed curve says.
const val CUSTOM = "Custom"

// A gain in tenths of a decibel, inside the slider range. Close to zero
// snaps to zero, so flat is easy to find again by hand.
fun cleanGain(db: Float): Float {
    val clamped = db.coerceIn(-GAIN_RANGE_DB, GAIN_RANGE_DB)
    if (abs(clamped) < 0.25f) return 0f
    return (clamped * 10f).roundToInt() / 10f
}

// A preset's curve on the ten bands, switching the equalizer on so the
// listener hears what they picked.
fun withPreset(settings: SoundSettings, preset: EqPreset): SoundSettings = settings.copy(
    eqEnabled = true,
    mode = EqMode.Graphic,
    graphicGains = preset.gains,
    preset = preset.name,
)

// One band moved by hand. The curve no longer matches a preset.
fun withBandGain(settings: SoundSettings, band: Int, gainDb: Float): SoundSettings {
    if (band !in settings.graphicGains.indices) return settings
    val gains = settings.graphicGains.toMutableList().also { it[band] = cleanGain(gainDb) }
    return settings.copy(graphicGains = gains, preset = null)
}

// The ten bands as parametric filters: the same sound, now free to move.
fun seedFromGraphic(gains: List<Float>): List<EqFilter> =
    GraphicBands.zip(gains) { frequency, gain -> EqFilter(FilterType.Peak, frequency, gain, GRAPHIC_Q) }

// Switches between the ten bands and free filters. Going to parametric
// starts from the ten bands, so the sound does not jump; only a flat set of
// bands leaves filters made earlier in place.
fun withMode(settings: SoundSettings, mode: EqMode): SoundSettings {
    if (mode == settings.mode) return settings
    return when (mode) {
        EqMode.Graphic -> settings.copy(mode = EqMode.Graphic)
        EqMode.Parametric -> {
            val keep = settings.filters.isNotEmpty() && settings.graphicGains.all { it == 0f }
            settings.copy(mode = EqMode.Parametric, filters = if (keep) settings.filters else seedFromGraphic(settings.graphicGains))
        }
    }
}

// One filter changed by hand, kept inside the ranges the page offers.
fun withFilter(settings: SoundSettings, index: Int, filter: EqFilter): SoundSettings {
    if (index !in settings.filters.indices) return settings
    val clean = filter.copy(
        frequency = filter.frequency.coerceIn(MIN_HZ, MAX_HZ),
        gainDb = cleanGain(filter.gainDb),
        q = filter.q.coerceIn(MIN_Q, MAX_Q),
    )
    val filters = settings.filters.toMutableList().also { it[index] = clean }
    return settings.copy(filters = filters, preset = null)
}

// A new flat filter in the widest empty stretch of the curve, or nothing
// new once there are as many as the equalizer takes.
fun withNewFilter(settings: SoundSettings): SoundSettings {
    if (settings.filters.size >= MAX_FILTERS) return settings
    val filter = EqFilter(FilterType.Peak, emptiestFrequency(settings.filters.map { it.frequency }), 0f, 1f)
    return settings.copy(filters = settings.filters + filter, preset = null)
}

fun withoutFilter(settings: SoundSettings, index: Int): SoundSettings {
    if (index !in settings.filters.indices) return settings
    return settings.copy(filters = settings.filters.filterIndexed { i, _ -> i != index }, preset = null)
}

// The middle, on the log scale, of the widest gap between the frequencies
// already used, rounded to a tidy number.
fun emptiestFrequency(used: List<Float>): Float {
    val points = (listOf(MIN_HZ, MAX_HZ) + used.map { it.coerceIn(MIN_HZ, MAX_HZ) }).sorted()
    val (low, high) = points.zipWithNext().maxBy { (a, b) -> ln(b / a) }
    return tidyHz(sqrt(low * high))
}

// A frequency rounded the way a person would type it: 47, 120, 1,300, 12,000.
fun tidyHz(hz: Float): Float {
    val step = when {
        hz < 100f -> 1f
        hz < 1_000f -> 10f
        hz < 10_000f -> 100f
        else -> 1_000f
    }
    return ((hz / step).roundToInt() * step).coerceIn(MIN_HZ, MAX_HZ)
}

// What the preset row marks as chosen: the preset the ten bands came from,
// or "Custom" for a curve changed by hand or made of free filters.
fun presetLabel(settings: SoundSettings): String =
    if (settings.mode == EqMode.Graphic) settings.preset ?: CUSTOM else CUSTOM

// The equalizer in a few words, for the line that opens the Sound page.
fun soundSummary(settings: SoundSettings): String = when {
    !settings.eqEnabled -> "Equalizer off"
    settings.correction != null -> "Equalizer on, ${presetLabel(settings)}, with ${settings.correction.name}"
    else -> "Equalizer on, ${presetLabel(settings)}"
}

// A frequency as a short label: 31, 500, 1k, 1.2k, 16k.
fun shortHz(hz: Float): String = when {
    hz < 1_000f -> hz.roundToInt().toString()
    else -> {
        val k = (hz / 100f).roundToInt() / 10f
        if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.ROOT, "%.1fk", k)
    }
}

// A frequency as a reading: 62 Hz, 1.2 kHz.
fun readHz(hz: Float): String = if (hz < 1_000f) "${hz.roundToInt()} Hz" else "${shortHz(hz).removeSuffix("k")} kHz"

// A gain as a reading: +3.5 dB, 0 dB, -2 dB.
fun readDb(db: Float): String {
    val tenths = (db * 10f).roundToInt()
    if (tenths == 0) return "0 dB"
    val sign = if (tenths > 0) "+" else "-"
    val size = abs(tenths)
    val number = if (size % 10 == 0) "${size / 10}" else "${size / 10}.${size % 10}"
    return "$sign$number dB"
}

// What a screen reader says for a point on the curve: "62 Hz, +3 dB".
fun describeNode(hz: Float, db: Float): String = "${hz.roundToInt()} Hz, ${readDb(db)}"
