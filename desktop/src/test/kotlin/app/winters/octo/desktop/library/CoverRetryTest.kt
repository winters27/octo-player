package app.winters.octo.desktop.library

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.FakeServer
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

// The playing song's cover is asked for again when it did not come (Octo
// may still be looking it up), and says when it comes in; covers in lists
// are asked for once.
class CoverRetryTest {
    @get:Rule val folder = TemporaryFolder()

    private val red = Surface.makeRasterN32Premul(32, 32).run {
        canvas.clear(0xFFD02020.toInt())
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @After
    fun reset() = SingletonImageLoader.reset()

    // Draws the cover for a while against a server whose first answer is no
    // picture: how often it was asked for, and whether its coming in was told.
    private fun draw(retry: Boolean, id: String, ms: Long): Pair<Int, Boolean> {
        val asked = AtomicInteger()
        FakeServer().use { server ->
            server.fileBy("getCoverArt") { if (asked.incrementAndGet() == 1) ByteArray(0) else red }
            SingletonImageLoader.setUnsafe(coverLoader(PlatformContext.INSTANCE, OkHttpClient(), folder.newFolder()))
            var arrived = false
            val watch = CoroutineScope(Dispatchers.Default).launch {
                withTimeoutOrNull(ms + 2_000) { CoverArrivals.arrived.first { it == id } }?.let { arrived = true }
            }
            val client = server.client()
            val scene = ImageComposeScene(100, 100, Density(1f)) {
                CompositionLocalProvider(LocalCovers provides client) { Cover(id, Modifier.size(80.dp), retry = retry) }
            }
            try {
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    scene.render()
                    Thread.sleep(30)
                }
            } finally {
                scene.close()
            }
            if (retry) runBlocking { watch.join() } else watch.cancel()
            return asked.get() to arrived
        }
    }

    @Test
    fun thePlayingSongsCoverIsAskedForAgain() {
        val (asked, arrived) = draw(retry = true, id = "al-retry", ms = COVER_RETRY_MS.first() + 1_500)
        assertEquals(2, asked)
        assertTrue("the cover coming in is told", arrived)
    }

    @Test
    fun aCoverInAListIsAskedForOnce() {
        val (asked, _) = draw(retry = false, id = "al-once", ms = COVER_RETRY_MS.first() + 1_500)
        assertEquals(1, asked)
    }
}
