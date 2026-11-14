package app.winters.octo.ui.signin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.winters.octo.connection.SignInWords
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Header rows out of touch mode (a hardware keyboard, or a mouse, which
// never enters touch mode), against the real Advanced. Android cannot leave
// nothing focused here, so a Remove of the row that holds focus hands it to
// Add header, and a Remove elsewhere leaves it where it is. Only focus is
// asserted: every wrong outcome lands on the page's first field instead.
// Robolectric's default graphics start out of touch mode, which each row
// checks first.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvancedKeyboardTest {
    // The switch's sign-in, recorded: no row here signs in, and a call made
    // inside the view model's work would never reach JUnit by itself.
    private val signIns = GatedSignIns()

    @After
    fun noSignInWasMade() = assertEquals(emptyList<RecordedSignIn>(), signIns.calls.value)

    @get:Rule
    val ui = RobolectricComposeRule()

    private lateinit var vm: SignInViewModel
    private var modes: InputModeManager? = null

    private fun open(vararg names: String) {
        vm = signInForm(signIns)
        for (name in names) {
            val id = vm.addHeader()
            vm.setHeaderName(id, name)
            vm.setHeaderValue(id, name.lowercase())
        }
        ui.compose.setContent {
            PageLocals {
                modes = LocalInputModeManager.current
                Column(Modifier.verticalScroll(rememberScrollState())) { Advanced(vm) }
            }
        }
        ui.compose.waitForIdle()
        assertEquals(InputMode.Keyboard, modes!!.inputMode)
    }

    private fun values() = ui.compose.onAllNodesWithContentDescription(SignInWords.HEADER_VALUE)

    private fun remove(name: String) = ui.compose.onNodeWithContentDescription(SignInWords.removeHeader(name))

    private fun addHeader() = ui.compose.onNodeWithText(SignInWords.ADD_HEADER)

    private fun focused(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    // Red when a keyboard Remove of the row that holds focus leaves focus to
    // the platform.
    @Test
    fun tabToARowsRemoveAndEnterFocusesAddHeader() {
        open("A", "B", "C")
        values()[1].performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        repeat(6) {
            if (!focused(remove("B"))) {
                ui.compose.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
                ui.compose.waitForIdle()
            }
        }
        remove("B").assertIsFocused()
        remove("B").performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
        addHeader().assertIsFocused()
    }

    // Red when a mouse click on Remove of the row being typed in leaves
    // focus to the platform.
    @Test
    fun aClickOnTheFocusedRowsRemoveFocusesAddHeader() {
        open("A", "B", "C")
        values()[1].performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        remove("B").performScrollTo().performMouseInput { click() }
        ui.compose.waitForIdle()
        addHeader().assertIsFocused()
    }

    // Red when a Remove elsewhere moves focus off the field being typed in.
    @Test
    fun aClickOnAnotherRowsRemoveKeepsFocusWhereItIs() {
        open("A", "B", "C")
        values()[0].performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        remove("C").performScrollTo().performMouseInput { click() }
        ui.compose.waitForIdle()
        values()[0].assertIsFocused()
    }
}
