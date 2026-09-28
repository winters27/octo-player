package app.winters.octo.desktop.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.OctoColors
import app.winters.octo.design.TypingState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

// The Taskbar and startup rows as Settings draws them, in each of their
// states, for looking at: OCTO_SHOTS=1 writes build/shots/settings-startup-*.png.
class StartupRowsShotTest {
    @Test
    fun theRowsCanBeLookedAt() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        shoot(out, "settings-startup-build") { StartupRows(SystemPrefs(), works = false, turnedOff = false, tray = true) {} }
        shoot(out, "settings-startup-off") { StartupRows(SystemPrefs(), works = true, turnedOff = false, tray = true) {} }
        shoot(out, "settings-startup-on") { StartupRows(SystemPrefs(startWithWindows = true, startInTray = true), works = true, turnedOff = false, tray = true) {} }
        shoot(out, "settings-startup-task-manager") { StartupRows(SystemPrefs(startWithWindows = true), works = true, turnedOff = true, tray = true) {} }
    }

    private fun shoot(out: File, name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scale = 2f
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene((760 * scale).toInt(), (260 * scale).toInt(), Density(scale)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) {
                    Column(Modifier.fillMaxSize().background(OctoColors.Background).padding(20.dp)) {
                        Column(Modifier.width(720.dp)) { content() }
                    }
                }
            }
        }
        var t = 0L
        repeat(40) {
            SwingUtilities.invokeAndWait { scene.render(t) }
            t += 16_000_000
        }
        lateinit var bytes: ByteArray
        SwingUtilities.invokeAndWait {
            bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            scene.close()
        }
        File(out, "$name.png").writeBytes(bytes)
    }
}
