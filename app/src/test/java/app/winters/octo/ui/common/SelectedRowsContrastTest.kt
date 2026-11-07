package app.winters.octo.ui.common

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.design.SelectedFill
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// The words on a selected row, as GlassMenu's chosen option and the server
// in use in ServersSection draw them: main words in the theme's words, the
// quieter lines in the color solved for the fill, all on the accent's
// selected fill, over every layer a row sits on, in every theme and accent.
@RunWith(RobolectricTestRunner::class)
class SelectedRowsContrastTest {
    private class Row(val layers: List<Pair<String, Color>>, val fill: Color, val main: Color, val secondary: Color, val muted: Color)

    private fun read(theme: ThemeChoice, accent: AccentChoice): Row {
        var row: Row? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ProvideOctoTheme(resolvePalette(theme, accent, null, false)) {
                val page = OctoColors.Background
                val layers = listOf(
                    "page" to page,
                    "card" to OctoColors.BackgroundTertiary.compositeOver(page),
                    "raised" to OctoColors.SurfaceRaised.compositeOver(page),
                )
                val fill = OctoColors.AccentSelected
                val main = OctoColors.TextPrimary
                SelectedFill { row = Row(layers, fill, main, OctoColors.TextSecondary, OctoColors.TextMuted) }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        return row!!
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun selectedRowsReadInEveryThemeAndAccent() {
        val low = mutableListOf<String>()
        var checked = 0
        for (theme in ThemeChoice.entries) for (accent in AccentChoice.entries) {
            val row = read(theme, accent)
            for ((layer, under) in row.layers) {
                val fill = row.fill.compositeOver(under)
                for ((role, words, need) in listOf(Triple("main words", row.main, 7.0), Triple("secondary words", row.secondary, 7.0), Triple("muted words", row.muted, 4.5))) {
                    val ratio = contrast(words.compositeOver(fill), fill)
                    checked++
                    if (ratio < need) low += "${theme.label}, ${accent.label}, on the $layer: $role ${"%.2f".format(ratio)} < $need"
                }
            }
        }
        assertTrue("checked $checked", checked >= ThemeChoice.entries.size * AccentChoice.entries.size * 9)
        assertTrue("selected rows under their minimum:\n" + low.joinToString("\n"), low.isEmpty())
    }
}
