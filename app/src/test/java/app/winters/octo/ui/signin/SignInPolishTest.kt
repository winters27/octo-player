package app.winters.octo.ui.signin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.OctoType
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import app.winters.octo.ui.common.PopupQuestion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The sign-in page's looks a unit row can read, on a phone's screen:
// Advanced's head set as a group's title, Scan a family QR code ending on
// the right edge of the button above it, Advanced's groups coming into view
// as its head opens it, and the trust question's answers whole and balanced
// at larger text. The pop-up a question floats in cannot be built in a unit
// test (no merged resources), so the question is drawn by itself, in the
// card's width.
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

    // The page at 130% text, the size where Advanced's head sits under the
    // fold of a phone's screen.
    private fun openAtLargerText(): SignInViewModel {
        val vm = signInForm(signIns)
        ui.compose.setContent { PageLocals { AtTextSize(1.3f) { SignInScreen(onBack = {}, onDone = {}, vm = vm) } } }
        ui.compose.waitForIdle()
        return vm
    }

    private fun head() = ui.compose.onNode(hasText(SignInWords.ADVANCED) and hasClickAction())

    // The page's scroll: where its view is in the window, and how far it has
    // scrolled.
    private fun view() = ui.compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()

    private fun scrolled() = view().config[SemanticsProperties.VerticalScrollAxisRange]

    // Where the view's soft bottom edge starts.
    private fun foot(): Float {
        val view = view()
        return view.positionInRoot.y + view.size.height - with(ui.compose.density) { 24.dp.toPx() }
    }

    // Advanced's first group's title, once it is drawn.
    private fun firstTitle(): SemanticsNode {
        val titles = ui.compose.onAllNodesWithText(SignInWords.GROUP_GETTING_THERE, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("Advanced's groups are drawn", 1, titles.size)
        return titles.single()
    }

    // Advanced's head at the bottom of the view, at 130% text: a tap on it
    // scrolls the page until the first group's title sits above the soft
    // bottom edge. Red when the tap only turns the chevron: the groups never
    // drawn (their fade read the open flag only where nothing watched it), or
    // drawn wholly under the view's foot.
    @Test
    fun openingAdvancedFromItsHeadBringsItsFirstGroupsTitleIntoView() {
        val vm = openAtLargerText()
        head().performScrollTo()
        ui.compose.waitForIdle()
        val view = view()
        val bottom = view.positionInRoot.y + view.size.height
        val shut = head().fetchSemanticsNode()
        assertEquals("the head sits at the bottom of the view", bottom, shut.positionInRoot.y + shut.size.height, 1f)
        val before = scrolled().value()
        head().performClick()
        ui.compose.waitForIdle()
        assertTrue(vm.advancedOpen)
        val title = firstTitle()
        assertTrue("the page scrolled from $before", scrolled().value() > before)
        assertTrue("the title's top at ${title.positionInRoot.y}", title.positionInRoot.y >= view.positionInRoot.y)
        assertTrue("the title's bottom at ${title.positionInRoot.y + title.size.height} over the foot at ${foot()}", title.positionInRoot.y + title.size.height <= foot())
    }

    // On a screen with room for Advanced's first group under its head, a tap
    // on the head draws the groups and leaves the page where it is, though
    // it could scroll. Red when the tap draws no groups (their fade read the
    // open flag only where nothing watched it); a guard against a reveal
    // that scrolls a page that already shows the title.
    @Config(qualifiers = "w360dp-h1280dp-mdpi")
    @Test
    fun openingAdvancedWithRoomForItsFirstGroupLeavesThePageWhereItIs() {
        val vm = openAtLargerText()
        assertEquals(0f, scrolled().value(), 0.5f)
        head().performClick()
        ui.compose.waitForIdle()
        assertTrue(vm.advancedOpen)
        val title = firstTitle()
        assertTrue("the title's bottom at ${title.positionInRoot.y + title.size.height} over the foot at ${foot()}", title.positionInRoot.y + title.size.height <= foot())
        assertTrue("the open page can scroll", scrolled().maxValue() > 0f)
        assertEquals("the page stayed where it was", 0f, scrolled().value(), 0.5f)
    }

    // The text size a question is drawn at.
    private var scale by mutableStateOf(1f)

    // A question as the trust question draws it, in the card a 360 dp phone
    // gives it (the pop-up's 12 dp margins off each side), with `confirm`
    // as its lit answer.
    private fun ask(confirm: String) {
        ui.compose.setContent {
            PageLocals {
                AtTextSize(scale) {
                    Box(Modifier.width(PhoneCard)) {
                        PopupQuestion(
                            SignInWords.TRUST_TITLE,
                            SignInWords.trustDetail("phone"),
                            confirm,
                            onConfirm = {},
                            onCancel = {},
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            width = 380.dp,
                        )
                    }
                }
            }
        }
        ui.compose.waitForIdle()
    }

    // An answer button as drawn: its bounds, and whether its words show
    // whole.
    private class Answer(val left: Float, val top: Float, val right: Float, val bottom: Float, val whole: Boolean) {
        val width get() = right - left
    }

    private fun answer(words: String): Answer {
        val button = ui.compose.onNode(hasText(words) and hasClickAction()).fetchSemanticsNode()
        val text = ui.compose.onNode(hasText(words), useUnmergedTree = true).fetchSemanticsNode()
        val laid = mutableListOf<TextLayoutResult>()
        text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid)
        val layout = laid.single()
        val at = button.positionInRoot
        // Cut words end in an ellipsis on their one line. (The layout a
        // reader gets is set in the room the words were offered, so in a
        // button wider than its words it reads as overflowing that room.)
        val whole = !layout.isLineEllipsized(0) && !layout.didOverflowHeight && layout.getLineEnd(0, visibleEnd = true) == words.length
        return Answer(at.x, at.y, at.x + button.size.width, at.y + button.size.height, whole)
    }

    // Both answers whole, inside the question's sides, clear of each other,
    // and the lit one no narrower than Cancel.
    private fun assertAnswersFit(confirm: String) {
        val label = "$confirm at $scale"
        val lit = answer(confirm)
        val cancel = answer("Cancel")
        val sides = with(ui.compose.density) { 20.dp.toPx() }
        val end = with(ui.compose.density) { PhoneCard.toPx() } - sides
        assertTrue("$label: the lit answer's words are cut", lit.whole)
        assertTrue("$label: Cancel's words are cut", cancel.whole)
        for (one in listOf(lit, cancel)) assertTrue("$label: an answer from ${one.left} to ${one.right} outside $sides to $end", one.left >= sides - 0.5f && one.right <= end + 0.5f)
        assertTrue("$label: the answers overlap", lit.right <= cancel.left || lit.bottom <= cancel.top)
        assertTrue("$label: the lit answer ${lit.width} wide beside Cancel ${cancel.width}", lit.width >= cancel.width - 0.5f)
    }

    // The trust question's answers at the phone's normal text size sit side
    // by side; at 130% on a 360 dp phone the lit one's words leave Cancel too
    // little room, so they stack, both whole. Red when they stay side by side
    // there: Cancel is squeezed to 77 of its 104 and its words are cut.
    @Test
    fun theTrustQuestionsAnswersShowWholeAtLargerTextOnANarrowPhone() {
        ask(SignInWords.TRUST_CONFIRM)
        assertAnswersFit(SignInWords.TRUST_CONFIRM)
        val lit = answer(SignInWords.TRUST_CONFIRM)
        assertEquals("side by side at the normal size", lit.top, answer("Cancel").top, 0.5f)
        scale = 1.3f
        ui.compose.waitForIdle()
        assertAnswersFit(SignInWords.TRUST_CONFIRM)
    }

    // A short lit answer (the trust question's old "Trust") is drawn at least
    // as wide as Cancel beside it, at the normal size and at 130%. Red when
    // it keeps its own width: 79 beside Cancel's 87.
    @Test
    fun aShortLitAnswerIsNeverNarrowerThanCancel() {
        ask("Trust")
        assertAnswersFit("Trust")
        scale = 1.3f
        ui.compose.waitForIdle()
        assertAnswersFit("Trust")
    }
}

// The width of a pop-up's card on a 360 dp wide phone.
private val PhoneCard = 336.dp

// Draws `content` at `scale` times the phone's normal text size.
@Composable
private fun AtTextSize(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}
