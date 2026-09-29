package app.winters.octo.desktop.a11y

import androidx.compose.ui.input.key.Key
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Pictures of the keyboard's ring on the main surfaces, and of the larger
// text sizes, for looking at: OCTO_SHOTS=1 ./gradlew :desktop:test
// --tests '*A11yShotsTest*' writes build/shots/a11y-*.png.
class A11yShotsTest {
    @get:Rule val folder = TemporaryFolder()

    // A teal cover with a warm disc: colour enough to show the glow.
    private val cover: ByteArray = run {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(0xFF3AA6A0.toInt())
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = 0xFFE0703A.toInt() })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun scene(textScale: Float = 1f) = A11yScene(folder.newFolder(), cover = cover, textScale = textScale).also { s ->
        s.onUi {
            s.app.play(s.app.library!!.index!!.songs, 0)
            s.app.toggleSidePanel(SidePanel.Queue)
        }
        s.render(4)
    }

    @Test(timeout = 600_000)
    fun drawTheKeyboardsRing() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        scene().use { s ->
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.render(4)
            s.tabTo("Albums")
            s.shot("a11y-ring-sidebar")
            s.tabTo("Song list, 5 songs")
            s.press(Key.DirectionDown)
            s.press(Key.DirectionDown)
            s.shot("a11y-ring-table-row")
            s.press(Key.Menu)
            s.shot("a11y-ring-menu")
            s.press(Key.Escape)
            s.onUi { s.app.popups.close() }
            s.tabTo("Pause")
            s.shot("a11y-ring-player")
            s.tabTo("Song position")
            s.shot("a11y-ring-slider")
            s.tabTo("Queue options")
            s.shot("a11y-ring-panel")
            s.onUi { s.app.navigator.go(Page.Settings) }
            s.render(4)
            s.tabTo("Moving background The cover's colours drift behind the full player. Off holds them still, easier on a laptop's battery.", presses = 300)
            s.shot("a11y-ring-settings-switch")
            s.onUi { s.app.navigator.go(Page.Albums) }
            s.render(4)
            s.tabTo("OK Computer Radiohead · 1997")
            s.shot("a11y-ring-card")
        }
    }

    @Test(timeout = 600_000)
    fun drawTheLargerTextSizes() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (size in listOf(1.15f, 1.3f)) {
            scene(size).use { s ->
                val tag = (size * 100).toInt()
                s.onUi { s.app.navigator.go(Page.Songs) }
                s.shot("a11y-text-$tag-songs")
                s.onUi { s.app.navigator.go(Page.Settings) }
                s.shot("a11y-text-$tag-settings")
                s.onUi { s.app.navigator.go(Page.Home) }
                s.shot("a11y-text-$tag-home")
            }
        }
    }
}
