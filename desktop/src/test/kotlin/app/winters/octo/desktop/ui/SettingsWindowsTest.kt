package app.winters.octo.desktop.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.winters.octo.desktop.settings.HeldDisk
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.StepClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

// A start with a held settings file, in real windows, as Main runs it: the
// "Settings are locked" window in an application of its own, which grows to
// fit when what holds Octo changes and ends once the file can be read, and
// then a second application, as the app's, whose window shows. Compose
// makes no window without a screen, so this runs only where there is one.
class SettingsWindowsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 60_000)
    fun theAppsApplicationStartsAfterTheLockedWindowsEnds() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val file = File(folder.root, "octo/${SettingsStore.FILE_NAME}")
        SettingsStore(file).update { it.copy(sidePanel = "queue") }
        val disk = HeldDisk().apply { refuseReads += SettingsStore.FILE_NAME }
        val held = SettingsStore.openOn(disk, file, clock = StepClock(), waitMs = 0) as SettingsOpen.Held
        val opened = AtomicReference<SettingsStore?>(null)
        val shown = AtomicBoolean(false)
        // As Main: the wait's own application, then the app's on the same
        // thread (here not ending the process when it closes).
        val start = thread(isDaemon = true, name = "octo-start") {
            opened.set(waitForSettings(held))
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Octo after the wait") {
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        withTimeoutOrNull(10_000) { while (!window.isShowing) delay(16) }
                        shown.set(window.isShowing)
                        exitApplication()
                    }
                }
            }
        }
        fun locked(): Frame? = java.awt.Window.getWindows().firstOrNull { it is Frame && it.title == "Octo" && it.isShowing } as Frame?
        fun waitFor(what: () -> Boolean) {
            val until = System.currentTimeMillis() + 15_000
            while (!what() && start.isAlive && System.currentTimeMillis() < until) Thread.sleep(50)
        }
        // Its height and the height its words need, read on its own thread.
        fun heights(): Pair<Int, Int> {
            var both = 0 to 0
            SwingUtilities.invokeAndWait { locked()?.let { both = it.height to it.preferredSize.height } }
            return both
        }
        waitFor { locked() != null }
        assertTrue("the locked window showed", locked() != null)
        val (inUse, _) = heights()
        // Now this account may not read the file: the window's own look
        // gives it a line more, the file, and the window grows to show it.
        disk.refuseReads.clear()
        disk.denyReads += SettingsStore.FILE_NAME
        waitFor { heights().first > inUse }
        val (tall, needs) = heights()
        assertTrue("the window grew for the file's line: $inUse to $tall", tall > inUse)
        assertTrue("nothing cut off: $tall for $needs", tall >= needs)
        // Let go: the window's own look reads the file and ends its
        // application.
        disk.denyReads.clear()
        start.join(30_000)
        assertFalse("both applications ended", start.isAlive)
        assertEquals("the real settings", "queue", opened.get()?.current?.sidePanel)
        assertTrue("the second application's window showed", shown.get())
    }
}
