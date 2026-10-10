package app.winters.octo.desktop.player.wash

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.CoverArrivals
import app.winters.octo.player.immersive.WashTuning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Brandon: songs played without their covers, and a cover that came in
// later never reached the background. A cover the server did not send was
// kept as the blend for good; now it is asked for again, and the
// background takes the real one when it comes.
class WashFollowTest {
    private val red = Surface.makeRasterN32Premul(32, 32).run {
        canvas.clear(0xFFD02020.toInt())
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    // A server that sends nothing for a cover `misses` times, then the cover.
    private fun serving(misses: Int, asked: AtomicInteger) = FakeServer().also { server ->
        server.fileBy("getCoverArt") { if (asked.incrementAndGet() <= misses) ByteArray(0) else red }
    }

    @Test
    fun aCoverThatDidNotComeIsAskedForAgainAndTheRealOneFollows() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 2, asked).use { server ->
            val covers = WashCovers(OkHttpClient(), retryMs = listOf(30L, 30L, 30L), holdMs = 0)
            val shown = withTimeout(5_000) { covers.follow(server.client(), "al-1", WashTuning()).take(2).toList() }
            assertEquals(3, asked.get())
            assertNotEquals("the blend first, then the cover", shown[0].main, shown[1].main)
            // The real one is kept: no more asking.
            covers.prepare(server.client(), "al-1", WashTuning())
            assertEquals(3, asked.get())
        }
    }

    @Test
    fun aMissIsNotKept() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 1, asked).use { server ->
            val covers = WashCovers(OkHttpClient(), retryMs = emptyList(), holdMs = 0)
            val blend = covers.prepare(server.client(), "al-1", WashTuning())
            val real = covers.prepare(server.client(), "al-1", WashTuning())
            assertEquals(2, asked.get())
            assertNotEquals(blend.main, real.main)
        }
    }

    @Test
    fun theCoverComingInOnScreenIsTakenAtOnce() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 1, asked).use { server ->
            // Too long a wait to reach in this test: only the arrival can.
            val covers = WashCovers(OkHttpClient(), retryMs = listOf(60_000L), holdMs = 0)
            val shown = mutableListOf<WashCover>()
            val following = launch { covers.follow(server.client(), "al-7", WashTuning()).collect { shown += it } }
            withTimeout(5_000) { while (shown.isEmpty()) delay(10) }
            delay(100)
            CoverArrivals.arrived("al-other")
            delay(200)
            assertEquals("another cover coming in changes nothing", 1, asked.get())
            CoverArrivals.arrived("al-7")
            withTimeout(5_000) { while (shown.size < 2) delay(10) }
            assertEquals(2, asked.get())
            following.cancel()
        }
    }

    @Test
    fun aSongWithNoCoverHasTheBlendWithoutAsking() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 0, asked).use { server ->
            val covers = WashCovers(OkHttpClient(), retryMs = listOf(30L))
            val shown = withTimeout(5_000) { covers.follow(server.client(), null, WashTuning()).toList() }
            assertEquals(1, shown.size)
            assertEquals(0, asked.get())
        }
    }

    // Brandon: on radio the background went through the blend and on to the
    // cover a moment later. A cover that comes in within the hold is the
    // first thing sent; the blend never shows.
    @Test
    fun aCoverComingInWithinTheHoldIsSentWithoutTheBlend() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 1, asked).use { server ->
            val covers = WashCovers(OkHttpClient(), retryMs = listOf(60_000L), holdMs = 60_000L)
            val shown = mutableListOf<WashCover>()
            val following = launch { covers.follow(server.client(), "al-9", WashTuning()).collect { shown += it } }
            withTimeout(5_000) { while (asked.get() < 1) delay(10) }
            delay(100)
            assertEquals("nothing sent while it waits", 0, shown.size)
            CoverArrivals.arrived("al-9")
            withTimeout(5_000) { while (shown.isEmpty()) delay(10) }
            val real = covers.prepare(server.client(), "al-9", WashTuning())
            assertEquals(1, shown.size)
            assertEquals("the real cover, not the blend", real.main, shown[0].main)
            following.cancel()
        }
    }

    // With no cover coming, the blend comes once the hold is over.
    @Test
    fun theBlendStandsInAfterTheHold() = runBlocking {
        val asked = AtomicInteger()
        serving(misses = 10, asked).use { server ->
            val covers = WashCovers(OkHttpClient(), retryMs = emptyList(), holdMs = 50L)
            val shown = withTimeout(5_000) { covers.follow(server.client(), "al-3", WashTuning()).toList() }
            assertEquals(1, shown.size)
            assertEquals(2, asked.get())
        }
    }

    // A fade that starts mid-fade starts from the two covers as the wash
    // shows them: a quarter of the way from red to blue is mostly red.
    @Test
    fun coversMixAsTheWashMixesThem() {
        fun square(colour: Int) = Surface.makeRasterN32Premul(8, 8).run {
            canvas.clear(colour)
            makeImageSnapshot()
        }
        val mixed = mixCovers(square(0xFFFF0000.toInt()), square(0xFF0000FF.toInt()), 0.25f)
        val pixel = org.jetbrains.skia.Bitmap.makeFromImage(mixed).getColor(4, 4)
        assertEquals(191f, (pixel shr 16 and 0xFF).toFloat(), 2f)
        assertEquals(64f, (pixel and 0xFF).toFloat(), 2f)
    }
}
