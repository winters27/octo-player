package app.winters.octo.desktop.a11y

import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.IconAction
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.ProvideWindowLook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The keyboard's ring: it shows on what Tab reaches, not on what is
// clicked, reads against the dark glass and a light cover alike, and a
// small button is still a target the pointer can hit.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FocusRingTest {
    private val dark = Color(0xFF16181A)
    private val light = Color(0xFFE8E4D8)

    private fun scene(visibility: FocusVisibility, under: Color = dark, content: @Composable () -> Unit) =
        ImageComposeScene(200, 80, Density(1f)) {
            ProvideWindowLook(reduceMotion = false, focus = visibility) {
                Box(Modifier.background(under).padding(20.dp)) { content() }
            }
        }

    private fun tab(scene: ImageComposeScene) {
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown, codePoint = '\t'.code))
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp, codePoint = '\t'.code))
    }

    private fun Image.pixel(x: Int, y: Int): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(this)
        return bitmap.getColor(x, y)
    }

    private fun Int.color() = Color(this)

    // The luminance contrast of two colours, as WCAG counts it.
    private fun contrast(a: Color, b: Color): Float {
        fun lum(c: Color): Float {
            fun ch(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun tabRingsTheButtonAndAClickDoesNot() {
        val visibility = FocusVisibility().apply { keyboard = false }
        val scene = scene(visibility) { IconAction(OctoIcons.Play, "Play", {}, size = 36.dp) }
        try {
            scene.render()
            // A click on it: no ring (whether or not the click focused it).
            scene.sendPointerEvent(PointerEventType.Press, Offset(38f, 38f), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, Offset(38f, 38f))
            // The pointer moves off, so its own faint lift is gone too.
            scene.sendPointerEvent(PointerEventType.Move, Offset(190f, 75f))
            val clicked = scene.render()
            assertEquals("no ring after a click", dark, clicked.pixel(21, 38).color())
            // The keyboard: Tab reaches it and the ring is drawn at its edge.
            visibility.keyboard = true
            tab(scene)
            val tabbed = scene.render()
            val ring = tabbed.pixel(21, 38).color()
            assertTrue("the ring stands out from the glass: ${contrast(ring, dark)}", contrast(ring, dark) >= 3f)
        } finally {
            scene.close()
        }
    }

    @Test
    fun theRingStillShowsOnALightCover() {
        val scene = scene(FocusVisibility(), under = light) { IconAction(OctoIcons.Play, "Play", {}, size = 36.dp) }
        try {
            scene.render()
            tab(scene)
            val image = scene.render()
            // Across the ring from outside in: the lightest and darkest seen
            // must differ by at least 3:1, so its shape reads on a light cover.
            val across = (18..26).map { image.pixel(it, 38).color() }
            val most = across.maxOf { contrast(it, light) }
            assertTrue("the ring's dark edge shows on a light cover: $most", most >= 3f)
        } finally {
            scene.close()
        }
    }

    @Test
    fun aSmallButtonIsStillATargetOf24() {
        val scene = scene(FocusVisibility()) { IconAction(OctoIcons.Play, "Play", {}, size = 16.dp, iconSize = 12.dp) }
        try {
            scene.render()
            val node = scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode, "Play") }
            assertTrue("${node.size}", node.size.width >= 24 && node.size.height >= 24)
        } finally {
            scene.close()
        }
    }

    @Test
    fun aToggleSaysWhetherItIsOn() {
        val scene = scene(FocusVisibility()) {
            Row {
                IconAction(OctoIcons.Shuffle, "Shuffle", {}, toggled = true)
                IconAction(OctoIcons.Repeat, "Repeat", {}, toggled = false)
            }
        }
        try {
            scene.render()
            val shuffle = scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode, "Shuffle") }
            val repeat = scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode, "Repeat") }
            assertEquals(androidx.compose.ui.state.ToggleableState.On, shuffle.config.getOrNull(SemanticsProperties.ToggleableState))
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, repeat.config.getOrNull(SemanticsProperties.ToggleableState))
        } finally {
            scene.close()
        }
    }

    @Test
    fun aSliderTakesTheArrowKeysAndSaysItsValue() {
        var value by mutableFloatStateOf(0.5f)
        val arrows = ArrowKeys()
        val scene = ImageComposeScene(300, 80, Density(1f)) {
            ProvideWindowLook(reduceMotion = false, arrows = arrows) {
                LineSlider({ value }, { value = it }, Modifier.size(200.dp, 24.dp), label = "Volume")
            }
        }
        try {
            scene.render()
            tab(scene)
            scene.render()
            assertTrue("a focused slider holds the arrow keys", arrows.claimed)
            scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
            scene.render()
            assertEquals(0.55f, value, 0.001f)
            scene.sendKeyEvent(KeyEvent(Key.MoveEnd, KeyEventType.KeyDown))
            scene.render()
            assertEquals(1f, value, 0.001f)
            val node = scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode, "Volume") }
            assertEquals(1f, node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current)
            assertEquals("100%", node.config.getOrNull(SemanticsProperties.StateDescription))
        } finally {
            scene.close()
        }
        assertFalse("letting go of the slider gives the arrows back", arrows.claimed)
    }

    private fun find(node: SemanticsNode, name: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true) return node
        return node.children.firstNotNullOfOrNull { find(it, name) }
    }

}
