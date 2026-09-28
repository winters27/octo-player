package app.winters.octo.desktop.library

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverDecodeTest {
    @get:Rule val folder = TemporaryFolder()

    // A 150 pixel cover of one-pixel black and white squares: shrunk well,
    // it is an even grey; shrunk by the nearest pixel, it stays black and white.
    private val checks: ByteArray = Surface.makeRasterN32Premul(150, 150).run {
        canvas.clear(Color.BLACK)
        val white = org.jetbrains.skia.Paint().apply { color = Color.WHITE }
        for (y in 0 until 150) for (x in 0 until 150) if ((x + y) % 2 == 0) canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(x.toFloat(), y.toFloat(), 1f, 1f), white)
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun shrink(loader: ImageLoader, px: Int): org.jetbrains.skia.Bitmap = runBlocking {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(checks).size(px, px).scale(Scale.FILL).build()
        val result = loader.execute(request)
        assertTrue("decoded: $result", result is SuccessResult)
        (result as SuccessResult).image.toBitmap()
    }

    // How far the shrunk cover's pixels stray from an even grey, at most.
    private fun worstStray(bitmap: org.jetbrains.skia.Bitmap): Int {
        var worst = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            worst = maxOf(worst, kotlin.math.abs(Color.getG(bitmap.getColor(x, y)) - 128))
        }
        return worst
    }

    @Test
    fun aSmallCoverIsShrunkSmoothlyToTheSizeItIsDrawn() {
        val loader = coverLoader(PlatformContext.INSTANCE, OkHttpClient(), folder.root)
        val shrunk = shrink(loader, 22)
        assertEquals(22, shrunk.width)
        assertEquals(22, shrunk.height)
        assertTrue("every pixel near an even grey, worst ${worstStray(shrunk)}", worstStray(shrunk) < 40)
    }

    @Test
    fun theStockDecoderWouldLeaveItJagged() {
        // Why Octo decodes its own: the plain loader keeps the nearest pixel.
        val plain = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        assertTrue(worstStray(shrink(plain, 22)) > 100)
    }

    @Test
    fun aCoverIsNeverEnlarged() {
        val loader = coverLoader(PlatformContext.INSTANCE, OkHttpClient(), folder.root)
        assertEquals(150, shrink(loader, 400).width)
    }
}
