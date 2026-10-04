package app.winters.octo.desktop.ui

import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.scrollbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A tooltip shows a quarter second after the pointer rests on its control,
// goes at a press, and only one shows at a time; a list's scroll bar shows
// while the pointer moves over the list and rests away again.
@OptIn(ExperimentalComposeUiApi::class)
class TooltipAndScrollbarTest {
    private fun ImageComposeScene.texts(): List<String> {
        val all = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { all += it.text }
            node.children.forEach(::walk)
        }
        semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    // Draws in real time for `ms`, and gives the last frame.
    private fun ImageComposeScene.run(ms: Long): Image {
        val begin = System.nanoTime()
        var image = render(0)
        while (System.nanoTime() - begin < ms * 1_000_000) {
            image.close()
            Thread.sleep(16)
            image = render(System.nanoTime() - begin)
        }
        return image
    }

    @Test
    fun aTooltipShowsAfterAQuarterSecondAndGoesAtAPress() {
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                OctoTooltip("Shuffle", Modifier.align(Alignment.Center)) { Box(Modifier.size(40.dp)) }
                OctoTooltip("Repeat", Modifier.align(Alignment.TopStart)) { Box(Modifier.size(40.dp)) }
            }
        }
        try {
            val it = scene
            it.run(50).close()
            it.sendPointerEvent(PointerEventType.Move, Offset(200f, 150f))
            it.run(100).close()
            assertFalse("not yet", "Shuffle" in it.texts())
            it.run(400).close()
            assertTrue("after resting", "Shuffle" in it.texts())
            it.sendPointerEvent(PointerEventType.Press, Offset(200f, 150f), buttons = PointerButtons(isPrimaryPressed = true))
            it.sendPointerEvent(PointerEventType.Release, Offset(200f, 150f))
            it.run(400).close()
            assertFalse("gone at the press", "Shuffle" in it.texts())
            // Onto the other: only it.
            it.sendPointerEvent(PointerEventType.Move, Offset(20f, 20f))
            it.run(500).close()
            assertTrue("Repeat" in it.texts())
            assertFalse("Shuffle" in it.texts())
        } finally {
            scene.close()
        }
    }

    @Test
    fun aListsBarShowsWhileThePointerMovesOverIt() {
        val state = LazyListState()
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            LazyColumn(Modifier.fillMaxSize().background(Color.Black).scrollbar(state), state) {
                items(100) { Box(Modifier.fillMaxWidth().height(40.dp)) }
            }
        }
        try {
            val it = scene
            // The thumb's spot: 3 in from the right edge, at the top.
            fun thumbLit(image: Image): Boolean {
                val bitmap = Bitmap.makeFromImage(image)
                return Color(bitmap.getColor(300 - 6, 20)).red > 0.1f
            }
            assertFalse("at rest", thumbLit(it.run(300)))
            it.sendPointerEvent(PointerEventType.Move, Offset(150f, 200f))
            assertTrue("the pointer moved", thumbLit(it.run(300)))
            assertFalse("rested again", thumbLit(it.run(2_000)))
        } finally {
            scene.close()
        }
    }
}
