package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.DesktopType
import app.winters.octo.design.TitleAndActions
import app.winters.octo.design.Txt
import app.winters.octo.design.WholeTxt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A page's title is never cut short or broken inside a word, and its
// buttons move under it rather than squeezing it.
class WholeTitleTest {
    // Lays `content` out and gives where each piece of text landed.
    private fun bounds(content: @Composable () -> Unit): Map<String, Rect> {
        val scene = ImageComposeScene(1200, 800, Density(1f)) { content() }
        try {
            scene.render()
            val found = mutableMapOf<String, Rect>()
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.let { found[it] = node.boundsInRoot }
                node.children.forEach(::walk)
            }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return found
        } finally {
            scene.close()
        }
    }

    private val oneLine = 40f

    @Test
    fun aLongTitleWrapsWholeRatherThanBeingCut() {
        val title = "Everything I have ever loved, in the order I found it, from the first cassette onwards"
        val at = bounds { Box(Modifier.width(300.dp)) { WholeTxt(title, DesktopType.pageTitle) } }.getValue(title)
        assertTrue("several lines: $at", at.height > oneLine * 2)
        assertTrue(at.width <= 300f)
    }

    @Test
    fun aNameJoinedWithUnderscoresWrapsAfterThem() {
        val title = "Radiohead_OK_Computer_OKNOTOK_1997_2017_FLAC_24bit"
        val at = bounds { Box(Modifier.width(300.dp)) { WholeTxt(title, DesktopType.pageTitle) } }.getValue(title)
        assertTrue("several lines at full size: $at", at.height > oneLine)
        assertTrue(at.width <= 300f)
    }

    @Test
    fun aWordTooWideForTheLineIsSetSmallerNotBroken() {
        val title = "Supercalifragilisticexpialidociousness"
        val at = bounds { Box(Modifier.width(300.dp)) { WholeTxt(title, DesktopType.pageTitle) } }.getValue(title)
        assertTrue("one line: $at", at.height < oneLine)
        assertTrue(at.width <= 300f)
    }

    @Test
    fun buttonsShareTheLineWhileTheTitleFits() {
        val at = bounds { Box(Modifier.width(800.dp)) { Titled("Songs") } }
        assertEquals(at.getValue("Songs").center.y, at.getValue("Play Shuffle").center.y, 1f)
        assertTrue(at.getValue("Play Shuffle").right >= 799f)
    }

    @Test
    fun buttonsMoveUnderATitleTheyWouldSqueeze() {
        val title = "Everything I have ever loved, in order"
        val at = bounds { Box(Modifier.width(400.dp)) { Titled(title) } }
        assertTrue(at.getValue("Play Shuffle").top >= at.getValue(title).bottom)
        assertEquals(0f, at.getValue("Play Shuffle").left, 0f)
    }

    @Composable
    private fun Titled(title: String) {
        TitleAndActions(title, DesktopType.pageTitle, heading = { WholeTxt(title, DesktopType.pageTitle) }) {
            Txt("Play Shuffle", DesktopType.body, modifier = Modifier.width(220.dp))
        }
    }
}
