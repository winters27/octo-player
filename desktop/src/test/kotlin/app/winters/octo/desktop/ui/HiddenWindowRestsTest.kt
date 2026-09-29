package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// The moving colours behind the sign-in hold still while the window is
// minimised or in the tray, so a signed-out Octo out of sight draws
// nothing.
class HiddenWindowRestsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun theSignInColoursRestWhileTheWindowIsHidden() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var app: AppState
        SwingUtilities.invokeAndWait { app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, restored = null) }
        var shown by mutableStateOf(false)
        // Effects on the window's thread, as in the app.
        val scene = ImageComposeScene(320, 200, Density(1f), coroutineContext = Dispatchers.Main) {
            CompositionLocalProvider(LocalWindowShown provides shown) { OctoAmbience(app, moving = true, veil = 0.2f) }
        }
        val begin = System.nanoTime()
        // Frames drawn in `ms`, offered 60 times a second and drawn when due.
        fun drawnIn(ms: Long): Int {
            var drawn = 0
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                SwingUtilities.invokeAndWait {
                    if (scene.hasInvalidations()) {
                        scene.render(System.nanoTime() - begin).close()
                        drawn++
                    }
                }
                Thread.sleep(16)
            }
            return drawn
        }
        // The colours arrive and fade in first.
        drawnIn(1_500)
        assertEquals("hidden, nothing is drawn", 0, drawnIn(1_000))
        shown = true
        val moving = drawnIn(2_000)
        assertTrue("shown, the colours move ($moving frames in 2 s)", moving >= 10)
        SwingUtilities.invokeAndWait { scene.close() }
        scope.cancel()
    }
}
