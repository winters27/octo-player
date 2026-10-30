@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "DEPRECATION")

package app.winters.octo.wayland

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.desktop.linux.Event
import org.jetbrains.desktop.linux.TextInputCommitStringData
import org.jetbrains.desktop.linux.TextInputPreeditStringData
import org.jetbrains.skia.Surface
import org.junit.Test
import kotlin.test.*

class ModernTextInputTest {
    // Adapted from the independent PR #14 V1 reproduction, RequestInputTest.kt.
    // Keep the real BasicTextField/key-dispatch path and the committed-text assertion.
    @Test fun keyboardEditEndsPreeditBeforeWindowLeaveOnActualComposeField() {
        for (availabilityFirst in listOf(false, true)) field {
            preedit()
            settle()
            key(Key.Backspace)
            settle()
            assertEquals("saved", value.text)
            assertNull(value.composition)
            key(Key.A, ctrl = true)
            settle()
            assertEquals(TextRange(0, 5), value.selection)
            leave(availabilityFirst)
            settle()
            assertEquals("saved", value.text)
            assertEquals(TextRange(0, 5), value.selection)
        }
    }

    @Test fun leaveAndEmptyDoneReadPublishedCompositionWithoutWaitingForObserver() {
        for (emptyDone in listOf(false, true)) field {
            preedit()
            settle()
            key(Key.Backspace)
            // Publish the field, but deliberately do not run the request observer.
            frame()
            frame()
            assertNull(request.value().composition)
            key(Key.A, ctrl = true)
            assertEquals(TextRange(0, 5), value.selection)
            val before = value
            val editsBefore = edits
            if (emptyDone) input.receive(Event.TextInput(null, null, null))
            leave()
            assertEquals(before, value)
            assertEquals(editsBefore, edits, "No edit callback may republish the processor's old selection")
        }
    }

    @Test fun keyboardEditBeforePublicationSurvivesEmptyDoneAndLeave() = field {
        preedit()
        settle()
        key(Key.Backspace)
        assertNull(value.composition)
        assertEquals("saved", value.text)
        assertNotNull(request.value().composition, "Exercise the actual request publication gap")
        val before = value
        val editsBefore = edits
        input.receive(Event.TextInput(null, null, null))
        leave()
        assertEquals(before, value)
        assertEquals(editsBefore, edits)
    }

    @Test fun clipboardEditBeforePublicationSurvivesEmptyDoneAndLeave() {
        for (emptyDone in listOf(false, true)) field {
            preedit()
            settle()
            paste("pasted")
            assertEquals("savedにpasted", value.text)
            assertNull(value.composition)
            assertNotNull(request.value().composition, "Exercise the actual request publication gap")
            val before = value
            val editsBefore = edits
            if (emptyDone) input.receive(Event.TextInput(null, null, null))
            leave()
            assertEquals(before, value)
            assertEquals(editsBefore, edits)
        }
    }

    @Test fun emptyDoneWithoutPreeditPreservesUnpublishedKeyboardAndClipboardChanges() = field {
        key(Key.A, ctrl = true)
        val selected = value
        input.receive(Event.TextInput(null, null, null))
        assertEquals(selected, value)
        settle()
        paste("pasted")
        assertEquals("pasted", value.text)
        input.receive(Event.TextInput(null, null, null))
        assertEquals("pasted", value.text)
        assertEquals(0, edits)
    }

    @Test fun genuinePreeditCancelsOnceInBothLeaveOrdersIncludingReentry() {
        for (availabilityFirst in listOf(false, true)) {
            for (publishFrame in listOf(false, true)) field {
                preedit()
                if (publishFrame) settle()
                onChange = { input.available = false; input.focused = false }
                leave(availabilityFirst)
                assertEquals("saved", value.text)
                assertEquals(TextRange(5), value.selection)
                assertNull(value.composition)
                assertEquals(2, edits, "One preedit and exactly one cancellation")
                assertEquals(1, disables)
                onChange = {}
                input.available = true
                input.focused = true
                input.type("x")
                assertEquals("savedx", value.text)
            }
        }
    }

    @Test fun emptyDoneStillCancelsGenuineModernPreedit() = field {
        preedit()
        input.receive(Event.TextInput(null, null, null))
        assertEquals("saved", value.text)
        assertNull(value.composition)
        assertEquals(2, edits)
    }

