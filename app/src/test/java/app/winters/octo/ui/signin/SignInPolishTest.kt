package app.winters.octo.ui.signin

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.OctoType
import app.winters.octo.subsonic.FamilyInviteLink
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

// The sign-in page's looks a unit row can read, on a phone's screen:
// Advanced's head set as a group's title, and Scan a family QR code ending
// on the right edge of the button above it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class SignInPolishTest {
    // The switch's sign-in, recorded: no row here signs in.
    private val signIns = GatedSignIns()

    @After
    fun noSignInWasMade() = assertEquals(emptyList<RecordedSignIn>(), signIns.calls.value)

    @get:Rule
    val ui = RobolectricComposeRule()

    private fun open(): SignInViewModel {
        val vm = signInForm(signIns)
        ui.compose.setContent { PageLocals { SignInScreen(onBack = {}, onDone = {}, vm = vm) } }
        ui.compose.waitForIdle()
        return vm
    }

    // Red when Advanced's title is set as a section title (22 bold) again.
    @Test
    fun advancedsTitleIsAGroupTitle() {
        open()
        val node = ui.compose.onNodeWithText(SignInWords.ADVANCED, useUnmergedTree = true).fetchSemanticsNode()
        val laid = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid)
        val style = laid.single().layoutInput.style
        assertEquals(OctoType.formGroup.fontSize, style.fontSize)
        assertEquals(OctoType.formGroup.fontWeight, style.fontWeight)
    }

    // Red when Scan a family QR code is centered under Sign in again.
    @Test
    fun scanAFamilyQrCodeEndsOnTheButtonsRightEdge() {
        open()
        val button = ui.compose.onNode(hasText("Sign in") and hasClickAction()).fetchSemanticsNode()
        val scan = ui.compose.onNode(hasText("Scan a family QR code") and hasClickAction()).fetchSemanticsNode()
        assertEquals(button.positionInRoot.x + button.size.width, scan.positionInRoot.x + scan.size.width, 1f)
    }

    // A family link's other ways, the scanner and the way back to a password
    // sign-in, end on the right edge of the button above them, where Scan a
    // family QR code ends on the plain page. Red when they are centered.
    @Test
    fun aJoinsOtherWaysEndOnTheButtonsRightEdge() {
        val vm = open()
        vm.startJoin(FamilyInviteLink("https://music.example.com", "t1"))
        ui.compose.waitForIdle()
        val button = ui.compose.onNode(hasText("Sign up") and hasClickAction()).fetchSemanticsNode()
        val right = button.positionInRoot.x + button.size.width
        for (words in listOf("Scan the QR code", "Sign in with a password")) {
            val node = ui.compose.onNode(hasText(words) and hasClickAction()).fetchSemanticsNode()
            assertEquals(words, right, node.positionInRoot.x + node.size.width, 1f)
        }
    }

    // An invite's note names its server as people read it, with no slash at
    // its end. Red with "music.example.com/.".
    @Test
    fun anInvitesNoteNamesItsServerWithoutASlash() {
        val vm = open()
        vm.startJoin(FamilyInviteLink("https://music.example.com/", "t1"))
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("You're invited to music.example.com. Choose your name and a password. Your login then works in Octo and any other music app.").assertExists()
    }
}
