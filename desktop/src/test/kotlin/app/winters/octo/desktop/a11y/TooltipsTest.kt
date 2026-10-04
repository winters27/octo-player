package app.winters.octo.desktop.a11y

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.nav.Page
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Brandon, 2026-10-04: "a bunch of icons that I don't really know what
// they do ... because theres no tooltip". Every icon button names itself
// in a tooltip when the pointer rests on it, and when the keyboard
// reaches it.
class TooltipsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun A11yScene.shownWords(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }

    private fun A11yScene.button(name: String) =
        nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == name }

    @Test(timeout = 180_000)
    fun everyIconButtonNamesItselfUnderThePointer() = A11yScene(folder.newFolder()).use { s ->
        s.onUi {
            s.app.play(s.app.library!!.index!!.songs, 0)
            s.app.player.togglePlay()
            s.app.navigator.go(Page.Songs)
        }
        s.settle(1_000).close()
        for (name in listOf("Shuffle", "Previous", "Next", "Repeat", "Lyrics", "Queue", "Choose columns", "Back", "Open the player")) {
            val spot = s.button(name).boundsInRoot.center
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(spot.x, spot.y)) }
            s.settle(900).close()
            assertTrue("$name shows its tooltip (at $spot: ${s.shownWords().takeLast(8)})", name in s.shownWords())
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(700f, 60f)) }
            s.settle(200).close()
        }
    }

    @Test(timeout = 180_000)
    fun theKeyboardShowsItAtOnce() = A11yScene(folder.newFolder()).use { s ->
        s.onUi { s.app.play(s.app.library!!.index!!.songs, 0) }
        s.settle(800).close()
        assertTrue(s.tabTo("Shuffle"))
        s.render(4)
        assertTrue("Shuffle" in s.shownWords())
    }
}
