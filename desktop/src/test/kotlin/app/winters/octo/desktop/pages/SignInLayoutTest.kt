package app.winters.octo.desktop.pages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.POPUP_TAG
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.desktop.a11y.name
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

// The sign-in page and its sheets as drawn: Advanced opening moves nothing
// above it, a failure that met a sign-in page brings its group under the
// top (or ends a tall group's last note 8 above the bottom), picking API key
// brings its row up, header rows keep their focus and Show through adds and
// removes, a late answer or a swapped form never shows a secret, and Tab
// never stops on Show.
@OptIn(ExperimentalComposeUiApi::class)
class SignInLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    private fun page(height: Int = 900, serve: (FakeServer) -> Unit = {}) =
        A11yScene(folder.newFolder(), height = height, reduceMotion = true, signedOut = true, serve = serve)

    private fun signedIn(height: Int = 900, serve: (FakeServer) -> Unit = {}) =
        A11yScene(folder.newFolder(), height = height, reduceMotion = true, serve = serve)

    // Draws until `what` holds, for at most 10 s.
    private fun A11yScene.until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what()) {
            check(System.currentTimeMillis() < end) { "waited too long" }
            render(2)
        }
    }

    private fun A11yScene.withText(words: String): List<SemanticsNode> =
        nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == words } == true }

    // The highest node showing these words, on or off screen.
    private fun A11yScene.text(words: String): SemanticsNode = withText(words).minBy { it.positionInRoot.y }

    private fun A11yScene.named(name: String): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") == name }

    // The entry holding `value`, as typed.
    private fun A11yScene.entry(value: String): SemanticsNode =
        nodes().first { it.config.getOrNull(SemanticsProperties.InputText)?.text == value }

    // A value drawn as dots: the words the field shows are not its value.
    // (The field's Password flag is set once, when the field is made, and
    // does not follow Show.)
    private fun SemanticsNode.hidden(): Boolean {
        val value = config.getOrNull(SemanticsProperties.InputText)?.text.orEmpty()
        return value.isNotEmpty() && config.getOrNull(SemanticsProperties.EditableText)?.text != value
    }

    private fun SemanticsNode.focused(): Boolean = config.getOrNull(SemanticsProperties.Focused) == true

    // A node's middle, wherever its scroll has it: bounds in the root are cut
    // to what is on screen, and empty for a node scrolled out of view.
    private fun SemanticsNode.middle(): Float = positionInRoot.y + size.height / 2f

    // The Show (or Hide) button at the end of this entry, on or off screen.
    private fun A11yScene.showIn(entry: SemanticsNode): SemanticsNode =
        nodes().first { it.name() in setOf("Show", "Hide") && abs(it.middle() - entry.middle()) < 12f && it.positionInRoot.x > entry.positionInRoot.x }

    private fun SemanticsNode.inPopup(): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }

    // The lowest node in the open pop-up showing these words (a sheet's
    // button, under a title that may say the same), on or off screen.
    private fun A11yScene.inPopup(words: String): SemanticsNode = withText(words).filter { it.inPopup() }.maxBy { it.positionInRoot.y }

    // A click in the node's middle, once the node is wholly on screen and no
    // glide is left running: one frame on the real clock ends any glide that
    // started on render's clock, which stands at 0.
    private fun A11yScene.click(node: SemanticsNode) {
        if (node.boundsInRoot.height < node.size.height - 0.5f) bringUp(node) else frameAt(System.nanoTime()).close()
        val at = node.boundsInRoot.center
        onUi {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, at)
        }
        render(3)
    }

    private fun A11yScene.type(node: SemanticsNode, text: String) {
        onUi { node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text)) }
        render(2)
    }

    // The scroll a node sits in.
    private fun scrollOf(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }

    // Scrolls so the node is in the middle of its scroll, as far as the
    // scroll goes. ScrollBy glides, and render() draws on a clock that
    // stands at 0, where a glide never ends and its scroll takes the next
    // press for itself; so the glide starts on a frame at 0 and is drawn to
    // its end on the real clock.
    private fun A11yScene.bringUp(node: SemanticsNode) {
        val scroll = scrollOf(node)
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        val by = (node.middle() - scroll.boundsInRoot.center.y).coerceIn(-range.value(), range.maxValue() - range.value())
        val goal = range.value() + by
        onUi { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, by) }
        render(1)
        val end = System.currentTimeMillis() + 10_000
        do {
            check(System.currentTimeMillis() < end) { "waited too long" }
            frameAt(System.nanoTime()).close()
        } while (abs(range.value() - goal) > 0.5f)
    }

    // A group's title `inset` under the top of its scroll, within 1 px, or
    // the scroll at its end with the title in view.
    private fun A11yScene.landed(title: String, inset: Float) {
        val head = text(title)
        val scroll = scrollOf(head)
        val range = scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!
        val offset = head.positionInRoot.y - scroll.boundsInRoot.top
        val atEnd = range.value() >= range.maxValue() - 0.5f && offset >= 0f
        assertTrue("'$title' $offset under the top, scrolled ${range.value()} of ${range.maxValue()}", abs(offset - inset) <= 1f || atEnd)
    }

    private fun A11yScene.scrolled(node: SemanticsNode): Float = scrollOf(node).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.value()

    // A server whose ping a sign-in page answers.
    private val authentik: (FakeServer) -> Unit = { it.respond("ping") { SignInPages.authentik() } }

    private fun A11yScene.fill(address: String) = onUi {
        val form = app.signInForm
        form.typeAddress(address)
        form.username = "winters"
        form.password = "pw"
    }

    // Red when Advanced opening moves what is above it (the card centered
    // again on its new height).
    @Test(timeout = 120_000)
    fun signInStaysPutWhenAdvancedOpens() {
        page(height = 1_200).use { s ->
            s.settle(800).close()
            val before = s.text("Sign in").boundsInRoot.top
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(800).close()
            assertTrue(s.app.signInForm.advancedOpen)
            assertEquals(before, s.text("Sign in").boundsInRoot.top, 1f)
        }
    }

    // Red when the failure is only written, not called out and scrolled to.
    @Test(timeout = 120_000)
    fun aSignInPageFailureOnThePageBringsItsGroupUnderTheTop() {
        page(height = 700, serve = authentik).use { s ->
            s.fill(s.server.address)
            s.settle(600).close()
            assertFalse(s.app.signInForm.advancedOpen)
            s.click(s.text("Sign in"))
            s.until { s.app.signInForm.callout && !s.app.signInForm.calloutPending }
            s.render(4)
            s.landed(SignInWords.GROUP_GETTING_THERE, PageTopInset.value)
            assertTrue(s.scrolled(s.text(SignInWords.GROUP_GETTING_THERE)) > 0f)
            assertTrue(s.withText(GatewayWords.POINTER).isNotEmpty())
            assertTrue(s.withText(GatewayWords.inGroup(s.app.signInForm.result!!.second)).isNotEmpty())
        }
    }

    // The field that had the keyboard when the failure came keeps no claim:
    // focus held before the callout never counts.
    @Test(timeout = 120_000)
    fun thePasswordFocusedWhenTheFailureArrivesStillScrolls() {
        page(height = 700, serve = authentik).use { s ->
            s.fill(s.server.address)
            s.settle(600).close()
            s.click(s.named(SignInWords.PASSWORD).single())
            assertEquals(SignInWords.PASSWORD, s.focused()?.name())
            s.press(Key.Enter)
            s.until { s.app.signInForm.callout && !s.app.signInForm.calloutPending }
            s.render(4)
            s.landed(SignInWords.GROUP_GETTING_THERE, PageTopInset.value)
        }
    }

    // On a short window the sign-in page group, its two fields and its notes
    // are taller than the view: its last note ends 8 above the bottom.
    @Test(timeout = 120_000)
    fun aTallGroupEndsItsLastNoteEightAboveTheBottom() {
        page(height = 360).use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to GatewayWords.failure(Gateway.Authentik, false)
                form.failed(form.attemptAddress, Gateway.Authentik)
            }
            s.until { !s.app.signInForm.calloutPending }
            s.render(4)
            val last = s.text(SignInWords.HOME_NOTE)
            val scroll = scrollOf(last)
            assertTrue("the group is taller than the view", s.text(SignInWords.GROUP_GETTING_THERE).boundsInRoot.top < scroll.boundsInRoot.top + PageTopInset.value)
            assertEquals(scroll.boundsInRoot.bottom - CalloutEnd.value, last.boundsInRoot.bottom, 1f)
        }
    }

    // A page form opened for its certificate brings the Special setups group,
    // with the certificate row, under the top. Red when the certificate
    // callout is not placed (Advanced opens, the page does not move).
    @Test(timeout = 120_000)
    fun aFormOpenedForItsCertificateBringsSpecialSetupsUnderTheTop() {
        page(height = 700).use { s ->
            s.onUi {
                s.app.signInForm.typeAddress("https://music.example.com")
                s.app.signInForm.askForCertificate()
            }
            s.until { s.app.signInForm.advancedOpen && !s.app.signInForm.certificatePending }
            s.render(4)
            s.landed(SignInWords.GROUP_SPECIAL_SETUPS, PageTopInset.value)
            assertTrue(s.withText(SignInWords.CERT_TITLE).isNotEmpty())
        }
    }

    // Picking API key with Advanced scrolled down to the Method brings the
    // key row, in the main card, under the top. Red when the key row is
    // left out of view.
    @Test(timeout = 120_000)
    fun pickingApiKeyBringsTheKeyRowUnderTheTop() {
        page().use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            s.bringUp(s.named("${SignInWords.METHOD}, Normal").single())
            s.click(s.named("${SignInWords.METHOD}, Normal").single())
            s.click(s.text("API key"))
            s.until { s.app.signInForm.useApiKey && !s.app.signInForm.keyPending }
            s.render(4)
            val title = s.text(SignInWords.API_KEY)
            val scroll = scrollOf(title)
            // The row's top lands at the inset; its name sits 12 under it.
            assertEquals(PageTopInset.value + 12f, title.boundsInRoot.top - scroll.boundsInRoot.top, 1f)
            assertTrue(s.named(SignInWords.API_KEY).isNotEmpty())
            assertTrue(s.named(SignInWords.PASSWORD).isEmpty())
            assertEquals(1, s.named(SignInWords.optionalName(SignInWords.USERNAME)).size)
        }
    }

    // Red when Add header leaves the keyboard where it was.
    @Test(timeout = 120_000)
    fun addHeaderPutsTheKeyboardInTheNewRowsName() {
        page(height = 1_600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            s.click(s.named(SignInWords.ADD_HEADER).single())
            s.render(4)
            assertEquals(SignInWords.HEADER_NAME, s.focused()?.name())
            // Where the rows are, not where they show: the second click scrolls.
            val first = s.focused()!!
            s.click(s.named(SignInWords.ADD_HEADER).single())
            s.render(4)
            assertEquals(SignInWords.HEADER_NAME, s.focused()?.name())
            assertEquals(s.named(SignInWords.HEADER_NAME).maxOf { it.positionInRoot.y }, s.focused()!!.positionInRoot.y, 0.5f)
            assertTrue(s.focused()!!.positionInRoot.y > first.positionInRoot.y)
        }
    }

    // Headers A, B and C, with values; Advanced open on a tall window.
    private fun A11yScene.threeHeaders() {
        onUi {
            val form = app.signInForm
            form.advancedOpen = true
            listOf("A" to "a-secret", "B" to "b-secret", "C" to "c-secret").forEach { (name, value) ->
                val id = form.addHeader()
                form.setHeaderName(id, name)
                form.setHeaderValue(id, value)
            }
        }
        settle(600).close()
    }

    // B's Show on, then B removed: C's value stays hidden, and B's Remove is
    // named for it. Red on rows drawn by position, where B's Show moves to C.
    @Test(timeout = 120_000)
    fun showStaysWithItsRowWhenAnotherIsRemoved() {
        page(height = 1_600).use { s ->
            s.threeHeaders()
            s.click(s.showIn(s.entry("b-secret")))
            assertFalse("B shows its value", s.entry("b-secret").hidden())
            assertTrue(s.entry("c-secret").hidden())
            s.click(s.named(SignInWords.removeHeader("B")).single())
            s.render(4)
            assertTrue(s.nodes().none { it.config.getOrNull(SemanticsProperties.InputText)?.text == "b-secret" })
            assertTrue("C's value is still hidden", s.entry("c-secret").hidden())
            assertTrue(s.entry("a-secret").hidden())
            assertEquals(listOf("A", "C"), s.app.signInForm.headers.map { it.name })
        }
    }

    // A keyboard Remove gives the keyboard to Add header, and C keeps its
    // value and Show. Red when focus is left to fall where it may.
    @Test(timeout = 120_000)
    fun aKeyboardRemoveGivesTheKeyboardToAddHeader() {
        page(height = 1_600).use { s ->
            s.threeHeaders()
            s.click(s.showIn(s.entry("c-secret")))
            assertFalse(s.entry("c-secret").hidden())
            s.onUi { s.keyboard.keyboard = true }
            assertTrue(s.tabTo(SignInWords.removeHeader("B")))
            s.press(Key.Enter)
            s.render(4)
            assertEquals(SignInWords.ADD_HEADER, s.focused()?.name())
            assertEquals("c-secret", s.entry("c-secret").config.getOrNull(SemanticsProperties.InputText)?.text)
            assertFalse("C still shows its value", s.entry("c-secret").hidden())
        }
    }

    // A mouse Remove leaves nothing focused (the Remove went with its row).
    @Test(timeout = 120_000)
    fun aMouseRemoveLeavesNothingFocused() {
        page(height = 1_600).use { s ->
            s.threeHeaders()
            s.click(s.named(SignInWords.removeHeader("C")).single())
            s.render(4)
            assertFalse(s.keyboard.keyboard)
            assertNull(s.focused())
            assertEquals(listOf("A", "B"), s.app.signInForm.headers.map { it.name })
        }
    }

    // Tab from a header's value goes to its Remove, past Show; Tab from the
    // invite's Password goes to Password again, past its eye.
    @Test(timeout = 120_000)
    fun tabGoesFromFieldToFieldPastShow() {
        page(height = 1_600).use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.advancedOpen = true
                val id = form.addHeader()
                form.setHeaderName(id, "X-Token")
                form.setHeaderValue(id, "v")
            }
            s.settle(600).close()
            s.onUi { s.keyboard.keyboard = true }
            assertTrue(s.tabTo(SignInWords.HEADER_VALUE))
            s.press(Key.Tab)
            assertEquals(SignInWords.removeHeader("X-Token"), s.focused()?.name())
            assertTrue(s.nodes().none { it.name() in setOf("Show", "Hide") && it.focused() })
            s.onUi { s.app.signInForm.takeJoinLink("https://music.example.com/family/join#invite=tok_9") }
            s.settle(600).close()
            assertTrue(s.tabTo(SignInWords.PASSWORD))
            s.press(Key.Tab)
            assertEquals(SignInWords.PASSWORD_AGAIN, s.focused()?.name())
            assertTrue(s.nodes().none { it.name() in setOf("Show", "Hide") && it.focused() })
        }
    }

    // The late saved sign-in page moves its untouched row into the sign-in
    // page group while the person is in another row's value with Show on:
    // that value keeps the keyboard and stays shown.
    @Test(timeout = 120_000)
    fun aLateSavedSignInPageLeavesTheFocusedRowAlone() {
        page(height = 1_600).use { s ->
            val server = SavedServer("https://music.example.com/", "winters", headerNames = listOf("Authorization", "X-Token"), id = "s1")
            s.onUi { s.app.fillSignIn(server) }
            s.onUi {
                val form = s.app.signInForm
                form.advancedOpen = true
                form.setHeaderValue(form.headerIds[1], "t-value")
            }
            s.settle(600).close()
            s.click(s.entry("t-value"))
            s.click(s.showIn(s.entry("t-value")))
            assertTrue(s.entry("t-value").focused())
            assertFalse(s.entry("t-value").hidden())
            s.onUi { s.app.signInForm.knowSavedGateway(server, SavedGateway(GatewayKind.Password, setOf("Authorization"))) }
            s.render(4)
            val form = s.app.signInForm
            assertEquals(GatewayKind.Password, form.gatewayKind)
            assertEquals(listOf("X-Token"), form.headers.map { it.name })
            assertTrue("the value keeps the keyboard", s.entry("t-value").focused())
            assertFalse("and stays shown", s.entry("t-value").hidden())
        }
    }

    // A form swapped in place (another server's sign-in) never shows its
    // first header in clear because the old form's first header was shown.
    // Red on rows keyed by id alone (ids start at 0 in every form).
    @Test(timeout = 120_000)
    fun aSwappedFormNeverInheritsShowOrFocus() {
        page(height = 1_600).use { s ->
            val a = SavedServer("https://a.example.com/", "winters", headerNames = listOf("X-A"), id = "a1")
            val b = SavedServer("https://b.example.com/", "winters", headerNames = listOf("X-B"), id = "b1")
            s.onUi {
                s.app.fillSignIn(a)
                s.app.signInForm.advancedOpen = true
                s.app.signInForm.setHeaderValue(s.app.signInForm.headerIds[0], "a-value")
            }
            s.settle(600).close()
            s.click(s.entry("a-value"))
            s.click(s.showIn(s.entry("a-value")))
            assertFalse(s.entry("a-value").hidden())
            s.onUi { s.app.fillSignIn(b) }
            s.render(4)
            val value = s.named(SignInWords.HEADER_VALUE).single()
            assertEquals("", value.config.getOrNull(SemanticsProperties.InputText)?.text)
            // Empty, so its eye tells: it offers Show.
            assertEquals("not shown in clear", "Show", s.showIn(value).name())
            assertFalse("not focused", value.focused())
        }
    }

    // The Edit sheet calls a sign-in page failure out in its own scroll. The
    // window is tall enough for the sheet's view to hold the whole group (on
    // a shorter one the group ends its last note 8 above the bottom).
    @Test(timeout = 120_000)
    fun theEditSheetBringsTheSignInPageGroupUnderItsTop() {
        signedIn(height = 760, serve = authentik).use { s ->
            s.onUi { editServer(s.app, s.app.connection!!.server) }
            s.settle(600).close()
            s.type(s.named(SignInWords.PASSWORD).single(), "pw")
            s.click(s.inPopup("Test connection"))
            s.until { s.withText(GatewayWords.POINTER).isNotEmpty() }
            s.render(6)
            s.landed(SignInWords.GROUP_GETTING_THERE, PopupTopInset.value)
        }
    }

    // The link sheet does the same, its footer staying put. Advanced opens
    // first, so the sheet is at its full height: a sheet grows as Advanced
    // opens.
    @Test(timeout = 120_000)
    fun theLinkSheetBringsTheSignInPageGroupUnderItsTop() {
        signedIn(height = 760, serve = authentik).use { s ->
            s.onUi { s.app.openFamilyLink(FamilySignInPrefill(s.server.address, "winters")) }
            s.settle(600).close()
            s.click(s.inPopup(SignInWords.ADVANCED))
            s.settle(400).close()
            s.type(s.named(SignInWords.PASSWORD).single(), "pw")
            val signIn = s.inPopup("Sign in")
            val footer = signIn.boundsInRoot.top
            s.click(signIn)
            s.until { s.withText(GatewayWords.POINTER).isNotEmpty() }
            s.render(6)
            s.landed(SignInWords.GROUP_GETTING_THERE, PopupTopInset.value)
            assertEquals("the footer stays under the scroll", footer, s.inPopup("Sign in").boundsInRoot.top, 1f)
        }
    }

    // Picks API key in a sheet's Method row.
    private fun A11yScene.pickApiKeyInPopup() {
        val head = inPopup(SignInWords.ADVANCED)
        bringUp(head)
        click(inPopup(SignInWords.ADVANCED))
        settle(400).close()
        bringUp(named("${SignInWords.METHOD}, Normal").single())
        click(named("${SignInWords.METHOD}, Normal").single())
        click(inPopup("API key"))
        render(4)
    }

    // The Add sheet: API key takes the password's place in the main fields,
    // and the username carries the tag. Red when the key stays in Advanced.
    @Test(timeout = 120_000)
    fun theAddSheetTakesAnApiKeyInItsMainFields() {
        signedIn(height = 1_000).use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            assertEquals(1, s.named(SignInWords.PASSWORD).size)
            s.pickApiKeyInPopup()
            assertEquals(1, s.named(SignInWords.API_KEY).size)
            assertTrue(s.named(SignInWords.PASSWORD).isEmpty())
            assertEquals(1, s.named(SignInWords.optionalName(SignInWords.USERNAME)).size)
        }
    }

    // A sign-in link's sheet does the same.
    @Test(timeout = 120_000)
    fun aSignInLinksSheetTakesAnApiKeyInItsMainFields() {
        signedIn(height = 1_000).use { s ->
            s.onUi { s.app.openFamilyLink(FamilySignInPrefill(s.server.address, "winters")) }
            s.settle(600).close()
            s.pickApiKeyInPopup()
            assertEquals(1, s.named(SignInWords.API_KEY).size)
            assertTrue(s.named(SignInWords.PASSWORD).isEmpty())
            s.type(s.named(SignInWords.API_KEY).single(), "key-1")
            assertEquals(SignInWords.API_KEY, s.entry("key-1").config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "))
            assertNotEquals("the key is typed into its own field", s.named(SignInWords.USERNAME).firstOrNull(), s.entry("key-1"))
        }
    }
}
