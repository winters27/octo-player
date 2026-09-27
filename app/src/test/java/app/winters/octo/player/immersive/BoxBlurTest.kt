package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun anEvenPictureStaysEven() {
        val pixels = IntArray(16 * 16) { grey(90) }
        boxBlur(pixels, 16, 16, 3)
        assertTrue(pixels.all { it == grey(90) })
    }

    @Test
    fun aBrightSpotSpreadsAndKeepsItsMiddle() {
        val pixels = IntArray(9 * 9) { grey(0) }
        pixels[4 * 9 + 4] = grey(255)
        boxBlur(pixels, 9, 9, 1)
        // A 3 x 3 box: the spot is shared out over nine pixels.
        val centre = pixels[4 * 9 + 4] and 0xFF
        assertEquals(255 / 3 / 3, centre)
        assertEquals(centre, pixels[3 * 9 + 3] and 0xFF)
        assertEquals(0, pixels[2 * 9 + 2] and 0xFF)
        // Alpha is kept.
        assertEquals(0xFF, pixels[0] ushr 24)
    }
}
