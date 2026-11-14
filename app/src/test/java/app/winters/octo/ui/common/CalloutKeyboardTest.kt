package app.winters.octo.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The callout out of touch mode (a hardware keyboard, or a mouse, which can
// still raise the on-screen keyboard): it closes the keyboard by itself and
// leaves focus where it is, since clearing focus here would hand it to the
// first field and scroll there. Robolectric's default graphics start out of
// touch mode, which the row checks first.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalloutKeyboardTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    private val page = CalloutPage()

    // Closes the keyboard by moving its edge down, as the platform's does.
    private class FakeKeyboard(private val page: CalloutPage) : SoftwareKeyboardController {
        var hides = 0

        override fun show() = Unit

        override fun hide() {
            hides++
            page.ime = 0
        }
    }

    // Red when the effect clears focus out of touch mode (the address's focus
    // changes as focus comes back to it), or never closes the keyboard (the
    // scroll would wait for ever).
    @Test
    fun outOfTouchModeTheKeyboardClosesAndFocusStays() {
        val keyboard = FakeKeyboard(page)
        ui.compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { page.Draw() }
        }
        ui.compose.waitForIdle()
        assertEquals(InputMode.Keyboard, page.modes!!.inputMode)
        ui.compose.runOnIdle { page.nameFocus.requestFocus() }
        val name = ui.compose.onNode(hasContentDescription(NAME) and hasSetTextAction())
        name.assertIsFocused()
        page.ime = 300
        val changes = page.addressChanges
        page.pending = true
        ui.compose.waitForIdle()
        assertEquals(1, keyboard.hides)
        name.assertIsFocused()
        assertEquals(changes, page.addressChanges)
        assertEquals(page.topInset.toFloat(), ui.compose.runOnIdle { page.groupTop }, 1f)
        assertFalse(page.pending)
    }
}
