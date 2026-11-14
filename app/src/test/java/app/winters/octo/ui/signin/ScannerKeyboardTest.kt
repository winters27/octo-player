package app.winters.octo.ui.signin

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.testing.CountingKeyboard
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import app.winters.octo.ui.family.LocalQrScanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The camera as a modal out of touch mode (a hardware keyboard, or a mouse,
// which never enters touch mode): it takes focus and closes the on-screen
// keyboard as it opens, keeps Tab and the arrows in, hides the page from a
// screen reader, closes on Back, and gives focus a home on every way out:
// the opening button after Cancel or Back, the address after a link it read
// or a deep link's new page. The page under the camera has cleared
// semantics, so its nodes are found in the unmerged tree. Robolectric's
// default graphics start out of touch mode, which each row checks first.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScannerKeyboardTest {
    // The switch's sign-in, recorded: no row here signs in, and a call made
    // inside the view model's work would never reach JUnit by itself.
    private val signIns = GatedSignIns()

    @After
    fun noSignInWasMade() = assertEquals(emptyList<RecordedSignIn>(), signIns.calls.value)

    @get:Rule
    val ui = RobolectricComposeRule()

    private var modes: InputModeManager? = null
    private var backs = 0
    private var dispatcher: OnBackPressedDispatcher? = null
    private val keyboard = CountingKeyboard()
    private val invite = FamilyInviteLink(server = "https://music.example.com", token = "invite-token")

    private fun open(content: @Composable () -> Unit) {
        ui.compose.setContent {
            PageLocals {
                modes = LocalInputModeManager.current
                dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { content() }
            }
        }
        ui.compose.waitForIdle()
        assertEquals(InputMode.Keyboard, modes!!.inputMode)
    }

    private fun page(vm: SignInViewModel) = open { SignInScreen(onDone = {}, onBack ={ backs++ }, vm = vm) }

    private fun address() = ui.compose.onNode(hasContentDescription(SignInWords.SERVER_ADDRESS) and hasSetTextAction(), useUnmergedTree = true)

    private fun cancel() = ui.compose.onNodeWithText("Cancel")

    private fun focused(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    // Tabs from where focus is until `node` has it.
    private fun tabTo(node: SemanticsNodeInteraction) {
        repeat(16) {
            if (!focused(node)) {
                ui.compose.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
                ui.compose.waitForIdle()
            }
        }
        node.assertIsFocused()
    }

    // Opens the camera from "Scan the QR code" with Enter.
    private fun scanAgain() {
        val scan = ui.compose.onNodeWithText("Scan the QR code")
        scan.performScrollTo().requestFocus()
        ui.compose.waitForIdle()
        scan.performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
    }

    // Red when the camera opens with focus behind it on the address, or the
    // on-screen keyboard left up.
    @Test
    fun theCameraTakesFocusAndClosesTheKeyboard() {
        val vm = signInForm(signIns)
        page(vm)
        address().requestFocus()
        ui.compose.waitForIdle()
        ui.compose.runOnIdle {
            vm.startJoin()
            vm.scanning = true
        }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        address().assertIsNotFocused()
        assertEquals(1, keyboard.hides)
    }

    // Red when Enter on the opening button, which leaves the page as the
    // layout changes, leaves focus behind the camera.
    @Test
    fun enterOnScanAFamilyQrCodeFocusesCancel() {
        val vm = signInForm(signIns)
        page(vm)
        address().requestFocus()
        ui.compose.waitForIdle()
        tabTo(ui.compose.onNodeWithText("Scan a family QR code"))
        ui.compose.onNodeWithText("Scan a family QR code").performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
        assertTrue(vm.scanning)
        cancel().assertIsFocused()
    }

    // Red when Cancel leaves focus to the platform (it lands on the address).
    @Test
    fun cancelReturnsFocusToTheButtonThatOpenedIt() {
        val vm = signInForm(signIns)
        vm.startJoin()
        page(vm)
        scanAgain()
        cancel().assertIsFocused()
        cancel().performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
        assertFalse(vm.scanning)
        ui.compose.onNodeWithText("Scan the QR code").assertIsFocused()
    }

    // Red when Tab, Shift+Tab or the arrows move focus to the page behind.
    @Test
    fun focusStaysOnTheCamerasCancel() {
        val vm = signInForm(signIns)
        vm.startJoin()
        page(vm)
        scanAgain()
        cancel().assertIsFocused()
        cancel().performKeyInput { pressKey(Key.Tab) }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        cancel().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        cancel().performKeyInput { pressKey(Key.DirectionUp) }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        cancel().performKeyInput { pressKey(Key.DirectionLeft) }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
    }

    // Red when the page behind the camera stays in a screen reader's reach,
    // or does not come back once it closes.
    @Test
    fun thePageBehindTheCameraIsHiddenFromScreenReaders() {
        val vm = signInForm(signIns)
        vm.startJoin()
        page(vm)
        ui.compose.onNodeWithText("Sign in with a password").assertExists()
        scanAgain()
        ui.compose.onNodeWithText("Sign in with a password").assertDoesNotExist()
        cancel().performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("Sign in with a password").assertExists()
    }

    // Red when Back leaves the page instead of closing the camera, or closes
    // it without a home for focus.
    @Test
    fun backClosesTheCameraAndReturnsFocus() {
        val vm = signInForm(signIns)
        vm.startJoin()
        page(vm)
        scanAgain()
        cancel().assertIsFocused()
        ui.compose.runOnUiThread { dispatcher!!.onBackPressed() }
        ui.compose.waitForIdle()
        assertFalse(vm.scanning)
        assertEquals(0, backs)
        ui.compose.onNodeWithText("Scan the QR code").assertIsFocused()
    }

    // A deep link opens a new page with a new view model: its address takes
    // focus, not the platform's first item (the focusable box above). Red
    // when the guard's `before` is dropped.
    @Test
    fun aDeepLinksNewPageFocusesItsAddress() {
        val first = signInForm(signIns)
        first.startJoin()
        val second = signInForm(signIns)
        var opened by mutableStateOf(false)
        open {
            Column {
                Box(Modifier.size(1.dp).testTag("first").focusable())
                if (opened) SignInScreen(onDone = {}, onBack ={}, vm = second, join = invite, joinOpened = 1) else SignInScreen(onDone = {}, onBack ={}, vm = first)
            }
        }
        ui.compose.runOnIdle { first.scanning = true }
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        opened = true
        ui.compose.waitForIdle()
        address().assertIsFocused()
        ui.compose.onNodeWithTag("first").assertIsNotFocused()
    }

    // A link read by the camera: the address takes focus before the scanner
    // leaves. Red when focus is moved after the link is taken (the scanner
    // has gone, and its focus with it) or not at all.
    @Test
    fun aLinkTheCameraReadsFocusesTheAddress() {
        val vm = signInForm(signIns)
        vm.startJoin()
        open {
            Column {
                Box(Modifier.size(1.dp).testTag("first").focusable())
                CompositionLocalProvider(
                    LocalQrScanner provides { found, _, focus ->
                        Box(Modifier.size(80.dp).focusRequester(focus).clickable { found(invite) }) { BasicText("Found") }
                    },
                ) {
                    SignInScreen(onDone = {}, onBack ={}, vm = vm)
                }
            }
        }
        ui.compose.runOnIdle { vm.scanning = true }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("Found").assertIsFocused()
        ui.compose.onNodeWithText("Found").performKeyInput { pressKey(Key.Enter) }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("Found").assertDoesNotExist()
        assertFalse(vm.scanning)
        address().assertIsFocused()
        ui.compose.onNodeWithTag("first").assertIsNotFocused()
    }

    // A page drawn again (as after a rotation) keeps the camera open with
    // its Cancel focused, and starts its link nothing more. Red when the
    // guard is keyed on the link (the join would start again and close the
    // camera) or the camera's focus is not given back.
    @Test
    fun aPageDrawnAgainKeepsTheCameraAndItsFocus() {
        val vm = signInForm(signIns)
        var shown by mutableStateOf(true)
        open { if (shown) SignInScreen(onDone = {}, onBack ={}, vm = vm, join = invite, joinOpened = 1) }
        assertEquals(invite, vm.invite)
        scanAgain()
        cancel().assertIsFocused()
        shown = false
        ui.compose.waitForIdle()
        shown = true
        ui.compose.waitForIdle()
        cancel().assertIsFocused()
        address().assertIsNotFocused()
        assertTrue(vm.scanning)
    }
}