    @Test fun immediateCommitSurvivesLeaveEvenWithLaggedRequestPublication() {
        for (availabilityFirst in listOf(false, true)) {
            for (lagPublication in listOf(false, true)) field {
                preedit()
                settle()
                if (lagPublication) heldValue = request.value()
                input.receive(Event.TextInput(null, TextInputCommitStringData("日本"), null))
                assertEquals("saved日本", value.text)
                assertNull(value.composition)
                if (lagPublication) assertNotNull(request.value().composition)
                // A stale composed snapshot must never re-arm a cleared commit latch.
                observer.scheduler.runCurrent()
                leave(availabilityFirst)
                assertEquals("saved日本", value.text)
                assertEquals(2, edits)
            }
        }
    }

    private fun field(block: Field.() -> Unit) {
        val field = Field()
        try {
            field.start()
            field.block()
        } finally {
            field.close()
        }
    }

    private class Field : AutoCloseable {
        val dispatcher = StandardTestDispatcher()
        // Separate scheduling makes observer starvation deterministic, not a timing bet.
        val observer = StandardTestDispatcher(TestCoroutineScheduler())
        var value by mutableStateOf(TextFieldValue("saved", TextRange(5)))
        var heldValue: TextFieldValue? = null
        var onChange: () -> Unit = {}
        private var changes = 0
        var edits = 0
        var disables = 0
        private var modernRequests = 0
        lateinit var request: PlatformTextInputMethodRequest
        private val info = NativeWindowInfo().apply { isWindowFocused = true }
        val input = WaylandTextInput({}, {}, { disables++ }, { 1f }, { recomposer.flushPendingTextInput() })
        val clipboard = WaylandClipboard({ emptyList() }, { _, _ -> }, {})
        private val recomposer: FrameRecomposer
        private val scene: ComposeScene
        private val surface = Surface.makeRasterN32Premul(400, 100)

        init {
            Dispatchers.setMain(dispatcher)
            input.available = true
            input.focused = true
            val platform = object : PlatformContext.Empty() {
                override val windowInfo get() = info
                override val textInputService get() = input
                override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                    modernRequests++
                    val wrapped = object : PlatformTextInputMethodRequest by request {
                        override val value = { heldValue ?: request.value() }
                        override val onEditCommand = { commands: List<androidx.compose.ui.text.input.EditCommand> ->
                            edits++
                            request.onEditCommand(commands)
                        }
                    }
                    this@Field.request = wrapped
                    return withContext(observer) { input.start(wrapped) }
                }
            }
            recomposer = FrameRecomposer(dispatcher)
            scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = platform, size = IntSize(400, 100))
        }

        fun start() {
            scene.setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides clipboard.legacy) {
                    val focus = remember { FocusRequester() }
                    BasicTextField(value, { value = it; changes++; onChange() }, modifier = Modifier.focusRequester(focus), singleLine = true)
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
            settle()
            assertEquals(1, modernRequests)
            assertTrue(input.active)
        }

        fun frame() {
            Snapshot.sendApplyNotifications()
            recomposer.performFrame(System.nanoTime())
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }

        fun settle() {
            repeat(6) {
                dispatcher.scheduler.runCurrent()
                observer.scheduler.runCurrent()
                frame()
            }
        }

        fun key(key: Key, ctrl: Boolean = false) {
            assertTrue(scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl)))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        }

        fun paste(text: String) {
            clipboard.put(text)
            val before = changes
            key(Key.V, ctrl = true)
            // Compose reads the Transferable on Dispatchers.IO. Await its real
            // onValueChange, pumping coroutines but never advancing a frame.
            runBlocking {
                withTimeout(5_000) {
                    while (changes == before) {
                        dispatcher.scheduler.runCurrent()
                        delay(1)
                    }
                }
            }
        }

        fun preedit() {
            input.receive(Event.TextInput(TextInputPreeditStringData("に", 3, 3), null, null))
            assertEquals("savedに", value.text)
            assertNotNull(value.composition)
        }

        fun leave(availabilityFirst: Boolean = false) {
            if (availabilityFirst) input.available = false else input.focused = false
            input.focused = false
            info.isWindowFocused = false
            input.available = false
        }

        override fun close() {
            scene.close()
            recomposer.close()
            observer.scheduler.runCurrent()
            dispatcher.scheduler.runCurrent()
            surface.close()
            Dispatchers.resetMain()
        }
    }
}
