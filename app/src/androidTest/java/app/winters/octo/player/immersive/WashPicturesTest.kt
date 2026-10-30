package app.winters.octo.player.immersive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.ui.common.GpuPictureStream
import app.winters.octo.ui.common.PictureStreamImages
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The full player's moving background on a real phone's renderer: its
// pictures are made, arrive and are shown.
@RunWith(AndroidJUnit4::class)
class WashPicturesTest {
    @get:Rule
    val rule = createComposeRule()

    // A 512 square with color across it, as the wash's covers are.
    private fun cover(): WashCover {
        val square = Bitmap.createBitmap(WashSize, WashSize, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, WashSize.toFloat(), WashSize.toFloat(), Color.MAGENTA, Color.GREEN, Shader.TileMode.CLAMP)
        }
        Canvas(square).drawPaint(paint)
        return WashCover("test", square, null, WashRange(0xFF202020.toInt(), 0xFFE0E0E0.toInt()))
    }

    // Opening the player: the wash draws its first picture and the glass
    // shows it, on every phone that draws the wash (Android 13 and newer).
    @Test
    fun theWashShowsItsFirstPicture() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val glass = WashGlass()
        val cover = cover()
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                ImmersiveWash(cover, bpm = 120f, speed = 1f, moving = true, dolly = { 1f }, glass = glass)
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) { glass.ready }
    }

    // A long run of pictures keeps the shaders of only a few, whether the
    // phone hands the same few bitmaps round or a new one with each picture.
    @Test
    fun manyPicturesKeepFewShaders() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        lateinit var stream: GpuPictureStream
        lateinit var renderer: WashRenderer
        instrumentation.runOnMainSync {
            stream = GpuPictureStream(64, 64)
            renderer = WashRenderer()
        }
        try {
            repeat(PictureRun) { i ->
                picture(stream, if (i % 2 == 0) Color.RED else Color.BLUE)
                instrumentation.runOnMainSync { renderer.pictureArrived(stream) }
            }
            var kept = 0
            instrumentation.runOnMainSync { kept = renderer.pictureShaderCount }
            assertTrue("$kept shaders kept after $PictureRun pictures", kept <= PictureStreamImages)
        } finally {
            instrumentation.runOnMainSync {
                renderer.release()
                stream.close()
            }
        }
    }

    // Where the phone names each image's buffer, the stream hands its few
    // bitmaps round rather than a new one per picture.
    @Test
    fun newerPhonesHandTheSameBitmapsRound() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        lateinit var stream: GpuPictureStream
        instrumentation.runOnMainSync { stream = GpuPictureStream(64, 64) }
        val seen = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        try {
            repeat(PictureRun) { i ->
                picture(stream, if (i % 2 == 0) Color.RED else Color.BLUE)
                instrumentation.runOnMainSync { stream.latest?.let(seen::add) }
            }
            assertTrue("${seen.size} bitmaps for $PictureRun pictures", seen.size <= PictureStreamImages)
        } finally {
            instrumentation.runOnMainSync { stream.close() }
        }
    }

    // Asks the stream for a picture of one color, as the wash does: again a
    // moment later while it declines (a frame still on its way), until one
    // arrives.
    private fun picture(stream: GpuPictureStream, color: Int) {
        val arrived = CountDownLatch(1)
        instrumentation.runOnMainSync { stream.onPicture = { arrived.countDown() } }
        repeat(AskTries) {
            instrumentation.runOnMainSync { stream.draw { canvas -> canvas.drawColor(color) } }
            if (arrived.await(AskWaitMs, TimeUnit.MILLISECONDS)) return
        }
        throw AssertionError("no picture arrived after $AskTries asks")
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val PictureRun = 40
        const val AskTries = 20
        const val AskWaitMs = 300L
    }
}
