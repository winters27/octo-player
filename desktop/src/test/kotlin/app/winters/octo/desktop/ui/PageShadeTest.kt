package app.winters.octo.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.design.Ambience
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.overlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The page's shade: none for a dark cover, just enough for a bright one,
// following the glow down the page and gone where the glow is.
class PageShadeTest {
    private val base = OctoColors.Background.toArgb()

    // The quietest words' contrast over `under` with `shade` of black on it.
    private fun quiet(under: Int, shade: Float): Double {
        val back = overlay(0xFF000000.toInt(), shade, under)
        return contrastRatio(OctoColors.TextMuted.compositeOver(Color(back)).toArgb(), back)
    }

    @Test
    fun aDarkCoverGetsNoShade() {
        assertEquals(PageShade.None, glowShade(0xFF101830.toInt(), glowOpacity(1f)))
        assertEquals(0f, shadeFor(base), 0f)
    }

    @Test
    fun aBrightGlowIsShadedJustEnoughAllTheWayDown() {
        for ((name, peak) in listOf("yellow" to 0xFFFFE000.toInt(), "white" to 0xFFFFFFFF.toInt(), "green" to 0xFF00FF40.toInt())) {
            val glow = glowOpacity(1f)
            val shade = glowShade(peak, glow)
            assertTrue("$name is shaded at the top: ${shade.most}", shade.most > 0f)
            // Gone at the glow's foot, and never darker lower down.
            assertEquals(0f, shade.stops.last().second, 0f)
            assertEquals(Ambience.Height, shade.stops.last().first)
            shade.stops.zipWithNext().forEach { (a, b) -> assertTrue("$name fades as it goes down", b.second <= a.second) }
            // At each depth the words read over the glow there, and a
            // little less shade would not do.
            shade.stops.forEachIndexed { i, (_, s) ->
                val under = overlay(peak, glow * (1f - i / (shade.stops.size - 1f)), base)
                assertTrue("$name at step $i: ${quiet(under, s)}", quiet(under, s) >= 4.5)
                if (s > 0.05f) assertTrue("$name at step $i is no darker than it needs", quiet(under, s - 0.05f) < ShadeContrast)
            }
        }
    }

    @Test
    fun anEvenWashIsShadedEvenly() {
        val shade = evenShade(0xFFFFE000.toInt(), 0.41f)
        assertTrue(shade.most > 0f)
        assertEquals(1, shade.stops.map { it.second }.distinct().size)
        assertEquals(PageShade.None, evenShade(0xFF101830.toInt(), 0.41f))
    }
}
