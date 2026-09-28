package app.winters.octo.desktop.player.wash

import app.winters.octo.desktop.ui.playerInk
import app.winters.octo.player.immersive.WashMotion
import app.winters.octo.player.immersive.WashSize
import app.winters.octo.player.immersive.WashTuning
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The full player's background on the desktop: the phone's shader text runs
// as Skia runtime effects, the cover is prepared with the shared maths, and
// the words turn dark only over a light wash.
class WashTest {
    private fun solid(argb: Int, width: Int = 64, height: Int = 48): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(argb)
        return surface.makeImageSnapshot()
    }

    private fun Image.pixel(x: Int, y: Int): Int {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        readPixels(bitmap)
        return bitmap.getColor(x, y)
    }

    @Test
    fun theWashDrawsTheCoversColours() {
        val cover = WashCovers.prepared("red", solid(0xFFD02020.toInt()), WashTuning())
        val renderer = WashRenderer()
        renderer.setCovers(cover.square, cover.square)
        val surface = Surface.makeRasterN32Premul(160, 100)
        val canvas: Canvas = surface.canvas
        canvas.clear(Color.BLACK)
        val motion = WashMotion().apply { step(1f, 120f, 0.016f) }
        assertTrue(renderer.draw(canvas, 160f, 100f, motion, 1f, 1f, 1f))
        val middle = surface.makeImageSnapshot().pixel(80, 50)
        val r = Color.getR(middle)
        val g = Color.getG(middle)
        assertTrue("red shows through: ${Integer.toHexString(middle)}", r > 60 && r > g * 2)
    }

    // The square is worked out at its own size whatever the window's: on
    // the processor (as here) a large window costs little more than a small
    // one. On the GPU, where the app draws, both are quick.
    @Test
    fun theSquaresCostDoesNotGrowWithTheWindow() {
        val cover = WashCovers.prepared("red", solid(0xFFD02020.toInt()), WashTuning())
        val renderer = WashRenderer()
        renderer.setCovers(cover.square, cover.square)
        val motion = WashMotion()
        fun msPerFrame(width: Int, height: Int): Double {
            val surface = Surface.makeRasterN32Premul(width, height)
            repeat(2) { renderer.draw(surface.canvas, width.toFloat(), height.toFloat(), motion, 1f, 1f, 1f) }
            val started = System.nanoTime()
            repeat(4) {
                motion.step(1f, 120f, 0.016f)
                renderer.draw(surface.canvas, width.toFloat(), height.toFloat(), motion, 1f, 1f, 1f)
            }
            return (System.nanoTime() - started) / 4 / 1e6
        }
        val small = msPerFrame(160, 100)
        val large = msPerFrame(1440, 900)
        println("wash on the processor: %.1f ms at 160x100, %.1f ms at 1440x900".format(small, large))
        assertTrue("small $small ms, large $large ms", large < small * 6)
    }

    @Test
    fun nothingIsDrawnBeforeACoverArrives() {
        val surface = Surface.makeRasterN32Premul(40, 40)
        assertFalse(WashRenderer().draw(surface.canvas, 40f, 40f, WashMotion(), 1f, 1f, 1f))
    }

    @Test
    fun aBrightCoverIsCappedSoItNeverGlares() {
        // A light grey, 60% bright, under a cap of half: darkened by
        // (0.6 - 0.5) / 0.5 of itself, to 48%, as the shared maths says.
        val cover = WashCovers.prepared("grey", solid(0xFF999999.toInt(), 300, 200), WashTuning(contrast = 1f, saturation = 1f, brightnessCap = 0.5f))
        assertTrue(cover.square.width == WashSize && cover.square.height == WashSize)
        val p = cover.square.pixel(100, 100)
        val mean = (Color.getR(p) + Color.getG(p) + Color.getB(p)) / 3
        assertTrue("capped: $mean", mean in 118..126)
        assertFalse("white words over a capped wash", cover.playerInk().dark)
    }

    @Test
    fun wordsTurnDarkOverALightWash() {
        val light = WashCovers.prepared("pale", solid(0xFFF4EEDC.toInt()), WashTuning(contrast = 1f, saturation = 1f, brightnessCap = 1f))
        assertTrue(light.playerInk().dark)
        val dark = WashCovers.prepared("navy", solid(0xFF101830.toInt()), WashTuning(contrast = 1f, saturation = 1f, brightnessCap = 1f))
        assertFalse(dark.playerInk().dark)
    }

    @Test
    fun songsWithoutACoverGetABlend() {
        val cover = WashCovers.prepared("none", null, WashTuning())
        val corner = cover.square.pixel(0, 0)
        val far = cover.square.pixel(WashSize - 1, WashSize - 1)
        assertTrue(corner != far)
        assertFalse(cover.playerInk().dark)
    }
}
