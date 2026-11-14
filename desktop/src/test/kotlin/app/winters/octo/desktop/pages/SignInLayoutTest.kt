package app.winters.octo.desktop.pages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.IconSize
import app.winters.octo.design.POPUP_TAG
import app.winters.octo.design.Space
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.desktop.a11y.clickable
import app.winters.octo.desktop.a11y.name
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.errorWordsFor
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

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
    // are taller than the view: its last note ends 8 above where the page's
    // 24 soft bottom rows start. Red when the callout leaves the fade out
    // (the note ends 8 above the bottom, inside the fade).
    @Test(timeout = 120_000)
    fun aTallGroupEndsItsLastNoteEightAboveTheSoftEdge() {
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
            assertEquals(scroll.boundsInRoot.bottom - CalloutEnd.value - 24f, last.boundsInRoot.bottom, 1f)
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

    // `below` sits under `above`, close: the link note under the result line.
    private fun A11yScene.under(above: String, below: String) {
        val top = text(above).boundsInRoot
        val note = text(below).boundsInRoot
        assertTrue("'$below' at ${note.top}, '$above' ends at ${top.bottom}", note.top >= top.bottom - 1f && note.top - top.bottom < 24f)
    }

    // A refused link's words sit under the result line in the plain form and
    // in a link's card, an invite or a hand-over showing; a held sign-up
    // keeps the address it was made at. Shots of each, for the polish pass.
    @Test(timeout = 120_000)
    fun aRefusedLinksWordsSitUnderTheResultLine() {
        page(height = 1_200).use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to SignInWords.WRONG_PASSWORD
                form.noteLink(SignInWords.LINK_WHILE_BUSY)
            }
            s.settle(600).close()
            s.under(SignInWords.WRONG_PASSWORD, SignInWords.LINK_WHILE_BUSY)
            s.shot("signin-link-note")
            s.onUi {
                val form = s.app.signInForm
                form.take(FamilyInviteLink("https://music.example.com", "t1"))
                form.result = false to SignInWords.WRONG_PASSWORD
                form.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            }
            s.settle(600).close()
            s.under(SignInWords.WRONG_PASSWORD, SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            s.shot("signin-invite-link-note")
            s.onUi { s.app.signInForm.signedUp = HeldSignUp("alex", "long enough", s.app.signInForm.url.toString()) }
            s.render(3)
            assertTrue("the address is read-only", s.named(SignInWords.SERVER_ADDRESS).single().config.getOrNull(SemanticsProperties.Disabled) != null)
            s.onUi {
                val form = s.app.signInForm
                form.leaveLink()
                // The result is written before the page draws, so the hand-over does not start.
                form.take(FamilyHandOverLink(base = "https://music.example.com", token = "t2", key = "k", server = "https://music.example.com"))
                form.result = false to SignInWords.WRONG_PASSWORD
                form.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            }
            s.settle(600).close()
            assertNotNull(s.app.signInForm.handOver)
            s.under(SignInWords.WRONG_PASSWORD, SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            s.shot("signin-hand-over-link-note")
        }
    }

    // The link sheet with a refused link's words in its foot, over its
    // buttons.
    @Test(timeout = 120_000)
    fun theLinkSheetShowsALinkNoteOverItsButtons() {
        signedIn(height = 1_000).use { s ->
            s.onUi { s.app.openFamilyLink(FamilySignInPrefill("https://music.example.com", "ana")) }
            s.settle(600).close()
            s.onUi { s.app.popups.request!!.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN) }
            s.render(4)
            val note = s.inPopup(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            assertTrue(note.boundsInRoot.top < s.inPopup("Sign in").boundsInRoot.top)
            s.shot("link-sheet-link-note")
        }
    }

    // Scrolls so the node's top is at `top` in the window, and checks it got
    // there. As bringUp: the glide starts on a frame at 0 and is drawn to its
    // end on the real clock.
    private fun A11yScene.placeTop(node: SemanticsNode, top: Float) {
        val scroll = scrollOf(node)
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        val by = node.positionInRoot.y - top
        val goal = range.value() + by
        assertTrue("the scroll reaches $goal of ${range.maxValue()}", goal >= 0f && goal <= range.maxValue())
        onUi { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, by) }
        render(1)
        val end = System.currentTimeMillis() + 10_000
        do {
            check(System.currentTimeMillis() < end) { "waited too long" }
            frameAt(System.nanoTime()).close()
        } while (abs(range.value() - goal) > 0.5f)
        assertEquals(top, node.positionInRoot.y, 1f)
    }

    // The open pop-up's left edge.
    private fun A11yScene.popupLeft(): Float = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }.positionInRoot.x

    // A box of the window's pixels: columns left until right, rows top until
    // bottom.
    private class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

    // A node's box as laid out, wherever its scroll has it.
    private fun SemanticsNode.area(): Area {
        val x = positionInRoot.x.toInt()
        val y = positionInRoot.y.toInt()
        return Area(x, y, x + size.width, y + size.height)
    }

    private fun brightness(c: Int): Int = (c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114

    private fun distance(a: Int, b: Int): Double {
        val r = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val g = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val bl = (a and 0xFF) - (b and 0xFF)
        return sqrt((r * r + g * g + bl * bl).toDouble())
    }

    // How far each pixel of the area stands from the area's background (the
    // middle color, by brightness, of its edge), row by row.
    private fun inkOf(image: Image, area: Area): List<DoubleArray> {
        val bitmap = Bitmap.makeFromImage(image)
        val edge = ArrayList<Int>()
        for (x in area.left until area.right) {
            edge += bitmap.getColor(x, area.top)
            edge += bitmap.getColor(x, area.bottom - 1)
        }
        for (y in area.top until area.bottom) {
            edge += bitmap.getColor(area.left, y)
            edge += bitmap.getColor(area.right - 1, y)
        }
        val back = edge.sortedBy { brightness(it) }[edge.size / 2]
        val rows = (area.top until area.bottom).map { y -> DoubleArray(area.right - area.left) { i -> distance(bitmap.getColor(area.left + i, y), back) } }
        bitmap.close()
        return rows
    }

    // How far the area's most inked pixel stands from its background.
    private fun ink(image: Image, area: Area): Double = inkOf(image, area).maxOf { it.max() }

    // The rows of the area holding ink (a pixel at least half as inked as the
    // most inked one), as offsets from the area's top.
    private fun inkRows(image: Image, area: Area): List<Int> {
        val rows = inkOf(image, area)
        val most = rows.maxOf { it.max() }
        return rows.indices.filter { y -> rows[y].any { it >= most / 2 } }
    }

    // The middle, in the window, of the area's ink, top to bottom.
    private fun inkMiddle(image: Image, area: Area): Float {
        val rows = inkRows(image, area)
        return area.top + (rows.first() + rows.last() + 1) / 2f
    }

    // The share of the area's pixels drawn in ink.
    private fun inkShare(image: Image, area: Area): Double {
        val all = inkOf(image, area).flatMap { it.toList() }
        val most = all.max()
        return all.count { it >= most / 2 }.toDouble() / all.size
    }

    // One pixel of a frame.
    private fun pixel(image: Image, x: Int, y: Int): Int {
        val bitmap = Bitmap.makeFromImage(image)
        return bitmap.getColor(x, y).also { bitmap.close() }
    }

    // WCAG's relative luminance.
    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    // CIE lightness, 0 to 100.
    private fun lightness(c: Int): Double {
        val y = luminance(c)
        return if (y > 0.008856) 116 * Math.cbrt(y) - 16 else 903.3 * y
    }

    // The 16 square of the mark a note draws before its words.
    private fun markOf(words: SemanticsNode): Area {
        val x = words.positionInRoot.x.toInt() - 24
        val y = words.positionInRoot.y.toInt() + 2
        return Area(x, y, x + 16, y + 16)
    }

    // The words' top four ink rows: their ink 40 under the view's top, then
    // 1 under it.
    private fun A11yScene.fadesUnderTheTop(words: String) {
        val node = text(words)
        val view = scrollOf(node).boundsInRoot.top
        placeTop(node, view + 40f)
        val shown = settle(300)
        val first = inkRows(shown, node.area()).first()
        val left = node.positionInRoot.x.toInt()
        val full = ink(shown, Area(left, node.positionInRoot.y.toInt() + first, left + node.size.width, node.positionInRoot.y.toInt() + first + 4))
        shown.close()
        placeTop(node, view + 1f - first)
        val faded = settle(300)
        val top = view.toInt() + 1
        val under = ink(faded, Area(left, top, left + node.size.width, top + 4))
        faded.close()
        assertTrue("'$words' 1 under the top edge keeps $under of its $full", under < 0.6 * full)
    }

    // Words scrolled up under the top of the page's view fade out rather than
    // end on a straight line: the top four ink rows of a group title, 1 under
    // the view's top, keep under 0.6 of the ink they have in full view. Red
    // without the page's soft edges (they keep all of it).
    @Test(timeout = 120_000)
    fun wordsUnderThePagesTopEdgeFadeOut() {
        page(height = 600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            s.fadesUnderTheTop(SignInWords.GROUP_GETTING_THERE)
        }
    }

    // The same in a sheet's scroll. Red without the sheet's soft edges.
    @Test(timeout = 120_000)
    fun wordsUnderASheetsTopEdgeFadeOut() {
        signedIn(height = 700).use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            s.click(s.inPopup(SignInWords.ADVANCED))
            s.settle(400).close()
            s.fadesUnderTheTop(SignInWords.PASSWORD)
        }
    }

    // The Add sheet's title is its loudest heading, on its cards' edge: taller
    // than Advanced's head. Red with the menu title (14 under a 22 Advanced).
    @Test(timeout = 120_000)
    fun theAddSheetsTitleIsItsLoudestHeading() {
        signedIn().use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            val title = s.inPopup("Add a server")
            val advanced = s.inPopup(SignInWords.ADVANCED)
            assertTrue("a heading", title.config.contains(SemanticsProperties.Heading))
            assertTrue("${title.size.height} against Advanced's ${advanced.size.height}", title.size.height > advanced.size.height + 4)
            assertEquals(s.popupLeft() + Space.Xl.value, title.positionInRoot.x, 0.5f)
        }
    }

    // Advanced's head starts on the groups' content edge, its title the size
    // of a group's title. Red with the head's 4 inset (516 against 528) or its
    // 22 bold title.
    @Test(timeout = 120_000)
    fun advancedsHeadIsAGroupTitleOnTheContentEdge() {
        page(height = 1_600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            val group = s.text(SignInWords.GROUP_GETTING_THERE)
            val advanced = s.text(SignInWords.ADVANCED)
            assertEquals(group.positionInRoot.x, advanced.positionInRoot.x, 0.5f)
            assertEquals(group.size.height.toFloat(), advanced.size.height.toFloat(), 0.5f)
        }
    }

    // Advanced's chevron sits on its title: its ink's middle within 1 of the
    // middle of the title's capitals (the A's ink). Red with the chevron
    // centered on the title and its line (about 8 under).
    @Test(timeout = 120_000)
    fun advancedsChevronSitsOnItsTitle() {
        page(height = 1_200).use { s ->
            s.settle(600).close()
            val title = s.text(SignInWords.ADVANCED)
            val head = generateSequence(title) { it.parent }.first { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
            val image = s.settle(300)
            val box = title.area()
            val caps = inkMiddle(image, Area(box.left, box.top, box.left + 7, box.bottom))
            val right = head.positionInRoot.x.toInt() + head.size.width - RowInset.value.toInt()
            val top = head.positionInRoot.y.toInt()
            val chevron = inkMiddle(image, Area(right - IconSize.Toolbar.value.toInt(), top, right, top + head.size.height))
            image.close()
            assertEquals(caps, chevron, 1f)
        }
    }

    // Remember password's switch sits on its name: its middle within 1 of
    // the middle of the name's capitals (the R's ink). Red with the switch
    // centered on the name and its line (8.5 under).
    @Test(timeout = 120_000)
    fun theRememberSwitchSitsOnItsName() {
        page().use { s ->
            s.settle(600).close()
            val name = s.text("Remember password")
            val switch = s.nodes().first { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
            val image = s.settle(300)
            val box = name.area()
            val caps = inkMiddle(image, Area(box.left, box.top, box.left + 7, box.bottom))
            image.close()
            assertEquals(caps, switch.positionInRoot.y + switch.size.height / 2f, 1f)
        }
    }

    // A choice's value keeps one line where the name leaves it room: Username
    // and password beside Sign-in page in front. Red with the value held to
    // 45% of the row (two lines).
    @Test(timeout = 120_000)
    fun aValueKeepsOneLineWhereTheNameLeavesItRoom() {
        page(height = 1_600).use { s ->
            s.onUi {
                s.app.signInForm.advancedOpen = true
                s.app.signInForm.pickGateway(GatewayKind.Password)
            }
            s.settle(600).close()
            val name = s.text(GatewayWords.LABEL_SECTION)
            val value = s.text(GatewayWords.kindLabel(GatewayKind.Password))
            assertEquals(name.size.height.toFloat(), value.size.height.toFloat(), 1f)
        }
    }

    // An error note's mark is solid, a shape of its own, where a note's info
    // mark is a ring: over 0.35 of the error mark's square is ink, under 0.3
    // of the info mark's. Red with the outlined error mark (about 0.2).
    @Test(timeout = 120_000)
    fun anErrorsMarkIsSolidAndANotesIsARing() {
        page(height = 1_600).use { s ->
            s.onUi {
                s.app.signInForm.advancedOpen = true
                s.app.signInForm.result = false to SignInWords.WRONG_PASSWORD
            }
            s.settle(600).close()
            val image = s.settle(300)
            val error = inkShare(image, markOf(s.text(SignInWords.WRONG_PASSWORD)))
            val info = inkShare(image, markOf(s.text(SignInWords.HOME_NOTE)))
            image.close()
            assertTrue("the error mark's ink $error", error > 0.35)
            assertTrue("the info mark's ink $info", info < 0.3)
        }
    }

    // A group called out for a failure is ringed in the red of its error
    // note. Red with the accent ring.
    @Test(timeout = 120_000)
    fun theCalloutRingIsTheErrorRed() {
        page(height = 1_600).use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to GatewayWords.failure(Gateway.Authentik, false)
                form.failed(form.attemptAddress, Gateway.Authentik)
            }
            s.until { s.app.signInForm.showsCallout && !s.app.signInForm.calloutPending }
            s.render(4)
            val title = s.text(SignInWords.GROUP_GETTING_THERE)
            val left = title.positionInRoot.x.toInt() - RowInset.value.toInt()
            val top = title.positionInRoot.y.toInt() + title.size.height + GroupTitleGap.value.toInt()
            val image = s.settle(300)
            val ring = pixel(image, left + 1, top + 30)
            image.close()
            assertTrue("the ring is ${Integer.toHexString(ring)}", distance(ring, errorWordsFor(false)) < 40.0)
        }
    }

    // Paste a family link sits under the buttons, its words ending on Sign
    // in's right edge. Red when it is centered.
    @Test(timeout = 120_000)
    fun pasteAFamilyLinkEndsOnTheButtonsRightEdge() {
        page().use { s ->
            s.settle(600).close()
            val words = s.text("Paste a family link")
            val button = generateSequence(s.text("Sign in")) { it.parent }.first { it.clickable }
            assertEquals(button.positionInRoot.x + button.size.width, words.positionInRoot.x + words.size.width, 1f)
        }
    }

    // The Add sheet shades the page behind it, as Change password does: the
    // page under the shade keeps under half its light. Red without the shade.
    @Test(timeout = 120_000)
    fun theAddSheetShadesThePageBehindIt() {
        signedIn().use { s ->
            s.onUi {
                s.app.settings.pickTheme(ThemeChoice.Paper)
                s.app.navigator.go(Page.Songs)
            }
            val before = s.settle(800).let { image -> pixel(image, 300, 600).also { image.close() } }
            s.onUi { addServer(s.app) }
            val after = s.settleStill(700, quiet = 150).let { image -> pixel(image, 300, 600).also { image.close() } }
            assertTrue("the page behind: ${luminance(after)} against ${luminance(before)}", luminance(after) < 0.5 * luminance(before))
        }
    }

    // On a light theme a sheet's group card is a tray a step darker than the
    // sheet: on Paper at least 3 under it in lightness. Red with the card
    // color there (1.6 over the sheet).
    @Test(timeout = 120_000)
    fun aSheetsCardIsATrayOnALightTheme() {
        signedIn().use { s ->
            s.onUi { s.app.settings.pickTheme(ThemeChoice.Paper) }
            s.settle(600).close()
            s.onUi { addServer(s.app) }
            val image = s.settleStill(700, quiet = 150)
            val left = s.popupLeft().toInt()
            val name = s.inPopup(SignInWords.USERNAME)
            val y = (name.positionInRoot.y + name.size.height / 2f).toInt()
            val sheet = pixel(image, left + 8, y)
            val card = pixel(image, left + Space.Xl.value.toInt() + 4, y)
            image.close()
            assertTrue("card ${lightness(card)} on sheet ${lightness(sheet)}", lightness(sheet) - lightness(card) >= 3.0)
        }
    }

    // The Add sheet's Test connection is a capsule like the page's, starting
    // on the cards' edge. Red with the bare text link (no capsule named for
    // it; its words 12 in from that edge).
    @Test(timeout = 120_000)
    fun theSheetsTestConnectionIsACapsuleOnTheCardsEdge() {
        signedIn().use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            val capsule = s.named("Test connection").filter { it.inPopup() }
            assertEquals("a capsule named Test connection", 1, capsule.size)
            assertEquals(s.popupLeft() + Space.Xl.value, capsule.single().positionInRoot.x, 0.5f)
        }
    }

    // The Add sheet gives the keyboard to Server address when opened from the
    // keyboard. Red when the pop-up finds its own first row (Name).
    @Test(timeout = 120_000)
    fun theAddSheetGivesTheKeyboardToTheAddressFromTheKeyboard() {
        signedIn().use { s ->
            assertTrue(s.keyboard.keyboard)
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            assertEquals(SignInWords.SERVER_ADDRESS, s.focused()?.name())
        }
    }

    // And opened with a click. Red when the sheet asks before its rows exist
    // (nothing in it takes the keyboard).
    @Test(timeout = 120_000)
    fun theAddSheetGivesTheKeyboardToTheAddressAfterAClick() {
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            assertEquals(SignInWords.SERVER_ADDRESS, s.focused()?.name())
        }
    }

    // The Sign in sheet gives it to the password. Red as above.
    @Test(timeout = 120_000)
    fun theSignInSheetGivesTheKeyboardToThePassword() {
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { signInTo(s.app, s.app.connection!!.server) }
            s.settle(600).close()
            assertEquals(SignInWords.PASSWORD, s.focused()?.name())
        }
    }

    // Pictures for the polish pass, in desktop/build/shots. They run only
    // with OCTO_SHOTS=1, and each first checks the state it pictures.

    // The page: at rest, ready, under the pointer, with the keyboard on Sign
    // in, with API key, and with Advanced open on a picked sign-in page.
    @Test(timeout = 300_000)
    fun picturesOfThePage() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page().use { s ->
            s.shot("polish-page")
            s.fill("https://music.example.com")
            s.render(3)
            assertTrue(s.app.signInForm.ready)
            s.shot("polish-page-ready")
            val test = s.named("Test connection").single()
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, test.boundsInRoot.center) }
            s.shot("polish-page-hover")
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(5f, 895f)) }
            s.onUi { s.keyboard.keyboard = true }
            assertTrue(s.tabTo("Sign in"))
            s.shot("polish-page-focus")
            s.onUi { s.app.signInForm.pickMethod(AuthMode.ApiKey) }
            s.until { s.app.signInForm.useApiKey && !s.app.signInForm.keyPending }
            s.shot("polish-page-api-key")
            s.onUi {
                s.app.signInForm.pickMethod(AuthMode.Token)
                s.app.signInForm.advancedOpen = true
                s.app.signInForm.pickGateway(GatewayKind.Password)
            }
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.shot("polish-page-advanced")
        }
    }

    // The page scrolled with Advanced open: a title under the top edge, then
    // the last note under the bottom edge.
    @Test(timeout = 300_000)
    fun picturesOfThePageScrolled() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page(height = 600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            val title = s.text(SignInWords.GROUP_GETTING_THERE)
            s.placeTop(title, scrollOf(title).boundsInRoot.top + 6f)
            s.shot("polish-page-scrolled-top")
            val last = s.text(SignInWords.HOME_NOTE)
            s.placeTop(last, scrollOf(last).boundsInRoot.bottom - 10f)
            s.shot("polish-page-scrolled-bottom")
        }
    }

    // The callout in Octo, Paper and Dusk (the accents nearest the error red).
    @Test(timeout = 300_000)
    fun picturesOfTheCallout() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page().use { s ->
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to GatewayWords.failure(Gateway.Authentik, false)
                form.failed(form.attemptAddress, Gateway.Authentik)
            }
            s.until { s.app.signInForm.showsCallout && !s.app.signInForm.calloutPending }
            for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Paper, ThemeChoice.Dusk)) {
                s.onUi { s.app.settings.pickTheme(theme) }
                s.shot("polish-page-callout-${theme.name.lowercase()}")
            }
        }
    }

    // The invite's card and the hand-over's card on the page.
    @Test(timeout = 300_000)
    fun picturesOfTheLinkCards() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page(height = 1_000).use { s ->
            s.onUi { s.app.signInForm.take(FamilyInviteLink("https://music.example.com", "t1")) }
            s.settle(600).close()
            assertNotNull(s.app.signInForm.invite)
            s.shot("polish-page-invite")
            s.onUi {
                val form = s.app.signInForm
                form.leaveLink()
                // The result is written before the page draws, so the hand-over does not start.
                form.take(FamilyHandOverLink(base = "https://music.example.com", token = "t2", key = "k", server = "https://music.example.com"))
                form.result = false to SignInWords.WRONG_PASSWORD
            }
            s.settle(600).close()
            assertNotNull(s.app.signInForm.handOver)
            s.shot("polish-page-hand-over")
        }
    }

    // The page with the words set 130% larger, closed and with Advanced open.
    @Test(timeout = 300_000)
    fun picturesAtLargerText() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        A11yScene(folder.newFolder(), height = 900, reduceMotion = true, signedOut = true, textScale = 1.3f).use { s ->
            s.shot("polish-page-text-130")
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.shot("polish-page-text-130-advanced")
        }
    }

    // The server sheets over Songs: Add in Octo, Paper and Daylight; Add with
    // Advanced open and scrolled; Edit; Sign in; and a family invite's sheet.
    @Test(timeout = 300_000)
    fun picturesOfTheSheets() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        signedIn().use { s ->
            for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Paper, ThemeChoice.Daylight)) {
                s.onUi {
                    s.app.popups.close()
                    s.app.settings.pickTheme(theme)
                    s.app.navigator.go(Page.Songs)
                    addServer(s.app)
                }
                s.settle(600).close()
                assertTrue(s.app.popups.open)
                s.shot("polish-sheet-add-${theme.name.lowercase()}")
            }
            s.onUi { s.app.settings.pickTheme(ThemeChoice.Octo) }
            s.settle(400).close()
            s.click(s.inPopup(SignInWords.ADVANCED))
            s.settle(400).close()
            s.bringUp(s.inPopup(SignInWords.GROUP_SIGNING_IN))
            s.shot("polish-sheet-add-advanced")
            s.onUi {
                s.app.popups.close()
                editServer(s.app, s.app.connection!!.server)
            }
            s.settle(600).close()
            assertTrue(s.app.popups.open)
            s.shot("polish-sheet-edit")
            s.onUi {
                s.app.popups.close()
                signInTo(s.app, s.app.connection!!.server)
            }
            s.settle(600).close()
            assertTrue(s.app.popups.open)
            s.shot("polish-sheet-sign-in")
            s.onUi {
                s.app.popups.close()
                s.app.openFamilyLink(FamilyInviteLink(s.server.address, "t1"))
            }
            s.settle(600).close()
            assertTrue(s.app.popups.open)
            s.shot("polish-sheet-invite")
        }
    }

    // The Edit sheet calling a sign-in page failure out.
    @Test(timeout = 300_000)
    fun pictureOfASheetsCallout() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        signedIn(height = 760, serve = authentik).use { s ->
            s.onUi { editServer(s.app, s.app.connection!!.server) }
            s.settle(600).close()
            s.type(s.named(SignInWords.PASSWORD).single(), "pw")
            s.click(s.inPopup("Test connection"))
            s.until { s.withText(GatewayWords.POINTER).isNotEmpty() }
            s.render(6)
            s.shot("polish-sheet-callout")
        }
    }
}
