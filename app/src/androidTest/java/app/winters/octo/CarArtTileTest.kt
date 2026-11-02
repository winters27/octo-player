package app.winters.octo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.playback.ArtworkProvider
import app.winters.octo.playback.CarGlyph
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

// The tiles a car is sent where there is no picture, drawn with the app's
// own glyphs, read back as a car reads them.
@RunWith(AndroidJUnit4::class)
class CarArtTileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(uri: Uri): Bitmap =
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }

    private fun brightness(pixel: Int) = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3

    // The brightest pixel in the middle fifth of the tile, where the glyph is.
    private fun glyphLight(tile: Bitmap): Int {
        val from = tile.width * 2 / 5
        val to = tile.width * 3 / 5
        return (from until to).maxOf { y -> (from until to).maxOf { x -> brightness(tile.getPixel(x, y)) } }
    }

    private fun isTileColor(pixel: Int) = abs((pixel shr 16 and 0xFF) - 0x34) <= 6 && abs((pixel shr 8 and 0xFF) - 0x36) <= 6 && abs((pixel and 0xFF) - 0x3B) <= 6

    @Test
    fun everyGlyphTileIsTheTileWithItsGlyphInTheMiddle() {
        CarGlyph.entries.forEach { glyph ->
            val tile = picture(ArtworkProvider.carGlyphUri(glyph))
            assertTrue("$glyph corner", isTileColor(tile.getPixel(4, 4)))
            assertTrue("$glyph glyph ${glyphLight(tile)}", glyphLight(tile) > 0x34 + 0x50)
        }
    }

    @Test
    fun anArtistWithNoPictureHasTheArtistGlyphAndTheMark() {
        val tile = picture(ArtworkProvider.carMarkOnlyUri(round = true))
        assertTrue("corner", isTileColor(tile.getPixel(4, 4)))
        assertTrue("glyph ${glyphLight(tile)}", glyphLight(tile) > 0x34 + 0x50)
        // The mark's plus, in the bottom right quarter: lighter than the tile.
        val side = tile.width
        val corner = (side * 3 / 4 until side).maxOf { y -> (side * 3 / 4 until side).maxOf { x -> brightness(tile.getPixel(x, y)) } }
        assertTrue("mark $corner", corner > 0x34 + 0x60)
    }
}
