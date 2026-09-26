package app.winters.octo.sound

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// The plain text filter list other equalizers read and write, one filter a
// line, like "Filter 1: ON PK Fc 105 Hz Gain 6.4 dB Q 0.70", after a
// "Preamp: -6.1 dB" line. Headphone corrections are shared in this form.
object ParametricEqFile {
    // What a file holds: its level change and its filters.
    data class Contents(val preampDb: Float, val filters: List<EqFilter>)

    private val preampLine = Regex("""Preamp:\s*([+-]?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterLine = Regex(
        """Filter\s+\d+:\s+ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+([+-]?[\d.]+)\s+dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )

    // Reads a file. Lines it does not understand, and filters switched off,
    // are skipped.
    fun parse(text: String): Contents {
        var preamp = 0f
        val filters = mutableListOf<EqFilter>()
        text.lineSequence().forEach { line ->
            preampLine.find(line)?.let { match ->
                match.groupValues[1].toFloatOrNull()?.let { preamp = it }
            }
            filterLine.find(line)?.let { match ->
                val (kind, fc, gain, q) = match.destructured
                val frequency = fc.toFloatOrNull() ?: return@let
                val gainDb = gain.toFloatOrNull() ?: return@let
                val width = q.toFloatOrNull()?.takeIf { it > 0f } ?: return@let
                filters += EqFilter(typeFor(kind), frequency, gainDb, width)
            }
        }
        return Contents(preamp, filters)
    }

    // A headphone correction from a file, named after it, or nothing when
    // the file has no filters in it.
    fun correction(fileName: String, text: String): HeadphoneCorrection? {
        val contents = parse(text)
        if (contents.filters.isEmpty()) return null
        return HeadphoneCorrection(nameFrom(fileName), IMPORTED, contents.preampDb, contents.filters)
    }

    // The name a file gives a correction: its name without the extension,
    // and without the "ParametricEQ" such files usually end in.
    fun nameFrom(fileName: String): String {
        val bare = fileName.substringBeforeLast('.').trim()
        val short = bare.removeSuffix("ParametricEQ").trim().trimEnd('-', '_').trim()
        return short.ifEmpty { bare.ifEmpty { "Correction" } }
    }

    // Writes filters as a file another equalizer can load.
    fun write(preampDb: Float, filters: List<EqFilter>): String = buildString {
        append("Preamp: ").append(decimal(preampDb, 1)).append(" dB\n")
        filters.forEachIndexed { index, filter ->
            append("Filter ").append(index + 1).append(": ")
            append(if (filter.enabled) "ON " else "OFF ")
            append(codeFor(filter.type))
            append(" Fc ").append(frequency(filter.frequency)).append(" Hz")
            append(" Gain ").append(decimal(filter.gainDb, 1)).append(" dB")
            append(" Q ").append(decimal(filter.q, 2)).append('\n')
        }
    }

    // Who measured an imported correction, as far as Octo can tell.
    const val IMPORTED = "Imported"

    private fun typeFor(code: String) = when (code.uppercase(Locale.ROOT)) {
        "LSC" -> FilterType.LowShelf
        "HSC" -> FilterType.HighShelf
        else -> FilterType.Peak
    }

    private fun codeFor(type: FilterType) = when (type) {
        FilterType.Peak -> "PK"
        FilterType.LowShelf -> "LSC"
        FilterType.HighShelf -> "HSC"
    }

    // Whole hertz where the frequency is whole, otherwise one decimal.
    private fun frequency(hz: Float): String {
        val whole = hz.roundToInt()
        return if (abs(hz - whole) < 0.05f) whole.toString() else decimal(hz, 1)
    }

    // Always with a dot, whatever the phone's language.
    private fun decimal(value: Float, places: Int): String {
        val shown = String.format(Locale.ROOT, "%.${places}f", value)
        // No "-0.0".
        return if (shown.trimStart('-').all { it == '0' || it == '.' }) shown.trimStart('-') else shown
    }
}
