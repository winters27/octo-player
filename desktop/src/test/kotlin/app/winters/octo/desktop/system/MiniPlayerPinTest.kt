package app.winters.octo.desktop.system

import androidx.compose.ui.window.application
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.WindowSpot
import app.winters.octo.desktop.settings.currentOs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import kotlin.concurrent.thread

// Brandon: "pinning the mini player does nothing". The pin does reach the
// window: Windows' own topmost mark comes and goes with it. Pinned is the
// usual state, so a first click unpins, which shows nothing until another
// window comes over it; the mini player now says what the pin did.
class MiniPlayerPinTest {
    @get:Rule val temp = TemporaryFolder()

    private interface User32 : Library {
        fun GetWindowLongW(hWnd: Pointer, index: Int): Int
    }

    @Test
    fun thePinsWordsSayWhatItDid() {
        assertEquals("Kept on top of other windows", pinNoteFor(true))
        assertEquals("Other windows can cover it now", pinNoteFor(false))
    }

    @Test
    fun thePinPutsTheRealWindowOnTopAndLetsItGo() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        assumeFalse(GraphicsEnvironment.isHeadless())
        val user32 = Native.load("user32", User32::class.java)
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, SilentPlayer())
        var quit: (() -> Unit)? = null
        val ui = thread {
            application(exitProcessOnExit = false) {
                quit = ::exitApplication
                MiniPlayerWindow(app, WindowSpot(120f, 120f, 380f, 124f), DesktopOs.Windows, null, {}, ::exitApplication, reduceMotion = true)
            }
        }
        try {
            fun mini(): Window? = Window.getWindows().firstOrNull { it is Frame && it.title == "Octo mini player" && it.isShowing }
            fun topmost(): Boolean = user32.GetWindowLongW(Native.getWindowPointer(mini()!!), GWL_EXSTYLE) and WS_EX_TOPMOST != 0
            fun waitFor(what: () -> Boolean) {
                val until = System.currentTimeMillis() + 15_000
                while (!what() && System.currentTimeMillis() < until) Thread.sleep(50)
                assertTrue("waited too long", what())
            }
            waitFor { mini() != null }
            waitFor { topmost() }
            settings.update { it.copy(system = it.system.copy(miniPlayerOnTop = false)) }
            waitFor { !topmost() }
            settings.update { it.copy(system = it.system.copy(miniPlayerOnTop = true)) }
            waitFor { topmost() }
        } finally {
            quit?.invoke()
            ui.join(5_000)
            scope.cancel()
        }
    }

    private companion object {
        const val GWL_EXSTYLE = -20
        const val WS_EX_TOPMOST = 0x8
    }
}
