package app.winters.octo.desktop.a11y

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.pages.askToRemove
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// What Settings asks before it lets something go: removing a server and
// removing every kept song each ask in a sheet whose last button, the one
// that goes ahead, is in the error red. On the page, Remove all's name is
// plain and only its button, Remove, is red.
class SettingsConfirmTest {
    @get:Rule val folder = TemporaryFolder()

    private fun A11yScene.button(name: String): SemanticsNode =
        nodes(merged = true).last { it.name() == name && it.clickable }

    private fun A11yScene.click(node: SemanticsNode) {
        onUi { node.config[SemanticsActions.OnClick].action?.invoke() }
        render(4)
    }

    // How far the reddest pixel in `box` leans to red: red over the larger
    // of green and blue. The error red is about 170 there; white words and
    // the glass are near 0.
    private fun redness(bitmap: Bitmap, box: Rect): Int {
        var most = 0
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            val c = bitmap.getColor(x, y)
            most = maxOf(most, (c shr 16 and 0xFF) - maxOf(c shr 8 and 0xFF, c and 0xFF))
        }
        return most
    }

    // The sheet's Cancel and its last button, `go`: Cancel plain, `go`
    // after it and red.
    private fun A11yScene.checkSheet(go: String) {
        val frame = Bitmap.makeFromImage(settle(600))
        val cancel = button("Cancel").boundsInRoot
        val ahead = button(go).boundsInRoot
        assertTrue("$go comes after Cancel", ahead.left >= cancel.right)
        assertTrue("$go is red: ${redness(frame, ahead)}", redness(frame, ahead) >= 120)
        assertTrue("Cancel is not: ${redness(frame, cancel)}", redness(frame, cancel) < 40)
    }

    @Test(timeout = 120_000)
    fun removingAServerAsksWithItsRemoveLastAndRed() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.openSettings(SettingsKey.Servers) }
            s.render(4)
            s.onUi { askToRemove(s.app, s.app.connection!!.server) }
            s.render(4)
            assertTrue("the sheet asks", s.nodes().any { it.name().startsWith("Remove ") && it.name().endsWith("?") })
            s.checkSheet("Remove")
            s.click(s.button("Cancel"))
            assertFalse("Cancel keeps it", s.nodes().any { it.name().startsWith("Remove ") && it.name().endsWith("?") })
            assertTrue(s.app.connection != null)
        }
    }

    @Test(timeout = 120_000)
    fun removeAllIsPlainUntilItsRedButtonAndAsksWithItsRemoveAllLastAndRed() {
        // Tall enough that Remove all, at the page's foot, is on screen.
        A11yScene(folder.root, reduceMotion = true, height = 1_400).use { s ->
            s.onUi {
                s.app.offline.keepLiked(true)
                s.app.openSettings(SettingsKey.Offline)
            }
            s.render(4)
            // On the page, the row's name is plain and its button, Remove,
            // is in the lighter red a quiet pill takes.
            val page = Bitmap.makeFromImage(s.settle(600))
            val onPage = s.button("Remove")
            assertTrue("Remove on the page is red: ${redness(page, onPage.boundsInRoot)}", redness(page, onPage.boundsInRoot) >= 60)
            val name = s.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text } == "Remove all" }
            assertTrue("the row's name is not red: ${redness(page, name.boundsInRoot)}", redness(page, name.boundsInRoot) < 40)
            s.click(onPage)
            assertTrue("the sheet asks", s.nodes().any { it.name() == "Remove every kept song?" })
            s.checkSheet("Remove all")
        }
    }
}
