package app.winters.octo.desktop.family

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import dev.chrisbanes.haze.rememberHazeState
import app.winters.octo.design.PopupLayer
import app.winters.octo.design.PopupHost
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.subsonic.FamilyLinkChoices
import app.winters.octo.subsonic.FamilySignInPending
import app.winters.octo.subsonic.FamilySignInStart
import app.winters.octo.ui.family.HandOverSheet
import app.winters.octo.ui.family.InviteSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.swing.SwingUtilities

// The family dialogs as the app shows them, in the real dialog host over a
// window: the invite (no code row, a link to copy),
// "Sign in on another device" (the Anywhere / At home choice as a radio
// group, words never clipped, a footer in sight in a small window) and the
// question when a device scans it.
@OptIn(ExperimentalComposeUiApi::class)
class FamilyDialogsTest {
    private val now = Instant.parse("2026-10-20T18:00:00Z")
    private val key = "a-key_of-43-characters-made-for-this-test-0"
    private val start = FamilySignInStart(
        token = "tok_1",
        expires = "2026-10-20T18:01:42Z",
        links = FamilyLinkChoices("https://music.example.com", "http://192.168.1.20:4533"),
    )
    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val decided = mutableListOf<String>()
    private var handOver by mutableStateOf(HandOverSheet(id = 1, start = start, key = key))
    private var invite by mutableStateOf<InviteSheet?>(null)
    private var asking by mutableStateOf<FamilySignInPending?>(null)
    private var owner = false
    private var scene: ImageComposeScene? = null

    private val host = PopupHost()

