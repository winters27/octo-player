package app.winters.octo.desktop.perf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

// Milliseconds since this process started (the packaged runtime has no
// java.management).
private fun uptime(): Long = System.currentTimeMillis() - ProcessHandle.current().info().startInstant().get().toEpochMilli()

// Starts the app's code as far as its first frame, off screen and signed
// out, then prints how long that took from the JVM's start and ends. No
// window, no tray, no single-instance lock, nothing of the system's: it is
// for timing the packaged runtime and class loading (for example with and
// without a CDS archive) without starting Octo itself. Run it with the app
// image's jars and the test classes on the class path.
fun main() {
    val folder = Files.createTempDirectory("octo-probe").toFile()
    try {
        val settings = SettingsStore(File(folder, "settings.json"))
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var app: AppState
        SwingUtilities.invokeAndWait { app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, restored = null) }
        val made = uptime()
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(1440, 900, Density(1f), coroutineContext = Dispatchers.Main) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            scene.render(0).close()
        }
        val drawn = uptime()
        println("probe: app state ${made} ms, first frame ${drawn} ms after the process started")
    } catch (e: Throwable) {
        e.printStackTrace()
        exitProcess(1)
    } finally {
        folder.deleteRecursively()
    }
    exitProcess(0)
}
