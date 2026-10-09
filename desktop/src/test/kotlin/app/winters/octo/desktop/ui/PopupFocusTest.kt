package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.PopupHost
import app.winters.octo.design.PopupLayer
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TextAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

// A control in a pop-up that goes away with what clicking it does (a
// switch that changes the view, a button that turns into another) must not
// break the pop-up: the keyboard moves on and the pop-up keeps drawing.
@OptIn(ExperimentalComposeUiApi::class)
class PopupFocusTest {
    private var view by mutableStateOf(0)

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
        val at = node.boundsInRoot.center
        listOf(PointerEventType.Move, PointerEventType.Press, PointerEventType.Release).forEach { type ->
            scene.sendPointerEvent(type, at, buttons = PointerButtons(isPrimaryPressed = type == PointerEventType.Press), button = PointerButton.Primary)
            scene.render().close()
        }
    }

    @Test
    fun aClickedControlThatGoesAwayLeavesThePopupWorking() {
        val host = PopupHost()
        val errors = ByteArrayOutputStream()
        val err = System.err
        System.setErr(PrintStream(errors))
        val scene = ImageComposeScene(800, 600, Density(1f)) {
            ProvideWindowLook(reduceMotion = true, focus = FocusVisibility(keyboard = true)) {
                val haze = rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    PopupLayer(host, haze)
                }
            }
        }
        try {
            host.showCentred(width = 300.dp) {
                PopupPadding {
                    // Each view's controls are its own: the one clicked goes.
                    if (view == 0) {
                        GlazeSegments(listOf("One", "Two"), "One", { it }, { view = 1 })
                        GlazeCapsule(null, "Next", { view = 1 })
                    } else {
                        TextAction("Back", { view = 0 })
                        Box(Modifier.size(10.dp).clickable { })
                    }
                }
            }
            repeat(4) { scene.render().close() }
            click(scene, "Two")
            repeat(4) { scene.render().close() }
            assertEquals(1, view)
            click(scene, "Back")
            repeat(4) { scene.render().close() }
            assertEquals(0, view)
            click(scene, "Next")
            repeat(4) { scene.render().close() }
            assertEquals(1, view)
            assertTrue("the pop-up is still open", host.open)
        } finally {
            System.setErr(err)
            scene.close()
        }
        assertTrue("no error drawing the pop-up: $errors", !errors.toString().contains("Exception"))
    }
}
