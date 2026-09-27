package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WashInkTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun theWordsFlipOnlyOverALightWash() {
        val white = rgb(255, 255, 255)
        val pale = rgb(0xF2, 0xEA, 0xD8)
        val mid = rgb(128, 128, 128)
        val dark = rgb(32, 32, 32)
        val yellow = rgb(255, 255, 0)

        // At the defaults the cap pulls white and pale covers down, so the
        // words stay white.
        val defaults = WashTuning()
        assertEquals(Color.White, washContent(white, defaults))
        assertEquals(Color.White, washContent(pale, defaults))
        assertEquals(Color.White, washContent(mid, defaults))
        assertEquals(Color.White, washContent(dark, defaults))
        // A vivid yellow stays bright enough under it: dark words.
        assertTrue(washIsLight(prepareColor(yellow, defaults)))
        assertEquals(DarkInk, washContent(yellow, defaults))

        // With no cap, white and pale covers flip to dark words; mid and
        // dark ones do not.
        val uncapped = WashTuning(brightnessCap = 1f)
        assertEquals(DarkInk, washContent(white, uncapped))
        assertEquals(DarkInk, washContent(pale, uncapped))
        assertEquals(Color.White, washContent(mid, uncapped))
        assertEquals(Color.White, washContent(dark, uncapped))

        // No artwork colour: white.
        assertEquals(Color.White, washContent(null, defaults))
    }
}