    // Opens the dialog in the window's dialog host, the way the app opens
    // it, in a window `width` by `height`.
    private fun draw(width: Int = 1000, height: Int = 1000) {
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width, height, Density(1f)) {
                ProvideWindowLook(reduceMotion = true) {
                    Box(Modifier.fillMaxSize()) { PopupLayer(host, rememberHazeState()) }
                }
            }
            host.showFamilyDialog { close ->
                val actions = SheetActions(openPage = { opened += it }, open = { opened += it }, owner = owner, done = close)
                val shownInvite = invite
                val shownAsking = asking
                when {
                    shownInvite != null -> InviteCard(shownInvite, "https://music.example.com", {}, actions, copied::add)
                    shownAsking != null -> AskingCard(shownAsking, sending = false, allow = { decided += "allow" }, deny = { decided += "deny" })
                    else -> HandOverCard(handOver, awayAllowed = true, server = "https://music.example.com", avatarName = "Alex", actions = actions, copy = copied::add, now = { now })
                }
            }
        }
        render(8)
    }

    private fun render(frames: Int = 4) = repeat(frames) {
        SwingUtilities.invokeAndWait { scene!!.render(System.nanoTime()).close() }
        Thread.sleep(20)
    }

    // Long enough for fades to finish.
    private fun settle() = render(frames = 25)

    @After
    fun close() {
        SwingUtilities.invokeAndWait { scene?.close() }
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        SwingUtilities.invokeAndWait { scene!!.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) } }
        return all
    }

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")

    private fun has(words: String) = nodes().any { it.words().contains(words) }

    private fun tagged(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun linkTo(prefix: String) = nodes().firstOrNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) } }

    private fun click(words: String) {
        fun SemanticsNode.holds(): Boolean = words() == words || children.any { it.holds() }
        val node = nodes().last { it.config.getOrNull(SemanticsActions.OnClick) != null && it.holds() }
        SwingUtilities.invokeAndWait { node.config[SemanticsActions.OnClick].action!!.invoke() }
        render()
    }

    @Test
    fun theSignInCodeShowsItsLinkAndCountdown() {
        draw()
        assertTrue(has("Sign in on another device"))
        assertTrue(has("Works once · expires in 1:42"))
        val anywhere = "https://music.example.com/family/signin#t=tok_1&k=$key"
        assertNotNull("the anywhere link first", linkTo(anywhere))
        click("Copy link")
        assertTrue(copied.single().startsWith(anywhere))
        // The key goes with the link; the home address too.
        assertTrue(copied.single().contains("&h=http%3A%2F%2F192.168.1.20%3A4533"))
        assertTrue(has("Copied"))
    }

    @Test
    fun theChoicesAreARadioGroupThatSaysWhichIsChosen() {
        draw()
        fun selected(tag: String) = tagged(tag)!!.config.getOrNull(SemanticsProperties.Selected)
        assertEquals(Role.RadioButton, tagged("reach-Anywhere")!!.config.getOrNull(SemanticsProperties.Role))
        assertEquals(true, selected("reach-Anywhere"))
        assertEquals(false, selected("reach-Home"))
        assertTrue(has("Where will you use it?"))
        assertTrue(has("Uses https://music.example.com"))
        click("At home")
        assertEquals(false, selected("reach-Anywhere"))
        assertEquals(true, selected("reach-Home"))
        assertNotNull(linkTo("http://192.168.1.20:4533/family/signin#t=tok_1"))
        assertTrue(has("Uses your home network (192.168.1.20:4533)"))
    }

    @Test
    fun theChoicesNeverClipAt320dp() {
        // A window this narrow gives the dialog 300 dp, less than a phone.
        draw(width = 320)
        for (tag in listOf("reach-Anywhere-label", "reach-Home-label")) {
            val results = mutableListOf<TextLayoutResult>()
            tagged(tag)!!.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            assertFalse(tag, results.single().hasVisualOverflow)
            assertEquals(tag, 15f, results.single().layoutInput.style.fontSize.value, 0.01f)
        }
    }

    @Test
    fun inASmallWindowDoneStaysInSightAndTheCueBringsTheRestIntoView() {
        draw(width = 800, height = 420)
        val done = tagged(CARD_DONE_TAG)!!.boundsInRoot
        assertTrue("Done in sight: $done", done.bottom <= 420f)
        assertNotNull("the cue shows", tagged(CARD_MORE_TAG))
        click("More below")
        settle()
        assertNull("at the bottom the cue is gone", tagged(CARD_MORE_TAG))
    }

    @Test
    fun inAFullSizeWindowThereIsNoCue() {
        draw()
        settle()
        assertNull(tagged(CARD_MORE_TAG))
    }

    @Test
    fun withoutAnOutsideAddressAnywhereSaysWhatToDo() {
        handOver = handOver.copy(start = start.copy(links = FamilyLinkChoices(null, "http://192.168.1.20:4533"), anywhereAvailable = false))
        owner = true
        draw()
        assertNotNull("home first", linkTo("http://192.168.1.20:4533/family/signin"))
        click("Anywhere")
        assertTrue(has("Set your outside address first"))
        click("Open the Status page")
        assertEquals(listOf("https://music.example.com/admin/#status"), opened)
    }

    @Test
    fun aDeviceAskingIsAllowedOrDenied() {
        asking = FamilySignInPending("r_1", "Pixel 9", "Android")
        draw()
        assertTrue(has("Pixel 9"))
        assertTrue(has("Android"))
        click("Deny")
        click("Allow")
        assertEquals(listOf("deny", "allow"), decided)
    }

    @Test
    fun afterTenMinutesItAsksAndAfterSendingItSaysSo() {
        handOver = handOver.copy(stale = true)
        draw()
        assertTrue(has("Still there?"))
        assertTrue(has("Make a new code"))
        handOver = handOver.copy(stale = false, done = "Sent to Pixel 9. It's signing in now.")
        render()
        assertTrue(has("Sent to Pixel 9. It's signing in now."))
    }

    @Test
    fun theInviteHasNoCodeRow() {
        invite = InviteSheet("Sam", "sam", "https://music.example.com/family/join#invite=t9")
        draw()
        assertTrue(has("Invite Sam"))
        assertTrue(has("Sam picks a password, then signs in on any app."))
        assertFalse(has("Code"))
        assertFalse(has("Copy code"))
        click("Copy link")
        assertEquals(listOf("https://music.example.com/family/join#invite=t9"), copied)
    }

    @Test
    fun aResetLinkIsTitledForWhatItIs() {
        invite = InviteSheet("Sam", "sam", "https://music.example.com/family/join#invite=r1", reset = true)
        draw()
        assertTrue(has("New sign-in link for Sam"))
    }
}
