package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WashColourTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(argb: Int) = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

    @Test
    fun contrastPushesChannelsAwayFromTheMiddle() {
        // (c - 0.5) x 1.3 + 0.5, in 0 to 255.
        val only = washColorMatrix(WashTuning(contrast = 1.3f, saturation = 1f, brightnessCap = 1f))
        assertEquals(listOf(222, 92, 27), channels(applyColorMatrix(rgb(200, 100, 50), only)))
        // The middle stays put, and the ends clip.
        assertEquals(listOf(128, 255, 0), channels(applyColorMatrix(rgb(128, 250, 10), only)))
    }

    @Test
    fun saturationMixesAwayFromGrey() {
        val only = washColorMatrix(WashTuning(contrast = 1f, saturation = 1.8f, brightnessCap = 1f))
        // Grey is 0.299 R + 0.587 G + 0.114 B = 124.2; each channel moves 1.8
        // times as far from it.
        assertEquals(listOf(255, 81, 0), channels(applyColorMatrix(rgb(200, 100, 50), only)))
        // A grey has nothing to saturate.
        assertEquals(listOf(90, 90, 90), channels(applyColorMatrix(rgb(90, 90, 90), only)))
        // At 0 everything is its grey.
        val none = washColorMatrix(WashTuning(contrast = 1f, saturation = 0f, brightnessCap = 1f))
        assertEquals(listOf(124, 124, 124), channels(applyColorMatrix(rgb(200, 100, 50), none)))
    }

    @Test
    fun theOneMatrixIsContrastThenSaturation() {
        val tuning = WashTuning()
        val matrix = washColorMatrix(tuning)
        val (r, g, b) = listOf(150f, 110f, 90f)
        fun contrast(c: Float) = (c / 255f - 0.5f) * 1.3f * 255f + 127.5f
        val cr = contrast(r)
        val cg = contrast(g)
        val cb = contrast(b)
        val grey = 0.299f * cr + 0.587f * cg + 0.114f * cb
        val expected = listOf(cr, cg, cb).map { grey + 1.8f * (it - grey) }
        val actual = (0..2).map { row -> matrix[row * 5] * r + matrix[row * 5 + 1] * g + matrix[row * 5 + 2] * b + matrix[row * 5 + 4] }
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.01f) }
        // Alpha passes through.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), matrix.drop(15))
    }

    @Test
    fun theBrightnessCapDarkensByHowFarOverItIs() {
        // Mean 0.784 over a 0.5 cap: darkened by 0.569 of itself.
        assertEquals(listOf(86, 86, 86), channels(capBrightness(rgb(200, 200, 200), 0.5f)))
        // Under the cap, untouched.
        assertEquals(rgb(100, 100, 100), capBrightness(rgb(100, 100, 100), 0.5f))
        assertEquals(rgb(250, 10, 10), capBrightness(rgb(250, 10, 10), 0.5f))
        // Full white is twice the cap over, so it goes all the way down.
        assertEquals(listOf(0, 0, 0), channels(capBrightness(rgb(255, 255, 255), 0.5f)))
        // A cap of 100% never touches anything.
        assertEquals(rgb(255, 255, 255), capBrightness(rgb(255, 255, 255), 1f))
    }

    @Test
    fun theCapRunsOverAWholePicture() {
        val pixels = intArrayOf(rgb(200, 200, 200), rgb(100, 100, 100))
        capBrightness(pixels, 0.5f)
        assertEquals(listOf(86, 86, 86), channels(pixels[0]))
        assertEquals(rgb(100, 100, 100), pixels[1])
    }

    @Test
    fun capKeepsAlpha() {
        val half = (0x80 shl 24) or (200 shl 16) or (200 shl 8) or 200
        assertEquals(0x80, capBrightness(half, 0.5f) ushr 24)
    }

    @Test
    fun contrastAgainstWhite() {
        assertEquals(21.0, contrastRatio(rgb(0, 0, 0), rgb(255, 255, 255)), 0.01)
        assertEquals(1.0, contrastRatio(rgb(255, 255, 255), rgb(255, 255, 255)), 0.001)
        assertEquals(1.0, relativeLuminance(rgb(255, 255, 255)), 0.0001)
        assertEquals(0.2159, relativeLuminance(rgb(128, 128, 128)), 0.001)
    }

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

    @Test
    fun theLightTestNeedsBothHalves() {
        // Just past 3:1 against white: not light.
        assertFalse(washIsLight(rgb(140, 140, 140)))
        assertTrue(washIsLight(rgb(200, 200, 200)))
    }
}
