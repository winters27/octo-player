// KDT's event constructors are Kotlin-internal; these are incoming wire fixtures.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package app.winters.octo.wayland

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeOptions
import org.jetbrains.desktop.linux.Event
import org.jetbrains.desktop.linux.TextInputPreeditStringData
import org.jetbrains.desktop.linux.TextInputCommitStringData
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextInputTest {
    @Test fun emptyDoneDoesNotUndoPendingKeyboardSelectionOrClipboardValue() {
        val old = TextFieldValue("target", TextRange(6))
        for (changed in listOf(old.copy(selection = TextRange(0, 6)), TextFieldValue("pasted", TextRange(6)))) {
            val editor = EditProcessor().apply { reset(old, null) }
            var value = old
            val input = WaylandTextInput({}, {}, {}, { 1f })
            input.startInput(old, ImeOptions.Default, { commands -> value = editor.apply(commands) }, {})
            input.available = true
            input.focused = true
            // Compose's key/selection manager calls onValueChange directly;
            // EditProcessor.reset and platform updateState follow on recomposition.
            value = changed
            input.receive(Event.TextInput(null, null, null))
            assertEquals(changed, value)
            editor.reset(value, null)
            input.updateState(old, value)
        }
    }

    @Test fun emptyDoneStillRemovesAnOutstandingPreedit() {
        val editor = EditProcessor().apply { reset(TextFieldValue("ab", TextRange(1)), null) }
        val input = WaylandTextInput({}, {}, {}, { 1f })
        input.startInput(editor.toTextFieldValue(), ImeOptions.Default, { commands ->
            input.updateState(editor.toTextFieldValue(), editor.apply(commands))
        }, {})
        input.available = true
        input.focused = true
        input.receive(Event.TextInput(TextInputPreeditStringData("に", 3, 3), null, null))
        input.receive(Event.TextInput(null, null, null))
        assertEquals("ab", editor.toTextFieldValue().text)
        assertNull(editor.toTextFieldValue().composition)
    }

    @Test fun windowAndProtocolLeaveCancelPreeditOnceWithoutDeletingCommittedText() {
        for (availabilityFirst in listOf(false, true)) {
            val editor = EditProcessor().apply { reset(TextFieldValue("ab", TextRange(1)), null) }
            var disables = 0
            var edits = 0
            val input = WaylandTextInput({}, {}, { disables++ }, { 1f })
            input.startInput(editor.toTextFieldValue(), ImeOptions.Default, { commands ->
                edits++
                input.updateState(editor.toTextFieldValue(), editor.apply(commands))
            }, {})
            input.available = true
            input.focused = true
            input.receive(Event.TextInput(TextInputPreeditStringData("に", 3, 3), null, null))
            assertEquals("aにb", editor.toTextFieldValue().text)
            if (availabilityFirst) input.available = false else input.focused = false
            input.available = false
            input.focused = false
            assertEquals("ab", editor.toTextFieldValue().text)
            assertNull(editor.toTextFieldValue().composition)
            assertEquals(TextRange(1), editor.toTextFieldValue().selection)
            assertEquals(2, edits)
            assertEquals(1, disables)
            input.available = true
            input.focused = true
            input.type("x")
            assertEquals("axb", editor.toTextFieldValue().text)
        }
    }

    @Test fun commitImmediatelyBeforeLeaveSurvivesEvenBeforeValuePublication() {
        val editor = EditProcessor().apply { reset(TextFieldValue("ab", TextRange(1)), null) }
        val input = WaylandTextInput({}, {}, {}, { 1f })
        var publish = true
        input.startInput(editor.toTextFieldValue(), ImeOptions.Default, { commands ->
            val old = editor.toTextFieldValue()
            val changed = editor.apply(commands)
            if (publish) input.updateState(old, changed)
        }, {})
        input.available = true
        input.focused = true
        input.receive(Event.TextInput(TextInputPreeditStringData("に", 3, 3), null, null))
        publish = false
        input.receive(Event.TextInput(null, TextInputCommitStringData("日本"), null))
        input.focused = false
        input.available = false
        assertEquals("a日本b", editor.toTextFieldValue().text)
        assertNull(editor.toTextFieldValue().composition)
    }

    @Test fun commitsAndReplacesPreeditInOneBatchWithUtf8Cursor() {
        val editor = EditProcessor().apply { reset(TextFieldValue("ab", TextRange(1)), null) }
        fun input(preedit: Preedit?, commit: String? = null): TextFieldValue =
            editor.apply(imeCommands(editor.toTextFieldValue(), preedit, commit, null))
        var value = input(Preedit("に", 3, 3))
        assertEquals("aにb", value.text)
        assertEquals(TextRange(1, 2), value.composition)
        value = input(Preedit("語🌊", 3, 7), "日本")
        assertEquals("a日本語🌊b", value.text)
        assertEquals(TextRange(3, 6), value.composition)
        assertEquals(TextRange(4, 6), value.selection)
        value = input(null, "語")
        assertEquals("a日本語b", value.text)
        assertNull(value.composition)
        assertEquals(TextRange(4), value.selection)
    }

    @Test fun cancelledPreeditDoesNotCommitIt() {
        val editor = EditProcessor().apply { reset(TextFieldValue("a🌊z", TextRange(1, 3)), null) }
        var value = editor.apply(imeCommands(editor.toTextFieldValue(), Preedit("に", 3, 3), null, null))
        assertEquals("aにz", value.text)
        value = editor.apply(imeCommands(value, Preedit("", 0, 0), null, null))
        assertEquals("az", value.text)
        assertEquals(TextRange(1), value.selection)
        assertNull(value.composition)
    }

    @Test fun deletionUsesBytesAndDoesNotSplitSupplementaryCharacters() {
        val value = TextFieldValue("A🌊Б中Z", TextRange(3))
        val editor = EditProcessor().apply { reset(value, null) }
        val result = editor.apply(imeCommands(value, Preedit("語", 3, 3), "x", 4L to 5L))
        assertEquals("Ax語Z", result.text)
        assertEquals(TextRange(2, 3), result.composition)
        assertEquals(TextRange(3), result.selection)
        // An invalid byte boundary must never delete half a surrogate pair.
        editor.reset(value, null)
        assertEquals(value.text, editor.apply(imeCommands(value, null, null, 3L to 1L)).text)
    }

    @Test fun offsetsClampToWholeCodepoints() {
        val text = "A🌊Б"
        assertEquals(listOf(0, 1, 1, 1, 1, 3, 3, 4, 4), (0..8).map { utf8Offset(text, it) })
        assertEquals(0, utf8Offset(text, -1))
    }

    @Test fun surroundingTextIsBoundedAndPreservesReversedSelection() {
        val text = "🌊Б".repeat(2_000)
        val result = surroundingText(TextFieldValue(text, TextRange(3_006, 3_000)))
        assertTrue(result.text.toByteArray().size <= 3_500)
        assertEquals(result.text, result.text.toByteArray().decodeToString(throwOnInvalidSequence = true))
        assertEquals("🌊Б🌊Б", result.text.substring(result.selection.min, result.selection.max))
        assertTrue(result.selection.reversed)
        assertEquals("", surroundingText(TextFieldValue(text, TextRange(0, text.length))).text)
    }
}
