@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package app.winters.octo.wayland

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.ComposeScene
import kotlinx.coroutines.test.StandardTestDispatcher
import org.jetbrains.desktop.linux.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.*

class WindowInputTest {
    // Test the actual adapter without allocating a native EGL window. Only the
    // receiving scene/transport are replaced; event ordering and state are real.
    private class RecordingWindow : AutoCloseable {
        private val dispatcher = StandardTestDispatcher()
        private val recomposer = FrameRecomposer(dispatcher)
        val keys = mutableListOf<Key>()
        val buttons = mutableListOf<PointerButtons>()
        var cancels = 0
        val window: WaylandWindow
        init {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            window = (field.get(null) as sun.misc.Unsafe).allocateInstance(WaylandWindow::class.java) as WaylandWindow
            set("pressed", mutableSetOf<MouseButton>())
            set("scale", 1f)
            set("info", NativeWindowInfo().apply { isWindowFocused = true })
            set("input", WaylandTextInput({}, {}, {}, { 1f }))
            set("recomposer", recomposer)
            set("previewKey", { _: KeyEvent -> false })
            set("scene", Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ComposeScene::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("sendPointerEvent") -> { buttons += args[5] as PointerButtons; 0 }
                    method.name.startsWith("sendKeyEvent") -> { keys += KeyEvent(args[0]).key; true }
                    method.name == "cancelPointerInput" -> { cancels++; null }
                    else -> null
                }
            })
        }
        fun set(name: String, value: Any) = WaylandWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.set(window, value)
        fun mouse(type: PointerEventType, button: MouseButton? = null) = window.pointer(type, LogicalPoint(LogicalPixels(10.0), LogicalPixels(10.0)), button)
        override fun close() { recomposer.close(); dispatcher.scheduler.runCurrent() }
    }

    @Test fun compositorMoveWithoutClientReleaseCancelsGestureAndClearsButtons() = RecordingWindow().use { w ->
        w.mouse(PointerEventType.Press, MouseButton.LEFT)
        w.mouse(PointerEventType.Exit)
        w.mouse(PointerEventType.Enter)
        w.mouse(PointerEventType.Move)
        assertTrue(w.buttons.first().isPrimaryPressed)
        assertTrue(w.buttons.drop(1).none { it.areAnyPressed })
        assertTrue(w.cancels > 0)
        w.mouse(PointerEventType.Press, MouseButton.LEFT)
        w.mouse(PointerEventType.Release, MouseButton.LEFT)
        assertTrue(w.buttons[w.buttons.lastIndex - 1].isPrimaryPressed)
        assertFalse(w.buttons.last().areAnyPressed)
    }

    @Test fun keyboardFocusLossAlsoCancelsAnActivePointerGesture() = RecordingWindow().use { w ->
        w.mouse(PointerEventType.Press, MouseButton.LEFT)
        w.window.focus(false)
        assertEquals(1, w.cancels)
        w.window.focus(true)
        w.mouse(PointerEventType.Move)
        assertFalse(w.buttons.last().areAnyPressed)
    }

    @Test fun pasteShortcutsReachComposeBeforeTheFollowingFocusKey() {
        for ((code, sym, modifiers) in listOf(
            Triple(KeyCode.V, KeySym.v, setOf(KeyModifiers.Control)),
            Triple(KeyCode.Insert, KeySym.Insert, setOf(KeyModifiers.Shift)),
        )) {
            RecordingWindow().use { w ->
                w.window.key(code, sym, true, null, modifiers)
                w.window.key(KeyCode.Tab, KeySym.Tab, true, null, emptySet())
                assertEquals(listOf(if (code == KeyCode.V) Key.V else Key.Insert, Key.Tab), w.keys)
            }
        }
    }
}
