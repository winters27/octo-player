package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.player.PlayerColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The player's words take their colour from what the background draws,
// and the background dims as far as they need, in every mode.
class WashInkTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val base = Color(0xFF0C0C0D)

    // A prepared 512 square of `ground` with a disc of `disc`, as the wash
    // prepares a cover.
    private fun coverRange(ground: Int, disc: Int, tuning: WashTuning = WashTuning()): WashRange {
        val g = prepareColor(ground, tuning)
        val d = prepareColor(disc, tuning)
        return washRange(IntArray(512 * 512) { i -> val x = i % 512 - 300; val y = i / 512 - 280; if (x * x + y * y < 150 * 150) d else g }, 512)
    }

    // The worst contrast between the words and what is drawn under them.
    private fun worst(colors: PlayerColors, range: WashRange): Double {
        val words = colors.content.toArgb()
        return minOf(
            contrastRatio(words, overlay(range.low, colors.show, base.toArgb())),
            contrastRatio(words, overlay(range.high, colors.show, base.toArgb())),
        )
    }

    @Test
    fun aTealCoverWithADarkShapeKeepsWhiteWordsAndDims() {
        // Its main colour, teal, is light, yet the wash is dark around the
        // navy disc: dark words would vanish there.
        val teal = PlayerColors.Quiet.copy(dominant = Color(0xFF3AA6A0), base = base)
        val range = drawnRange(BackgroundMode.Default, teal, BackgroundPrefs(), coverRange(rgb(0x3A, 0xA6, 0xA0), rgb(0x1B, 0x2A, 0x4A)), live = true)!!
        val over = teal.over(range)
        assertEquals(Color.White, over.content)
        assertTrue(over.show < 1f)
        assertTrue(worst(over, range) >= 4.5)
    }

    @Test
    fun everyModeReadsOverEveryCover() {
        val covers = listOf(
            rgb(0x10, 0x18, 0x30) to rgb(0x8A, 0x1C, 0x3C),
            rgb(0x3A, 0xA6, 0xA0) to rgb(0x1B, 0x2A, 0x4A),
            rgb(0xFF, 0xE0, 0x00) to rgb(0xFF, 0x8A, 0x00),
            rgb(0xFF, 0xFF, 0xFF) to rgb(0xF2, 0xEA, 0xD8),
            rgb(0x00, 0xFF, 0x40) to rgb(0xB0, 0xFF, 0x00),
        )
        for (prefs in listOf(BackgroundPrefs(), BackgroundPrefs(brightnessCap = 100, saturation = 100, contrast = 1f))) {
            for ((ground, disc) in covers) {
                val colors = PlayerColors(
                    mesh = listOf(Color(ground), Color(disc), Color(0xFF202020), Color(0xFF0A0B0F)),
                    base = base,
                    dominant = Color(ground),
                )
                for (mode in BackgroundMode.entries) for (live in listOf(true, false)) {
                    val range = drawnRange(mode, colors, prefs, coverRange(ground, disc, prefs.tuning), live)!!
                    val over = colors.over(range)
                    assertTrue("$mode over ${Integer.toHexString(ground)}: ${over.content} at ${over.show}", worst(over, range) >= 4.5)
                }
            }
        }
    }

    @Test
    fun wordsGoDarkOnlyOverABackgroundLightAllOver() {
        val uncapped = BackgroundPrefs(brightnessCap = 100, saturation = 100, contrast = 1f)
        val pale = PlayerColors.Quiet.copy(dominant = Color(0xFFF4EEDC), base = base)
        val light = pale.over(drawnRange(BackgroundMode.Colour, pale, uncapped, null, live = true))
        assertEquals(DarkInk, light.content)
        assertEquals(1f, light.show)
        // The classic mesh always has a near-black pool: white words.
        assertEquals(Color.White, pale.over(drawnRange(BackgroundMode.Classic, pale, uncapped, null, live = true)).content)
    }

    @Test
    fun untilTheCoverIsReadyTheWordsAreWhite() {
        assertNull(drawnRange(BackgroundMode.Default, PlayerColors.Quiet, BackgroundPrefs(), null, live = true))
        val waiting = PlayerColors.Quiet.over(null)
        assertEquals(Color.White, waiting.content)
        assertEquals(1f, waiting.show)
    }
}
