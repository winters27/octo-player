package app.winters.octo.desktop.pages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.winters.octo.design.OctoColors
import app.winters.octo.desktop.readIn
import app.winters.octo.theme.ThemeChoice
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

// The lines under the server in use are read on the chosen fill, in every theme.
class InUseSubtitleContrastTest {
    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(ink: Color, fill: Color): Double {
        val (hi, lo) = listOf(luminance(ink.compositeOver(fill)), luminance(fill)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private class Reading(val fill: Color, val subtitle: Color, val name: Color, val muted: Color)

    @Test
    fun theSubtitleReadsOnTheChosenFill() {
        ThemeChoice.entries.forEach { theme ->
            val read = readIn(theme) {
                Reading(OctoColors.AccentSelected.compositeOver(OctoColors.BackgroundTertiary), InUseSubtitle, OctoColors.TextPrimary, OctoColors.TextMuted)
            }
            val ratio = contrast(read.subtitle, read.fill)
            val name = contrast(read.name, read.fill)
            println("CONTRAST ${theme.name} in-use subtitle and mark ${"%.2f".format(ratio)} name ${"%.2f".format(name)} muted ${"%.2f".format(contrast(read.muted, read.fill))} ink ${read.subtitle.compositeOver(read.fill)}")
            assertTrue("${theme.name}: the subtitle and the mark are $ratio:1", ratio >= 4.5)
            assertTrue("${theme.name}: quieter than the name: $ratio vs $name", ratio < name)
        }
    }
}
