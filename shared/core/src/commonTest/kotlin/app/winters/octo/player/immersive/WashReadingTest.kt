package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WashReadingTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val page = rgb(0x0C, 0x0C, 0x0D)
    private val accent = rgb(0x97, 0xB1, 0xB9)
    private val white = rgb(255, 255, 255)

    // A cover of one colour, prepared as the wash prepares covers.
    private fun prepared(argb: Int, tuning: WashTuning = WashTuning()) = IntArray(64 * 64) { prepareColor(argb, tuning) }

    @Test
    fun thePeakIsTheBrightestFewAsTheFinalPassLeavesThem() {
        // Mostly black, with 5% of it bright green: the green is the peak,
        // dimmed by the final pass.
        val pixels = IntArray(1000) { if (it % 20 == 0) rgb(0, 200, 0) else rgb(0, 0, 0) }
        val peak = washPeak(pixels, step = 1)
        assertEquals((200 * FinalGain).toInt().toFloat(), (peak shr 8 and 0xFF).toFloat(), 1f)
        // A single bright pixel in a thousand is blurred away: no peak.
        val speck = IntArray(1000) { if (it == 500) rgb(255, 255, 255) else rgb(10, 10, 10) }
        assertEquals(rgb(9, 9, 9), washPeak(speck, step = 1))
    }

    @Test
    fun aDarkCoverShowsAsMuchAsAsked() {
        val navy = washPeak(prepared(rgb(0x10, 0x18, 0x30)))
        assertEquals(0.9f, readableAlpha(navy, page, accent, 4.5, 0.9f), 1e-6f)
    }

    @Test
    fun aBrightCoverShowsFainterSoTheWordsStillRead() {
        for (cover in listOf(rgb(255, 224, 0), rgb(0, 255, 0), rgb(0xF4, 0xEE, 0xDC), rgb(255, 255, 255), rgb(0, 255, 255))) {
            val peak = washPeak(prepared(cover))
            val alpha = readableAlpha(peak, page, accent, 4.5, 0.9f)
            val behind = overlay(peak, alpha, page)
            assertTrue("accent on ${Integer.toHexString(cover)} at $alpha", contrastRatio(accent, behind) >= 4.5)
            // White titles read easily.
            assertTrue(contrastRatio(white, behind) >= 7.0)
            // And it is the most that reads: a little more would not.
            if (alpha < 0.9f) assertFalse(contrastRatio(accent, overlay(peak, alpha + 0.02f, page)) >= 4.5)
        }
        // Yellow and green, the brightest covers once prepared, still show.
        assertTrue(readableAlpha(washPeak(prepared(rgb(255, 224, 0))), page, accent, 4.5, 0.9f) > 0.25f)
    }

    @Test
    fun overlayMixesChannelByChannel() {
        assertEquals(page, overlay(white, 0f, page))
        assertEquals(white, overlay(white, 1f, page))
        assertEquals(rgb(134, 134, 134), overlay(white, 0.5f, page))
    }

    @Test
    fun theWordsGoDarkOnlyOverALightWash() {
        assertTrue(washDarkWords(rgb(255, 255, 0), WashTuning()))
        assertFalse(washDarkWords(rgb(32, 32, 32), WashTuning()))
        assertFalse(washDarkWords(null, WashTuning()))
    }

    @Test
    fun theChoicesAreThePhones() {
        assertEquals(listOf(30, 60, 90, 120), FpsChoices)
        assertEquals(20..100, BrightnessCapRange)
        assertEquals(5..100, SpeedRange)
    }
}
