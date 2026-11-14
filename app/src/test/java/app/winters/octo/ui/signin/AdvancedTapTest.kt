package app.winters.octo.ui.signin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import app.winters.octo.connection.SignInWords
import app.winters.octo.testing.DeviceFocusRule
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Header rows in touch mode, against the real Advanced: Add header puts
// focus in each new row's name once that row is on screen, and a tap on
// Remove takes its own row with its Show and leaves nothing focused. Every
// row first checks the page is in touch mode. A field with words in it takes
// focus by a request, not a tap: a tap there shows the cursor's handle, a
// popup the unit tests cannot build without the app's resources.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdvancedTapTest {
    // The switch's sign-in, recorded: no row here signs in, and a call made
    // inside the view model's work would never reach JUnit by itself.
    private val signIns = GatedSignIns()

    @After
    fun noSignInWasMade() = assertEquals(emptyList<RecordedSignIn>(), signIns.calls.value)

    @get:Rule
    val ui = RobolectricComposeRule()

    // Clearing focus in touch mode leaves nothing focused, as on the phone.
    @get:Rule
    val focusRule = DeviceFocusRule()

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
        assertEquals(InputMode.Touch, modes!!.inputMode)
    }

    private fun names() = ui.compose.onAllNodesWithContentDescription(SignInWords.HEADER_NAME)

    private fun values() = ui.compose.onAllNodesWithContentDescription(SignInWords.HEADER_VALUE)

    private fun remove(name: String) = ui.compose.onNodeWithContentDescription(SignInWords.removeHeader(name))

    private fun addHeader() = ui.compose.onNodeWithText(SignInWords.ADD_HEADER)

    // Red when Add header asks for focus in the same action that adds the
    // row (the new row's field does not exist yet), or not at all.
    @Test
    fun addHeaderFocusesEachNewRowsName() {
        open()
        addHeader().performScrollTo().performClick()
        ui.compose.waitForIdle()
        names()[0].assertIsFocused()
        addHeader().performScrollTo().performClick()
        ui.compose.waitForIdle()
        names()[1].assertIsFocused()
    }

    // Red when the rows are drawn by position (focus stays on the second
    // row, C's now, and what is typed next lands in C's value) or a tap on
    // Remove leaves the field focused.
    @Test
    fun aTapOnRemoveLeavesNothingFocusedAndTheNextRowUntouched() {
        open("A", "B", "C")
        values()[1].performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        values()[1].assertIsFocused()
        remove("B").performScrollTo().performClick()
        ui.compose.waitForIdle()
        ui.compose.onAllNodes(isFocused()).assertCountEquals(0)
        assertEquals(listOf("A", "C"), vm.headers.map { it.name })
        assertEquals("c", vm.headers[1].value)
    }

    // Red when Show is remembered by position: C's value would come out
    // shown.
    @Test
    fun showOnARemovedRowDoesNotMoveToTheNext() {
        open("A", "B", "C")
        ui.compose.onAllNodesWithText("Show")[1].performScrollTo().performClick()
        ui.compose.waitForIdle()
        ui.compose.onAllNodesWithText("Hide").assertCountEquals(1)
        remove("B").performScrollTo().performClick()
        ui.compose.waitForIdle()
        ui.compose.onAllNodesWithText("Hide").assertCountEquals(0)
        ui.compose.onAllNodesWithText("Show").assertCountEquals(2)
    }

    // Red when a tap on another row's Remove leaves the field typed in
    // focused.
    @Test
    fun aTapOnAnotherRowsRemoveLeavesNothingFocused() {
        open("A", "B", "C")
        values()[0].performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        values()[0].assertIsFocused()
        remove("C").performScrollTo().performClick()
        ui.compose.waitForIdle()
        ui.compose.onAllNodes(isFocused()).assertCountEquals(0)
    }
}
