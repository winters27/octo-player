@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "DEPRECATION")

package app.winters.octo.wayland

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.desktop.linux.KeyCode
import org.jetbrains.desktop.linux.KeyModifiers
import org.jetbrains.desktop.linux.KeySym
import org.jetbrains.desktop.linux.MouseButton
import org.jetbrains.skia.Surface
import org.junit.Test
import kotlin.test.*

class KeyboardInputTest {
    // Based on the independent PR #14 V2 QueuedKeysTest. The real host key
    // method and Compose field run; only native allocation/transport are absent.
    @Test fun backspaceThenSelectAllWithoutAFramePreservesDeletionAndCopiesIt() = field {
        tap(KeyCode.BackSpace, KeySym.BackSpace)
        assertEquals(TextFieldValue("save", TextRange(4)), values[0])
        tap(KeyCode.A, KeySym.a, setOf(KeyModifiers.Control))
        assertEquals(TextFieldValue("save", TextRange(0, 4)), values[0])
        clipboardData.put("different-copy-sentinel")
        tap(KeyCode.C, KeySym.c, setOf(KeyModifiers.Control))
        await { clipboardData.text() == "save" }
        assertEquals(TextFieldValue("save", TextRange(0, 4)), values[0])
    }

    @Test fun repeatedKeyDownsAndNativeTextReplacementUseTheLatestSelection() = field {
        // No key-up or test frame can accidentally supply the publication boundary.
        host.key(KeyCode.BackSpace, KeySym.BackSpace, true, null, emptySet())
        host.key(KeyCode.BackSpace, KeySym.BackSpace, true, null, emptySet())
        assertEquals(TextFieldValue("sav", TextRange(3)), values[0])
        host.key(KeyCode.A, KeySym.a, true, null, setOf(KeyModifiers.Control))
        assertEquals(TextFieldValue("sav", TextRange(0, 3)), values[0])
        host.key(KeyCode.X, KeySym.x, true, "x", emptySet())
        assertEquals(TextFieldValue("x", TextRange(1)), values[0])
        host.key(KeyCode.A, KeySym.a, true, null, setOf(KeyModifiers.Control))
        assertEquals(TextFieldValue("x", TextRange(0, 1)), values[0])
    }

    @Test fun publicationThatClosesOrUnfocusesTheWindowDropsTheKey() {
        for (closeWindow in listOf(false, true)) field {
            effects.launch {
                if (closeWindow) closeHost() else host.focus(false)
            }
            assertTrue(info.isWindowFocused)
            tap(KeyCode.BackSpace, KeySym.BackSpace)
            assertEquals(TextFieldValue("saved", TextRange(5)), values[0])
            assertEquals(0, previews, "A key must not reach preview/scene after publication retires its window")
        }
    }

    @Test fun publicationThatRemovesTheFieldDoesNotTypeIntoItsRetiredRequest() = field {
        visible = false
        host.key(KeyCode.X, KeySym.x, true, "x", emptySet())
        assertFalse(input.active)
        assertEquals(TextFieldValue("saved", TextRange(5)), values[0])
    }

    @Test fun pasteIsDispatchedBeforeTabAndItsAsyncReplyKeepsTheOriginalField() {
        for ((code, symbol, modifiers) in listOf(
            Triple(KeyCode.V, KeySym.v, setOf(KeyModifiers.Control)),
            Triple(KeyCode.Insert, KeySym.Insert, setOf(KeyModifiers.Shift)),
        )) field(initial = "") {
            clipboardData.put("original-field")
            val gate = CompletableDeferred<Unit>()
            clipboardGate = gate
            tap(code, symbol, modifiers)
            assertEquals(1, clipboardReads, "Paste must reach Compose synchronously, before the next key")
            tap(KeyCode.Tab, KeySym.Tab)
            settle()
            assertEquals(1, focusedField)
            assertFalse(gate.isCompleted)
            assertEquals("", values[0].text)
            assertEquals("target", values[1].text)
            gate.complete(Unit)
            await { values[0].text == "original-field" }
            assertEquals("target", values[1].text)
        }
    }

    private fun field(initial: String = "saved", block: Field.() -> Unit) {
        Field(initial).use { it.start(); it.block() }
    }

    private class Field(initial: String) : AutoCloseable {
        private val dispatcher = StandardTestDispatcher()
        init { Dispatchers.setMain(dispatcher) }
        private val recomposer = FrameRecomposer(dispatcher)
        val input = WaylandTextInput({}, {}, {}, { 1f }, recomposer::flushPendingTextInput)
        val info = NativeWindowInfo().apply { isWindowFocused = true }
        val values = mutableStateListOf(TextFieldValue(initial, TextRange(initial.length)), TextFieldValue("target", TextRange(6)))
        var visible by mutableStateOf(true)
        var focusedField = -1
        var previews = 0
        var clipboardReads = 0
        var clipboardGate: CompletableDeferred<Unit>? = null
        val clipboardData = WaylandClipboard({ emptyList() }, { _, _ -> }, {})
        private val clipboard = object : Clipboard by clipboardData {
            override suspend fun getClipEntry(): ClipEntry? {
                clipboardReads++
                clipboardGate?.await()
                return clipboardData.getClipEntry()
            }
        }
        lateinit var effects: CoroutineScope
        private var requests = 0
        private var hostClosed = false
        private val platform = object : PlatformContext.Empty() {
            override val windowInfo get() = info
            override val textInputService get() = input
            override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                requests++
                return input.start(request)
            }
        }
        private val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = platform, size = IntSize(400, 100))
        private val surface = Surface.makeRasterN32Premul(400, 100)
        val host: WaylandWindow

        init {
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            host = (unsafe.get(null) as sun.misc.Unsafe).allocateInstance(WaylandWindow::class.java) as WaylandWindow
            fun set(name: String, value: Any) = WaylandWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.set(host, value)
            set("pressed", mutableSetOf<MouseButton>())
            set("scale", 1f)
            set("info", info)
            set("input", input)
            set("recomposer", recomposer)
            set("previewKey", { _: KeyEvent -> previews++; false })
            set("scene", scene)
            input.available = true
            input.focused = true
        }

        fun start() {
            scene.setContent {
                effects = rememberCoroutineScope()
                CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides clipboardData.legacy) {
                    if (visible) Column {
                        repeat(2) { index ->
                            val focus = remember { FocusRequester() }
                            BasicTextField(values[index], { values[index] = it },
                                Modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) focusedField = index }, singleLine = true)
                            if (index == 0) LaunchedEffect(Unit) { focus.requestFocus() }
                        }
                    }
                }
            }
            settle()
            assertEquals(1, requests)
            assertEquals(0, focusedField)
        }

        fun tap(code: UInt, symbol: UInt, modifiers: Set<KeyModifiers> = emptySet()) {
            host.key(code, symbol, true, null, modifiers)
            host.key(code, symbol, false, null, modifiers)
        }

        fun settle() {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                dispatcher.scheduler.runCurrent()
                recomposer.performFrame(System.nanoTime())
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
        }

        fun await(done: () -> Boolean) = runBlocking {
            withTimeout(5_000) {
                while (!done()) {
                    dispatcher.scheduler.runCurrent()
                    delay(1) // Await the real clipboard reply; never advance a test frame.
                }
            }
        }

        fun closeHost() {
            hostClosed = true
            host.nativeClosed()
        }

        override fun close() {
            if (!hostClosed) {
                scene.close()
                recomposer.close()
            }
            dispatcher.scheduler.runCurrent()
            surface.close()
            Dispatchers.resetMain()
        }
    }
}
