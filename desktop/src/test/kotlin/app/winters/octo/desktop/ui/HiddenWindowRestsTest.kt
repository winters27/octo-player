package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import app.winters.octo.design.OctoColors
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// The moving colors behind the sign-in hold still while the window is
// minimized or in the tray, so a signed-out Octo out of sight draws
// nothing. The scene is drawn only when it asks for a frame, and each
// frame moves the test's own clock on by one of a 60 Hz screen's, so the
// frames the wash draws are the same on a busy machine as on an idle one:
// load changes how long the test takes, never what it sees.
class HiddenWindowRestsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 120_000)
    fun theSignInColoursRestWhileTheWindowIsHidden() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var app: AppState
        SwingUtilities.invokeAndWait { app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, restored = null) }
        var shown by mutableStateOf(false)
        // Effects on the window's thread, as in the app.
        val scene = ImageComposeScene(160, 100, Density(1f), coroutineContext = Dispatchers.Main) {
            CompositionLocalProvider(LocalWindowShown provides shown) { OctoAmbience(app, moving = true, veil = 0.2f) }
        }
        // The test's clock, one 60 Hz frame on for each frame drawn.
        var clock = 0L
        // One frame, drawn only if the scene asks for one: its pixels, or
        // null when it asks for none.
        fun frame(): Bitmap? {
            var drawn: Bitmap? = null
            SwingUtilities.invokeAndWait {
                if (scene.hasInvalidations()) {
                    clock += FrameNanos
                    scene.render(clock).use { drawn = Bitmap.makeFromImage(it) }
                }
            }
            return drawn
        }
        // The next frame the scene asks for, waiting up to `ms` for it (a
        // moving wash rests between frames, off the window's thread).
        fun nextFrame(ms: Long): Bitmap? {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                frame()?.let { return it }
                Thread.sleep(1)
            }
            return null
        }
        // The frames asked for in `ms`.
        fun askedIn(ms: Long): Int {
            var asked = 0
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                frame()?.let { asked++; it.close() } ?: Thread.sleep(1)
            }
            return asked
        }
        val plain = OctoColors.Background.toArgb()
        fun Bitmap.colored(): Boolean = (0 until 100 step 10).any { y -> (0 until 160 step 10).any { x -> getColor(x, y) != plain } }

        // The colors arrive and fade in first. Hidden, the frames that
        // takes are the last: once it has asked for none in a second, it
        // has stopped (a wash still moving asks again within a tenth).
        var arrived = false
        var rested = false
        val settleBy = System.currentTimeMillis() + 30_000
        while (!rested && System.currentTimeMillis() < settleBy) {
            val drawn = nextFrame(1_000)
            if (drawn == null) rested = arrived else drawn.use { arrived = arrived || it.colored() }
        }
        assertTrue("the colors arrive", arrived)
        assertTrue("hidden, the colors come to rest", rested)
        assertEquals("hidden, nothing is drawn", 0, askedIn(1_000))

        // Shown, they move: two seconds of the test's clock, each frame
        // offered once the wash asks for it, until it stops asking.
        shown = true
        var before = nextFrame(10_000)
        var moved = 0
        for (n in 1..2 * 60) {
            val drawn = nextFrame(10_000) ?: break
            if (before != null && !drawn.readPixels()!!.contentEquals(before.readPixels()!!)) moved++
            before?.close()
            before = drawn
        }
        before?.close()
        assertTrue("shown, the colors move ($moved frames in 2 s)", moved >= 10)
        SwingUtilities.invokeAndWait { scene.close() }
        scope.cancel()
    }
}

// One frame of a 60 Hz screen.
private const val FrameNanos = 1_000_000_000L / 60
