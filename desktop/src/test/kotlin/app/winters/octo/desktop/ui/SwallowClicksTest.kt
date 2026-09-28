package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class SwallowClicksTest {
    // A page that counts clicks and scrolls, with a layer over it.
    private fun clicksReachingUnder(covered: Boolean): Pair<Int, Float> {
        var clicks = 0
        var scrolled = 0f
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .scrollable(rememberScrollableState { scrolled += it; it }, Orientation.Vertical)
                        .clickable { clicks++ },
                )
                if (covered) Box(Modifier.fillMaxSize().swallowClicks())
            }
        }
        try {
            scene.render()
            val spot = Offset(100f, 100f)
            scene.sendPointerEvent(PointerEventType.Enter, spot)
            scene.sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, spot)
            scene.render()
            scene.sendPointerEvent(PointerEventType.Scroll, spot, scrollDelta = Offset(0f, 3f))
            scene.render()
        } finally {
            scene.close()
        }
        return clicks to scrolled
    }

    @Test
    fun theLayerUnderHearsNothing() {
        val (clicks, scrolled) = clicksReachingUnder(covered = false)
        assertEquals(1, clicks, "the page hears a click when nothing covers it")
        assertEquals(true, scrolled != 0f, "the page hears the wheel when nothing covers it")
        assertEquals(0 to 0f, clicksReachingUnder(covered = true))
    }
}
