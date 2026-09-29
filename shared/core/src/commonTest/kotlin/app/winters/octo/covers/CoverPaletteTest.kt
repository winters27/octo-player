package app.winters.octo.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun theMainColoursOfAPictureComeFirst() {
        // Three quarters navy, a quarter orange.
        val pixels = IntArray(400) { if (it % 4 == 0) rgb(230, 110, 40) else rgb(20, 30, 80) }
        val swatches = coverSwatches(pixels, step = 1)
        assertEquals(2, swatches.size)
        assertTrue(colourDistance(swatches[0].argb, rgb(20, 30, 80)) < 0.02)
        assertEquals(0.75f, swatches[0].share, 0.01f)
        assertEquals(0.25f, swatches[1].share, 0.01f)
    }

    @Test
    fun eachQuarterOfAMosaicIsOneCover() {
        val colours = listOf(rgb(200, 30, 30), rgb(30, 200, 30), rgb(30, 30, 200), rgb(200, 200, 30))
        val pixels = IntArray(20 * 20) { i ->
            val x = i % 20
            val y = i / 20
            colours[(if (x < 10) 0 else 1) + (if (y < 10) 0 else 2)]
        }
        val quarters = quarterSwatches(pixels, 20)
        assertEquals(4, quarters.size)
        quarters.forEachIndexed { q, swatches -> assertTrue(colourDistance(swatches.first().argb, colours[q]) < 0.02) }
    }

    @Test
    fun theColoursComeFromTheMusic() {
        val red = listOf(Swatch(rgb(200, 30, 40), 0.8f))
        val blue = listOf(Swatch(rgb(30, 60, 200), 0.6f))
        val palette = coverPalette(listOf(red, blue), "pl-1")
        assertTrue(palette.fromMusic)
        // The strongest cover's colour, its hue and lightness.
        assertTrue(hueDistance(palette.hue.toDouble(), toLch(rgb(200, 30, 40)).h) < 2)
        assertEquals(toLch(rgb(200, 30, 40)).l, palette.lightness, 0.002)
    }

    @Test
    fun aVividDetailBeatsALargeGreyButNotALargeColour() {
        val grey = Swatch(rgb(120, 120, 120), 0.9f)
        val speck = Swatch(rgb(0, 160, 255), 0.05f)
        assertTrue(hueDistance(coverPalette(listOf(listOf(grey, speck)), "x").hue.toDouble(), toLch(rgb(0, 160, 255)).h) < 2)
        val green = Swatch(rgb(40, 140, 60), 0.9f)
        assertTrue(hueDistance(coverPalette(listOf(listOf(green, speck)), "x").hue.toDouble(), toLch(rgb(40, 140, 60)).h) < 2)
    }

    @Test
    fun noCoversOrGreyOnesGiveTheListsOwnPalette() {
        val none = coverPalette(emptyList(), "pl-7")
        assertFalse(none.fromMusic)
        assertEquals(seededPalette("pl-7"), none)
        val grey = coverPalette(listOf(listOf(Swatch(rgb(128, 128, 128), 1f)), listOf(Swatch(rgb(10, 10, 10), 1f))), "pl-7")
        assertEquals(seededPalette("pl-7"), grey)
        // Always the same for the same list, different for another.
        assertEquals(seededPalette("pl-7"), seededPalette("pl-7"))
        assertNotEquals(seededPalette("pl-7").key, seededPalette("pl-8").key)
    }

    @Test
    fun aPaletteReadsBackFromItsKey() {
        val palette = coverPalette(listOf(listOf(Swatch(rgb(200, 90, 30), 1f))), "a")
        assertEquals(palette, CoverPalette.fromKey(palette.key))
        assertNull(CoverPalette.fromKey("nonsense"))
    }

    @Test
    fun theHashIsTheSameEverywhere() {
        // FNV-1a over UTF-8, shifted: fixed values, so the server's matches.
        assertEquals(0x5280d386db28089bL, coverHash("pl-1"))
    }

    @Test
    fun colourSpaceRoundTrips() {
        for (argb in listOf(rgb(255, 0, 0), rgb(12, 200, 90), rgb(30, 30, 30), rgb(250, 240, 220))) {
            val lch = toLch(argb)
            assertTrue(colourDistance(argb, lch.toArgb()) < 0.005)
        }
        // A chroma no screen shows is brought down, the lightness kept.
        val wild = lchToArgb(0.5, 0.5, 140.0)
        assertEquals(0.5, toLch(wild).l, 0.01)
    }
}
