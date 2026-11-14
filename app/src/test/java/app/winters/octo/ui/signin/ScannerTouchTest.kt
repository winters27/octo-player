package app.winters.octo.ui.signin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.winters.octo.connection.SignInWords
import app.winters.octo.testing.DeviceFocusRule
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The camera over the whole sign-in page, in touch mode: nothing stays
// focused behind it, presses never reach the page, and a finger's tap on
// Cancel closes it even with a small movement. The page under the camera has
// cleared semantics, so its nodes are found in the unmerged tree. Every row
// first checks the page is in touch mode. It runs on a phone's screen: on
// Robolectric's smaller default one the camera's Cancel sits over the
// address.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class ScannerTouchTest {
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

    private var modes: InputModeManager? = null
    private var backs = 0

    private fun open(vm: SignInViewModel) {
        ui.compose.setContent {
            PageLocals {
                modes = LocalInputModeManager.current
                SignInScreen(onDone = {}, onBack ={ backs++ }, vm = vm)
            }
        }
        ui.compose.waitForIdle()
        assertEquals(InputMode.Touch, modes!!.inputMode)
    }

    private fun address() = ui.compose.onNode(hasContentDescription(SignInWords.SERVER_ADDRESS) and hasSetTextAction(), useUnmergedTree = true)

    private fun scan(vm: SignInViewModel) {
        ui.compose.runOnIdle {
            vm.startJoin()
            vm.scanning = true
        }
        ui.compose.waitForIdle()
    }

    // Red when the camera opens with the address still focused behind it.
    @Test
    fun openingTheCameraLeavesNothingFocused() {
        val vm = signInForm(signIns)
        open(vm)
        address().performClick()
        ui.compose.waitForIdle()
        address().assertIsFocused()
        scan(vm)
        ui.compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(0)
    }

    // Red when presses pass through the camera to the page under it. After
    // each press the camera is still open, so a press on its own Cancel
    // cannot pass for one the page never got.
    @Test
    fun pressesNeverReachThePageUnderTheCamera() {
        val vm = signInForm(signIns)
        open(vm)
        val field = address().fetchSemanticsNode().boundsInRoot.center
        val back = ui.compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.center
        scan(vm)
        ui.compose.onRoot().performTouchInput { click(field) }
        ui.compose.waitForIdle()
        assertTrue(vm.scanning)
        address().assertIsNotFocused()
        assertTrue(vm.joining)
        ui.compose.onRoot().performTouchInput { click(back) }
        ui.compose.waitForIdle()
        assertTrue(vm.scanning)
        assertEquals(0, backs)
    }

    // Red when the camera takes every move (a finger's tap with the usual
    // small movement would not click Cancel).
    @Test
    fun aFingersTapWithASmallMoveClosesTheCamera() {
        val vm = signInForm(signIns)
        open(vm)
        scan(vm)
        ui.compose.onNodeWithText("Cancel").performTouchInput {
            down(center)
            moveBy(Offset(2f, 0f))
            up()
        }
        ui.compose.waitForIdle()
        assertFalse(vm.scanning)
    }

    // Red when Cancel hands focus to the opening button in touch mode.
    @Test
    fun aTapOnCancelLeavesNothingFocused() {
        val vm = signInForm(signIns)
        open(vm)
        scan(vm)
        ui.compose.onNodeWithText("Cancel").performClick()
        ui.compose.waitForIdle()
        assertFalse(vm.scanning)
        ui.compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(0)
    }
}
