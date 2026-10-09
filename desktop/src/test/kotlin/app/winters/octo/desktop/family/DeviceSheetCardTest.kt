package app.winters.octo.desktop.family

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyLinkChoices
import app.winters.octo.ui.family.DeviceSheet
import app.winters.octo.ui.family.DeviceSheetView
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

// The Add a device and invite popups as drawn: what each copy button
// copies, the switch between the anywhere and home links, the app password
// view's rows and steps, and the invite with no code row.
@OptIn(ExperimentalComposeUiApi::class)
class DeviceSheetCardTest {
    private val anywhere = "https://music.example.com/family/join#u=alex&c=482913"
    private val home = "http://192.168.1.20:4533/family/join#u=alex&c=482913"
    private val now = Instant.parse("2026-10-20T18:00:00Z")
    private val code = FamilyDeviceAdded(
        deviceId = "d_1", kind = FamilyDeviceKind.OctoApp, pairCode = "482913", expires = "2026-10-20T18:09:42Z",
        server = "https://music.example.com", username = "alex", links = FamilyLinkChoices(anywhere, home),
    )
    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var sheet by mutableStateOf(DeviceSheet(id = 1, code = code))
    private var invite by mutableStateOf<InviteSheet?>(null)
    private var owner = false
    private var scene: ImageComposeScene? = null

    // Draws the card as the dialog holds it: `width` wide and at most
    // `height` tall, the rest scrolling.
    private fun draw(width: Int = 640, height: Int = 1000) {
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width, height, Density(1f)) {
                ProvideWindowLook(reduceMotion = true) {
                    Column(Modifier.width(width.dp).heightIn(max = height.dp)) {
                        val shown = invite
                        val actions = SheetActions(openPage = { opened += it }, open = { opened += it }, owner = owner)
                        if (shown != null) InviteCard(shown, "https://music.example.com", {}, actions, copied::add)
                        else DeviceSheetCard(sheet, "https://fallback.example.com", "fallback", "Alex", actions, copied::add, now = { now })
                    }
                }
            }
        }
        render()
    }

    private fun tagged(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun render(frames: Int = 4) = repeat(frames) {
        SwingUtilities.invokeAndWait { scene!!.render((System.nanoTime())).close() }
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

    private fun find(words: String): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(words) }

    private fun has(words: String) = nodes().any { it.words().contains(words) }

    private fun click(words: String) {
        fun SemanticsNode.holds(): Boolean = words() == words || children.any { it.holds() }
        val node = nodes().last { it.config.getOrNull(SemanticsActions.OnClick) != null && it.holds() }
        SwingUtilities.invokeAndWait { node.config[SemanticsActions.OnClick].action!!.invoke() }
        render()
    }

    @Test
    fun theCountdownAndTheCopyButtonsCopyTheRightValues() {
        draw()
        assertTrue(has("Works once · expires in 9:42"))
        assertTrue(has("482 913"))
        click("Copy code")
        click("Copy link")
        assertEquals(listOf("482913", anywhere), copied)
        assertTrue(has("Copied"))
        // A code past its time is on its way to being replaced.
        sheet = sheet.copy(code = code.copy(expires = "2026-10-20T17:59:00Z"))
        render()
        assertTrue(has("Works once · getting a new code"))
    }

    @Test
    fun theLinkOpensWhenClicked() {
        draw()
        assertTrue(has("music.example.com/family/join"))
        val link = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(anywhere) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        SwingUtilities.invokeAndWait { link.config[SemanticsActions.OnClick].action!!.invoke() }
        assertEquals(listOf(anywhere), opened)
    }

    @Test
    fun theSwitchSwapsTheQrCodeAndLinkButKeepsTheCode() {
        draw()
        assertNotNull("anywhere first", find(anywhere))
        click("At home")
        assertNull(find(anywhere))
        assertNotNull(find(home))
        assertTrue(has("Uses your home network (192.168.1.20:4533)"))
        assertTrue(has("for alex on 192.168.1.20:4533"))
        assertTrue(has("482 913"))
        click("Copy link")
        assertEquals(listOf(home), copied)
    }

    @Test
    fun withoutAnOutsideAddressAnywhereSaysWhatToDo() {
        sheet = DeviceSheet(id = 2, code = code.copy(links = FamilyLinkChoices(null, home), anywhereAvailable = false))
        owner = true
        draw()
        // Home comes first, as anywhere can't be had yet.
        assertNotNull(find(home))
        click("Anywhere")
        assertTrue(has("Set your outside address first"))
        click("Open the Status page")
        // The dashboard of the server this app is signed in to.
        assertEquals(listOf("https://fallback.example.com/admin/#status"), opened)
    }

    @Test
    fun othersAreToldToAskTheOwner() {
        sheet = DeviceSheet(id = 2, code = code.copy(links = FamilyLinkChoices(null, home), anywhereAvailable = false))
        draw()
        click("Anywhere")
        assertTrue(has("Ask your family owner to set an outside address"))
        assertFalse(has("Open the Status page"))
    }

    @Test
    fun theAppPasswordViewShowsItsRowsAndSteps() {
        val password = FamilyDeviceAdded(deviceId = "p_1", kind = FamilyDeviceKind.SubsonicApp, appPassword = "ABCDEFGHJKMNPQRS", server = "https://music.example.com", username = "alex")
        sheet = DeviceSheet(id = 3, code = code, password = password, view = DeviceSheetView.OtherApps)
        draw()
        assertTrue(has("Add Symfonium or another app"))
        assertTrue(has("ABCD-EFGH-JKMN-PQRS"))
        assertTrue(has("Shown once"))
        for (app in listOf("Symfonium", "Feishin", "Amperfy", "Substreamer", "Tempo")) assertTrue(app, has(app))
        click("Copy server")
        click("Copy username")
        click("Copy app password")
        assertEquals(listOf("https://music.example.com", "alex", "ABCDEFGHJKMNPQRS"), copied)
    }

    // A real click, the pointer left resting where it pressed.
    private fun press(words: String) {
        val node = nodes().first { it.words() == words }
        val at = node.boundsInRoot.center
        listOf(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.input.pointer.PointerEventType.Release).forEach { type ->
            SwingUtilities.invokeAndWait {
                scene!!.sendPointerEvent(type, at, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = type == androidx.compose.ui.input.pointer.PointerEventType.Press), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene!!.render().close()
            }
        }
        render()
    }

    @Test
    fun switchingToOtherAppsAndBackRedraws() {
        draw()
        press("At home")
        assertNotNull(find(home))
        val password = FamilyDeviceAdded(deviceId = "p_1", kind = FamilyDeviceKind.SubsonicApp, appPassword = "ABCDEFGHJKMNPQRS", server = "https://music.example.com", username = "alex")
        sheet = sheet.copy(view = DeviceSheetView.OtherApps, loading = true)
        render()
        sheet = sheet.copy(password = password, loading = false)
        render()
        assertTrue(has("ABCD-EFGH-JKMN-PQRS"))
        sheet = sheet.copy(view = DeviceSheetView.Code)
        render()
        assertTrue(has("482 913"))
    }

    @Test
    fun theInviteHasNoCodeRow() {
        invite = InviteSheet("Sam", "sam", "https://music.example.com/family/join#invite=t9")
        draw()
        assertTrue(has("Invite Sam"))
        assertTrue(has("Sam picks a password, then adds their devices."))
        assertFalse(has("Code"))
        assertFalse(has("Copy code"))
        click("Copy link")
        assertEquals(listOf("https://music.example.com/family/join#invite=t9"), copied)
    }

    @Test
    fun theChoicesAreARadioGroupThatSaysWhichIsChosen() {
        draw()
        fun selected(tag: String) = tagged(tag)!!.config.getOrNull(SemanticsProperties.Selected)
        assertEquals(Role.RadioButton, tagged("reach-Anywhere")!!.config.getOrNull(SemanticsProperties.Role))
        assertEquals(true, selected("reach-Anywhere"))
        assertEquals(false, selected("reach-Home"))
        assertTrue(has("Uses https://music.example.com"))
        click("At home")
        assertEquals(false, selected("reach-Anywhere"))
        assertEquals(true, selected("reach-Home"))
    }

    @Test
    fun theChoicesNeverClipAt320dp() {
        draw(width = 320)
        for (tag in listOf("reach-Anywhere-label", "reach-Home-label")) {
            val results = mutableListOf<TextLayoutResult>()
            tagged(tag)!!.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            assertFalse(tag, results.single().hasVisualOverflow)
            assertEquals(tag, 15f, results.single().layoutInput.style.fontSize.value, 0.01f)
        }
    }

    @Test
    fun inASmallWindowDoneStaysInSightAndTheCueBringsTheCode() {
        draw(width = 640, height = 420)
        val done = tagged(CARD_DONE_TAG)!!.boundsInRoot
        assertTrue("Done in sight: $done", done.bottom <= 420f)
        assertNotNull("the cue shows", tagged(CARD_MORE_TAG))
        click("More below")
        settle()
        val code = tagged(CARD_CODE_TAG)!!.boundsInRoot
        assertTrue("the code in sight above the footer: $code, $done", code.bottom <= tagged(CARD_DONE_TAG)!!.boundsInRoot.top)
        // At the bottom the cue is gone.
        assertNull(tagged(CARD_MORE_TAG))
    }

    @Test
    fun inAFullSizeWindowThereIsNoCue() {
        draw()
        settle()
        assertNull(tagged(CARD_MORE_TAG))
    }

    @Test
    fun theStillThereStateOffersANewCode() {
        sheet = DeviceSheet(id = 4, code = code, stale = true)
        draw()
        assertTrue(has("Still there?"))
        assertTrue(has("Make a new code"))
    }
}
