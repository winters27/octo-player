package app.winters.octo.desktop.pages

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.FrameSize
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.POPUP_TAG
import app.winters.octo.design.Space
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.desktop.a11y.clickable
import app.winters.octo.desktop.a11y.name
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.server.CertFixtures
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.CertificateOpened
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.server.readCapped
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.name
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.errorWordsFor
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The sign-in page and its sheets as drawn: Advanced opening glides the
// card up to sit balanced, a failure that met a sign-in page brings its group under the
// top (or ends a tall group's last note 8 above the bottom), picking API key
// brings its row up, header rows keep their focus and Show through adds and
// removes, a late answer or a swapped form never shows a secret, and Tab
// never stops on Show.
@OptIn(ExperimentalComposeUiApi::class)
class SignInLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    // The window signed out, on the sign-in page, or signed in. `chosen` is
    // the file a certificate row's Choose picks, and `reads` holds the
    // certificate reads when a row asks for that.
    private fun page(height: Int = 900, chosen: File? = null, reads: HeldReads? = null, serve: (FakeServer) -> Unit = {}) =
        A11yScene(folder.newFolder(), height = height, reduceMotion = true, signedOut = true, serve = serve, wrap = choosing(chosen), readCertificate = reads?.read ?: ::readCapped)

    private fun signedIn(height: Int = 900, chosen: File? = null, reads: HeldReads? = null, serve: (FakeServer) -> Unit = {}) =
        A11yScene(folder.newFolder(), height = height, reduceMotion = true, serve = serve, wrap = choosing(chosen), readCertificate = reads?.read ?: ::readCapped)

    // The file window gives `chosen` when there is one.
    private fun choosing(chosen: File?): @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (chosen == null) content() else CompositionLocalProvider(LocalChooseCertificateFile provides { _ -> chosen }) { content() }
    }

    // Certificate file reads that wait, once held, until let go, at most 10 s.
    private class HeldReads {
        @Volatile private var gate: CountDownLatch? = null

        val read: (Path) -> ByteArray = { path ->
            gate?.let { check(it.await(10, TimeUnit.SECONDS)) { "held too long" } }
            readCapped(path)
        }

        fun hold() {
            gate = CountDownLatch(1)
        }

        fun release() {
            gate?.countDown()
        }
    }

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

    // A click in the middle of a node, with no frame drawn after it: the
    // frames that follow are the test's own, on the real clock.
    private fun A11yScene.clickOnly(node: SemanticsNode) {
        val at = Offset(node.positionInRoot.x + node.size.width / 2f, node.middle())
        onUi {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, at)
        }
    }

    // The room the card has in a window 720 or taller: from the window's
    // title bar and the page's 32 over the card, to the page's 32 under it.
    private fun A11yScene.room(): Pair<Float, Float> = pagePad to height - 32f

    // Opening Advanced in a tall window glides the card up from its rest:
    // its top rises on every frame it moves, never by more than the glide's
    // own curve moves it in that frame's time, its words going up with it,
    // until the open card, which fits the room, sits in the room's middle.
    // Opened with a click and drawn with motion on. Red with the card left at
    // its rest as Advanced opens (431 over it and 33 under at 1600), or set
    // in its place in one frame.
    @Test(timeout = 120_000)
    fun openingAdvancedGlidesTheCardUpToSitBalanced() {
        A11yScene(folder.newFolder(), height = 1_600, signedOut = true).use { s ->
            s.settle(800).close()
            val rest = s.cardTop()
            val inside = s.text("Octo").positionInRoot.y - rest
            s.clickOnly(generateSequence(s.text(SignInWords.ADVANCED)) { it.parent }.first { it.clickable })
            // Frames 16 ms apart on the scene's clock, however long each
            // takes to draw, so the glide is seen frame by frame.
            val start = System.nanoTime()
            val tops = mutableListOf(rest)
            val times = mutableListOf(start)
            for (frame in 1..60) {
                val now = start + frame * 16_000_000L
                s.frameAt(now).close()
                val top = s.cardTop()
                if (top > s.room().first + 0.5f) assertEquals("the words lift with the card at $top", inside, s.text("Octo").positionInRoot.y - top, 0.5f)
                tops += top
                times += now
            }
            assertTrue(s.app.signInForm.advancedOpen)
            val (top, bottom) = s.cardEdges()
            val (roomTop, roomBottom) = s.room()
            assertTrue("the open card, $top to $bottom, fits the room from $roomTop to $roomBottom", bottom - top < roomBottom - roomTop - 1f)
            assertEquals("the open card sits in the room's middle: ${top - roomTop} over it, ${roomBottom - bottom} under", top - roomTop, roomBottom - bottom, 1f)
            val distance = rest - top
            assertTrue("the card rose $distance", distance > 100f)
            val rises = tops.zipWithNext().filter { (a, b) -> b > a + 0.5f }
            assertTrue("the card's top fell back ${rises.take(3)} on its way up", rises.isEmpty())
            // The glide's tween, at its steepest, per nanosecond of its 300 ms.
            val steepest = (0 until 1_000).maxOf { i -> FastOutSlowInEasing.transform((i + 1) / 1_000f) - FastOutSlowInEasing.transform(i / 1_000f) } * 1_000f
            val perNano = distance * steepest / (OctoDuration.Neutral * 1_000_000f)
            val jumps = tops.indices.drop(1).filter { i -> tops[i - 1] - tops[i] > perNano * (times[i] - times[i - 1]) + 1f }
            assertTrue("frames that jumped past the glide: ${jumps.take(3).map { tops[it - 1] to tops[it] }}", jumps.isEmpty())
            val between = tops.count { it < rest - 1f && it > top + 1f }
            assertTrue("$between frames between rest and its place", between >= 6)
        }
    }

    // In a shorter window, where the open card does not fit, opening
    // Advanced lifts the card to the window's top pad with its words still
    // at their top inside it, and goes no further. Red with the card left at
    // its rest.
    @Test(timeout = 120_000)
    fun openingAdvancedInAShorterWindowLiftsTheCardToTheTop() {
        page(height = 900).use { s ->
            s.settle(600).close()
            val rest = s.cardTop()
            val title = s.text("Octo")
            val inside = title.positionInRoot.y - rest
            assertTrue("the closed card rests under the pad at $rest", rest > pagePad + 10f)
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(300).close()
            assertTrue(s.app.signInForm.advancedOpen)
            val (top, bottom) = s.cardEdges()
            assertEquals("lifted to the pad", pagePad, top, 0.5f)
            assertEquals("to the room's foot: the words run on past it", s.room().second, bottom, 0.5f)
            assertEquals("the words still at their top inside it", inside, title.positionInRoot.y - top, 0.5f)
        }
    }

    // Advanced's first group's title shows on the card, clear of its soft
    // edges.
    private fun A11yScene.firstGroupShows(how: String) {
        val (top, bottom) = cardEdges()
        val title = text(SignInWords.GROUP_GETTING_THERE)
        val y = title.positionInRoot.y
        assertTrue("$how: the first group's title at $y to ${y + title.size.height}, on a card from $top to $bottom", y >= top + PageFade.value && y + title.size.height <= bottom - PageFade.value)
    }

    // Opened from its head with none of its groups in view, Advanced brings
    // its first group's title over the card's soft foot in the same glide:
    // at 130% text in a 900 window, opened from the keyboard, and in a 600
    // window scrolled down to the head, opened with a click. Red when only
    // the chevron turns (the title starts under the card's foot).
    @Test(timeout = 240_000)
    fun openingAdvancedFromItsHeadShowsItsFirstGroup() {
        largerText().use { s ->
            s.settle(600).close()
            s.onUi { s.keyboard.keyboard = true }
            var presses = 0
            while (s.focused()?.name()?.startsWith(SignInWords.ADVANCED) != true) {
                check(presses++ < 60) { "Tab never reached Advanced" }
                s.press(Key.Tab)
            }
            s.press(Key.Enter)
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.firstGroupShows("130%")
        }
        page(height = 600).use { s ->
            s.settle(600).close()
            val head = s.text(SignInWords.ADVANCED)
            s.scrollToEnd(head)
            s.click(head)
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.firstGroupShows("600 tall")
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

    // A sheet's Advanced, opened from its head with none of its groups in
    // view, does the same in the sheet's own scroll: the Add sheet at 130%
    // text in a 700 window, scrolled down to the head. Red when only the
    // chevron turns.
    @Test(timeout = 120_000)
    fun aSheetsAdvancedOpenedFromItsHeadShowsItsFirstGroup() {
        A11yScene(folder.newFolder(), height = 700, reduceMotion = true, textScale = 1.3f).use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            s.scrollToEnd(s.inPopup(SignInWords.ADVANCED))
            s.click(s.inPopup(SignInWords.ADVANCED))
            s.settle(400).close()
            val title = s.inPopup(SignInWords.GROUP_GETTING_THERE)
            val view = scrollOf(title).boundsInRoot
            val y = title.positionInRoot.y
            assertTrue("the first group's title at $y to ${y + title.size.height}, in a view from ${view.top} to ${view.bottom}", y >= view.top && y + title.size.height <= view.bottom - SheetFade.value)
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

    // The pop-ups that ask for a password give the keyboard to their first
    // field once their group's rows are drawn, the second opened straight
    // after the first closes: Change password to the current password, the
    // certificate's ask to its password. Red with each sheet asking for it
    // itself as it is composed, before the group's rows (laid out in their
    // own pass) hold the field.
    @Test(timeout = 120_000)
    fun thePasswordPopupsGiveTheKeyboardToTheirFirstField() {
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { changePassword(s.app) }
            s.settle(600).close()
            assertEquals("Change password", "Current password", s.focused()?.name())
            s.onUi {
                s.app.popups.close()
                askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
            }
            s.settle(600).close()
            assertEquals("the certificate's ask", SignInWords.CERT_PASSWORD_FIELD, s.focused()?.name())
        }
    }

    // The form the open sheet was made with: the receiver of the link note
    // the sheet handed its pop-up (`form::noteLink`).
    private fun A11yScene.sheetForm(): SignInForm {
        val note = app.popups.request!!.noteLink
        return note.javaClass.getMethod("getBoundReceiver").invoke(note) as SignInForm
    }

    // In the pop-up on top, not in one kept out of sight under it: the
    // unmerged tree still lists what such a pop-up holds, under the node that
    // clears it for a screen reader.
    private fun SemanticsNode.inOpenPopup(): Boolean = inPopup() && generateSequence(this) { it.parent }.none { it.config.isClearingSemantics }

    // The open pop-up's card, as laid out.
    private fun A11yScene.popupArea(): Area = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG && it.inOpenPopup() }.area()

    // The control drawing these words in the open pop-up, its lowest.
    private fun A11yScene.controlInPopup(words: String): SemanticsNode =
        generateSequence(withText(words).filter { it.inOpenPopup() }.maxBy { it.positionInRoot.y }) { it.parent }.first { it.clickable }

    // Presses a control the way a screen reader does, with no pointer moved.
    private fun A11yScene.activate(words: String) {
        val control = controlInPopup(words)
        onUi { control.config[SemanticsActions.OnClick].action?.invoke() }
        render(3)
    }

    private fun A11yScene.giveKeyboard(node: SemanticsNode) {
        onUi { node.config[SemanticsActions.RequestFocus].action?.invoke() }
        render(3)
    }

    // A click at a point of the window.
    private fun A11yScene.pressAt(at: Offset) {
        onUi {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, at)
        }
        render(3)
    }

    // How many of the area's pixels differ between two frames by more than
    // 6 in a channel.
    private fun changedPixels(a: Image, b: Image, area: Area): Int {
        val one = Bitmap.makeFromImage(a)
        val two = Bitmap.makeFromImage(b)
        var changed = 0
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val p = one.getColor(x, y)
                val q = two.getColor(x, y)
                val most = maxOf(abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)), abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)), abs((p and 0xFF) - (q and 0xFF)))
                if (most > 6) changed++
            }
        }
        one.close()
        two.close()
        return changed
    }

    // The mean lightness of the window round `area` (every fourth pixel
    // more than `margin` off it, so its halo is left out).
    private fun A11yScene.lightnessRound(image: Image, area: Area, margin: Int): Double {
        val bitmap = Bitmap.makeFromImage(image)
        var sum = 0.0
        var count = 0
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                if (x in (area.left - margin) until (area.right + margin) && y in (area.top - margin) until (area.bottom + margin)) continue
                sum += lightness(bitmap.getColor(x, y))
                count++
            }
        }
        bitmap.close()
        return sum / count
    }

    // Asks for `file`'s password for `form`, as choosing a file that needs
    // one does, from the pop-up open now (the row's), if any.
    private fun A11yScene.chooseNeedingPassword(form: SignInForm, file: File) {
        val opened = runBlocking { app.accounts.openPicked(file.path, "") }
        assertTrue("the file needs its password", opened is CertificateOpened.Refused && opened.wrongPassword)
        val turn = Any()
        onUi {
            form.certificateTurn = turn
            askCertificatePassword(app, form, file.path, turn, app.popups.request)
        }
        settle(600).close()
    }

    // The control drawing these words on the page, not in a pop-up.
    private fun A11yScene.controlOnPage(words: String): SemanticsNode =
        generateSequence(withText(words).single { !it.inPopup() }) { it.parent }.first { it.clickable }

    // Presses it the way a screen reader does.
    private fun A11yScene.pressOnPage(words: String) {
        val control = controlOnPage(words)
        onUi { control.config[SemanticsActions.OnClick].action?.invoke() }
        render(3)
    }

    // A file chosen for one server asks for its password, then a link to
    // another server starts the page's form over: Use it opens the file
    // and closes the ask, but puts nothing on the form the link made. Red
    // when the forget leaves the opening's turn, so the first server's file
    // lands on the second server's form.
    @Test(timeout = 120_000)
    fun aFileAskedForBeforeALinkToAnotherServerIsNotTaken() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        page().use { s ->
            val form = s.app.signInForm
            s.onUi {
                form.typeAddress("https://a.example.com")
                form.advancedOpen = true
            }
            s.settle(600).close()
            s.chooseNeedingPassword(form, file)
            s.onUi { s.app.openFamilyLink(FamilyInviteLink("https://b.example.com", "t1")) }
            s.render(3)
            assertEquals("the link started the form over", "t1", form.invite?.token)
            assertEquals("b.example.com", form.address)
            s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inOpenPopup() }, CertFixtures.PASSWORD)
            val asking = s.app.popups.request
            s.activate(SignInWords.CERT_PASSWORD_USE)
            s.until { s.app.popups.request !== asking }
            s.settle(300).close()
            assertEquals("no file on the new server's form", CertificateDraft.None, form.certificateDraft)
            assertNull(form.certificateLoad)
        }
    }

    // Choose opening a file that is slow to read, then Advanced closed: the
    // opening ends with its row, and Choose is ready again once Advanced
    // opens. Red when the flag that turns Choose off is cleared only once the
    // read comes back: the row's scope ends first, and Choose stays off with
    // its spinner.
    @Test(timeout = 120_000)
    fun closingAdvancedWhileAFileIsReadLeavesChooseReady() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        val reads = HeldReads()
        page(chosen = file, reads = reads).use { s ->
            val form = s.app.signInForm
            s.onUi { form.advancedOpen = true }
            s.settle(600).close()
            reads.hold()
            try {
                s.pressOnPage(SignInWords.CERT_CHOOSE)
                s.until { form.certificateOpening && s.app.accounts.certificateReading.isNotEmpty() }
                s.onUi { form.advancedOpen = false }
                s.settle(600).close()
                assertTrue("the row is gone", s.withText(SignInWords.CERT_CHOOSE).isEmpty())
            } finally {
                reads.release()
            }
            s.until { s.app.accounts.certificateReading.isEmpty() }
            s.render(3)
            assertFalse("the opening ended with its row", form.certificateOpening)
            s.onUi { form.advancedOpen = true }
            s.settle(600).close()
            assertFalse("Choose is on", s.controlOnPage(SignInWords.CERT_CHOOSE).config.contains(SemanticsProperties.Disabled))
            assertEquals("nothing was taken", CertificateDraft.None, form.certificateDraft)
        }
    }

    // The certificate's password ask keeps under it only the pop-up its row
    // is drawn in, while that one is on top. From the Add sheet's Choose the
    // sheet is kept and comes back after Cancel; from the page's Choose, a
    // menu opened while the file was read is replaced and never comes back.
    // Red when the ask keeps whatever is on top: the menu returns after
    // Cancel.
    @Test(timeout = 180_000)
    fun theCertificateAskKeepsUnderItOnlyThePopUpItWasAskedFrom() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn(chosen = file).use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            val sheet = s.app.popups.request!!
            val form = s.sheetForm()
            s.onUi { form.advancedOpen = true }
            s.settle(600).close()
            s.activate(SignInWords.CERT_CHOOSE)
            s.until { s.app.popups.request !== sheet && s.withText(SignInWords.CERT_PASSWORD_TITLE).any { it.inOpenPopup() } }
            assertSame("the sheet is kept under the ask", sheet, s.app.popups.request?.under)
            s.activate("Cancel")
            s.settle(600).close()
            assertSame("the sheet is back", sheet, s.app.popups.request)
        }
        val reads = HeldReads()
        page(chosen = file, reads = reads).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            reads.hold()
            val menu = try {
                s.pressOnPage(SignInWords.CERT_CHOOSE)
                s.until { s.app.accounts.certificateReading.isNotEmpty() }
                s.onUi { s.app.popups.showAt(IntOffset(40, 40)) { _ -> MenuRow("A menu row", {}) } }
                s.render(3)
                s.app.popups.request ?: throw AssertionError("the menu did not open")
            } finally {
                reads.release()
            }
            s.until { s.app.popups.request !== menu && s.withText(SignInWords.CERT_PASSWORD_TITLE).any { it.inOpenPopup() } }
            assertNull("the menu is not kept under the ask", s.app.popups.request?.under)
            s.activate("Cancel")
            s.settle(600).close()
            assertNull("nothing comes back after Cancel", s.app.popups.request)
        }
    }

    // From the open server sheet titled `title`: Advanced open and Choose
    // holding the keyboard, as a press on it leaves it, a file that needs its
    // password is chosen. The ask opens with its field holding the keyboard
    // and the sheet out of sight. Cancel brings the sheet back exactly as it
    // was (the same picture, what was typed, no certificate) with the
    // keyboard back on Choose, as Escape and a press outside the ask do; Use
    // it with the file's password brings it back with the file taken and the
    // keyboard on Change. `kept` checks what was typed.
    private fun A11yScene.askReturnsToTheSheet(title: String, file: File, kept: (SignInForm) -> Unit) {
        val sheet = app.popups.request!!
        val form = sheetForm()
        val start = form.certificateDraft
        onUi { form.advancedOpen = true }
        settle(600).close()
        bringUp(controlInPopup(SignInWords.CERT_CHOOSE))
        giveKeyboard(controlInPopup(SignInWords.CERT_CHOOSE))
        assertEquals("Choose has the keyboard", SignInWords.CERT_CHOOSE, focused()?.name())
        val area = popupArea()
        val before = settleStill(600, quiet = 200)

        fun ask() {
            chooseNeedingPassword(form, file)
            assertNotSame("the ask is open", sheet, app.popups.request)
            assertTrue("the sheet is out of sight", withText(title).none { it.inOpenPopup() })
            val focus = focused()
            assertTrue("the ask's field has the keyboard: ${focus?.name()}", focus != null && focus.name() == SignInWords.CERT_PASSWORD_FIELD && focus.inOpenPopup())
        }
        fun back(how: String) {
            settle(600).close()
            assertSame("$how: the sheet is back", sheet, app.popups.request)
            assertEquals("$how: its title shows", 1, withText(title).count { it.inOpenPopup() })
            kept(form)
            assertEquals("$how: no certificate taken", start, form.certificateDraft)
            assertEquals("$how: the keyboard is back on Choose", SignInWords.CERT_CHOOSE, focused()?.name())
        }

        ask()
        activate("Cancel")
        back("Cancel")
        val after = settleStill(600, quiet = 200)
        val changed = changedPixels(before, after, area)
        before.close()
        after.close()
        val all = (area.right - area.left) * (area.bottom - area.top)
        assertTrue("Cancel: $changed of the sheet's $all pixels changed", changed <= all / 1_000)

        ask()
        onUi { app.perform(Shortcut.CloseLayer, typing = true) }
        back("Escape")

        ask()
        pressAt(Offset(24f, height / 2f))
        back("a press outside")

        ask()
        type(named(SignInWords.CERT_PASSWORD_FIELD).single { it.inOpenPopup() }, CertFixtures.PASSWORD)
        val asking = app.popups.request
        activate(SignInWords.CERT_PASSWORD_USE)
        until { app.popups.request !== asking }
        settle(600).close()
        assertSame("Use it: the sheet is back", sheet, app.popups.request)
        kept(form)
        assertTrue("Use it: the file is taken", form.certificateDraft is CertificateDraft.Use)
        assertTrue("Use it: the row names it", withText(SignInWords.CERT_NONE).none { it.inOpenPopup() } && nodes().any { node -> node.inOpenPopup() && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(file.name) } == true })
        assertEquals("Use it: the keyboard is back on Change", SignInWords.CERT_CHANGE, focused()?.name())
    }

    // A certificate's password asked from the Add sheet keeps the sheet
    // under it, and every way the ask closes brings the sheet back as it
    // was, the address still typed (askReturnsToTheSheet). Red at BASE by
    // the sheet being gone: the ask replaced it, and closing it left nothing
    // open.
    @Test(timeout = 240_000)
    fun aCertificatePasswordAskedFromTheAddSheetReturnsToIt() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            s.type(s.named(SignInWords.SERVER_ADDRESS).single { it.inPopup() }, "music.example.com")
            s.askReturnsToTheSheet("Add a server", file) { form -> assertEquals("the address still typed", "music.example.com", form.address) }
        }
    }

    // The same from the Edit sheet, a new name typed, and from the Sign in
    // sheet, a password typed. Red at BASE by the sheet being gone.
    @Test(timeout = 300_000)
    fun aCertificatePasswordAskedFromTheEditAndSignInSheetsReturnsToThem() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            val server = s.app.connection!!.server
            s.onUi { editServer(s.app, server) }
            s.settle(600).close()
            s.type(s.named(SignInWords.optionalName(SignInWords.SERVER_NAME)).single { it.inPopup() }, "Living room")
            s.askReturnsToTheSheet("Edit ${server.name}", file) { form -> assertEquals("the name still typed", "Living room", form.label) }
            s.onUi {
                s.app.popups.close()
                signInTo(s.app, server)
            }
            s.settle(600).close()
            s.type(s.named(SignInWords.PASSWORD).single { it.inPopup() }, "typed")
            s.askReturnsToTheSheet("Sign in to ${server.name}", file) { form -> assertEquals("the password still typed", "typed", form.password) }
        }
    }

    // The certificate's password ask shades the page as every sheet does:
    // over the sign-in page with Advanced open the window round it darkens
    // by a quarter or more, and asked from the Add sheet the shade round the
    // cards is the sheet's own, neither lifted nor doubled. Red at BASE by
    // the ask asking for no shade: the sign-in page round it stays as bright
    // as before, and from the sheet the page comes back unshaded.
    @Test(timeout = 180_000)
    fun theCertificatePasswordAskShadesThePageAsTheSheetsDo() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        page().use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            val bare = s.settleStill(600, quiet = 200)
            s.chooseNeedingPassword(s.app.signInForm, file)
            val asking = s.settleStill(600, quiet = 200)
            val ask = s.popupArea()
            val before = s.lightnessRound(bare, ask, 64)
            val after = s.lightnessRound(asking, ask, 64)
            bare.close()
            asking.close()
            assertTrue("the sign-in page round the ask: L* %.1f, then %.1f".format(before, after), after <= before * 0.75)
        }
        signedIn().use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            val sheet = s.popupArea()
            val shaded = s.settleStill(600, quiet = 200)
            s.chooseNeedingPassword(s.sheetForm(), file)
            val asking = s.settleStill(600, quiet = 200)
            val under = s.lightnessRound(shaded, sheet, 64)
            val over = s.lightnessRound(asking, sheet, 64)
            shaded.close()
            asking.close()
            assertEquals("the shade round the sheet, then round the ask over it", under, over, 0.5)
        }
    }

    // While the certificate's password is asked over a family link's sheet,
    // that sheet is still open under it: a switch that needs a password opens
    // nothing over them and says why, and Cancel brings the link's sheet
    // back. Red at BASE by the ask having replaced the link's sheet: the
    // switch's own sheet then opens over everything.
    @Test(timeout = 120_000)
    fun aSwitchOpensNothingOverTheAskOnALinksSheet() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn().use { s ->
            s.onUi { s.app.openFamilyLink(FamilyInviteLink(s.server.address, "t1")) }
            s.settle(600).close()
            val link = s.app.popups.request
            assertTrue("the link's sheet is open", link?.link == true)
            s.chooseNeedingPassword(s.sheetForm(), file)
            val asking = s.app.popups.request
            val server = s.app.connection!!.server
            s.onUi { askSignIn(s.app, SheetKind.SignIn, server, null) }
            s.render(3)
            assertSame("nothing opens over them", asking, s.app.popups.request)
            assertEquals(SignInWords.askWhileSignInOpen(server.name), s.app.notice)
            s.activate("Cancel")
            s.settle(600).close()
            assertSame("the link's sheet is back", link, s.app.popups.request)
        }
    }

    // Settings, opened while the certificate's password is asked over a
    // server sheet, closes both, as it closes any pop-up open. Red with
    // Settings closing only the ask, which left the sheet open over Settings.
    @Test(timeout = 120_000)
    fun settingsClosesTheAskAndTheSheetUnderIt() {
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn().use { s ->
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            s.chooseNeedingPassword(s.sheetForm(), file)
            assertTrue(s.app.popups.open)
            s.onUi { s.app.openSettings() }
            s.render(3)
            assertNull("nothing left open", s.app.popups.request)
        }
    }

    // Change password's main button waits, drawn off, until all three
    // passwords hold words, as every sign-in sheet's main button waits for
    // its required fields; then it can be pressed. Red with it lit as the
    // sheet opens.
    @Test(timeout = 120_000)
    fun changePasswordsButtonWaitsForItsThreeFields() {
        signedIn().use { s ->
            s.onUi { changePassword(s.app) }
            s.settle(600).close()
            fun off() = s.controlInPopup("Change password").config.contains(SemanticsProperties.Disabled)
            assertTrue("off as it opens", off())
            s.type(s.named("Current password").single { it.inPopup() }, "pw")
            s.type(s.named("New password").single { it.inPopup() }, "a new one")
            assertTrue("off with one left empty", off())
            s.type(s.named("New password again").single { it.inPopup() }, "a new one")
            assertFalse("ready once all three hold words", off())
        }
    }

    // Enter in Change password before all three hold words still asks: the
    // note says what is missing, nothing is sent and the sheet stays. Red
    // with the sheet's change refusing in silence until it is ready.
    @Test(timeout = 120_000)
    fun enterInChangePasswordEarlySaysWhatIsMissing() {
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { changePassword(s.app) }
            s.settle(600).close()
            s.type(s.named("Current password").single { it.inPopup() }, "pw")
            assertEquals("Current password", s.focused()?.name())
            val sent = s.server.endpoints().size
            s.press(Key.Enter)
            s.settle(400).close()
            assertEquals("the note says what is missing", 1, s.withText("Enter a new password.").count { it.inPopup() })
            assertTrue("the sheet stays", s.app.popups.open)
            assertFalse(s.app.changingPassword)
            assertTrue("nothing sent", s.server.endpoints().drop(sent).none { it.contains("changePassword", ignoreCase = true) })
        }
    }

    // Pictures for the polish pass: the certificate's password asked from
    // the Add sheet (the sheet before, the ask over it, the sheet back after
    // Cancel and after Use it), over the sign-in page with Advanced open (the
    // page before and the ask with its shade), and Change password empty and
    // filled in Octo and Paper.
    @Test(timeout = 300_000)
    fun picturesOfTheCertificateAskInItsPlaces() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val file = CertFixtures.copy("rsa2048.p12", folder.newFolder())
        signedIn().use { s ->
            s.onUi { s.keyboard.keyboard = false }
            s.onUi { addServer(s.app) }
            s.settle(600).close()
            s.type(s.named(SignInWords.SERVER_ADDRESS).single { it.inPopup() }, "music.example.com")
            val form = s.sheetForm()
            s.onUi { form.advancedOpen = true }
            s.settle(600).close()
            s.bringUp(s.controlInPopup(SignInWords.CERT_CHOOSE))
            s.giveKeyboard(s.controlInPopup(SignInWords.CERT_CHOOSE))
            s.shot("polish-cert-add-before")
            s.chooseNeedingPassword(form, file)
            s.shot("polish-cert-ask-over-add")
            s.activate("Cancel")
            s.shot("polish-cert-add-after-cancel")
            s.chooseNeedingPassword(form, file)
            s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inOpenPopup() }, CertFixtures.PASSWORD)
            val asking = s.app.popups.request
            s.activate(SignInWords.CERT_PASSWORD_USE)
            s.until { s.app.popups.request !== asking }
            assertTrue(form.certificateDraft is CertificateDraft.Use)
            s.shot("polish-cert-add-after-use")
        }
        page().use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            s.shot("polish-cert-page-before")
            s.chooseNeedingPassword(s.app.signInForm, file)
            s.shot("polish-cert-ask-over-page")
        }
        signedIn().use { s ->
            for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Paper)) {
                s.onUi {
                    s.app.popups.close()
                    s.app.settings.pickTheme(theme)
                }
                s.settle(600).close()
                s.onUi { changePassword(s.app) }
                s.shot("polish-change-password-empty-${theme.name.lowercase()}")
                s.type(s.named("Current password").single { it.inPopup() }, "pw")
                s.type(s.named("New password").single { it.inPopup() }, "a new one")
                s.type(s.named("New password again").single { it.inPopup() }, "a new one")
                s.shot("polish-change-password-filled-${theme.name.lowercase()}")
            }
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

    // The page with the words set 130% larger, closed and with Advanced
    // opened from its head.
    @Test(timeout = 300_000)
    fun picturesAtLargerText() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        A11yScene(folder.newFolder(), height = 900, reduceMotion = true, signedOut = true, textScale = 1.3f).use { s ->
            s.shot("polish-page-text-130")
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.shot("polish-page-text-130-advanced")
        }
    }

    // Where Advanced's opening glide ends, opened from its head: in a 1600
    // window, where the open card sits in the middle, in a 900 one, where it
    // is lifted to the top, and in a 600 one scrolled down to the head.
    @Test(timeout = 300_000)
    fun picturesOfTheOpenGlide() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (height in listOf(1_600, 900)) {
            page(height = height).use { s ->
                s.settle(600).close()
                s.click(s.text(SignInWords.ADVANCED))
                s.settle(600).close()
                assertTrue(s.app.signInForm.advancedOpen)
                s.shot("polish-page-open-$height")
            }
        }
        page(height = 600).use { s ->
            s.settle(600).close()
            val head = s.text(SignInWords.ADVANCED)
            s.scrollToEnd(head)
            s.shot("polish-page-open-600-before")
            s.click(head)
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            s.shot("polish-page-open-600")
        }
    }

    // The card's scrollbar shown by the pointer over the card, with Advanced
    // open in a 900 window: at rest after the glide, and at the scroll's end.
    @Test(timeout = 300_000)
    fun picturesOfTheCardsScrollbar() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page(height = 900).use { s ->
            s.settle(600).close()
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(600).close()
            val (top, bottom) = s.cardEdges()
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(s.width / 2f, (top + bottom) / 2f)) }
            s.shot("polish-page-scrollbar-rest", ms = 300)
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(5f, s.height - 5f)) }
            s.settle(600).close()
            s.shot("polish-page-scrollbar-resting", ms = 300)
            s.scrollToEnd(s.text("Octo"))
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(s.width / 2f, (top + bottom) / 2f)) }
            s.shot("polish-page-scrollbar-end", ms = 300)
        }
    }

    // The sheets' feet with the words 130% larger (Add, Edit, Sign in and
    // Change password), and the certificate password's ask and the changed
    // certificate question at the usual size.
    @Test(timeout = 300_000)
    fun picturesOfTheSheetsFeet() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        A11yScene(folder.newFolder(), reduceMotion = true, textScale = 1.3f).use { s ->
            s.onUi { addServer(s.app) }
            s.shot("polish-sheet-add-130")
            s.onUi {
                s.app.popups.close()
                editServer(s.app, s.app.connection!!.server)
            }
            s.shot("polish-sheet-edit-130")
            s.onUi {
                s.app.popups.close()
                signInTo(s.app, s.app.connection!!.server)
            }
            s.shot("polish-sheet-sign-in-130")
            s.onUi {
                s.app.popups.close()
                changePassword(s.app)
            }
            s.shot("polish-sheet-change-password-130")
        }
        signedIn().use { s ->
            s.onUi { askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null) }
            s.settle(600).close()
            s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inPopup() }, "pw")
            s.shot("polish-sheet-certificate-password")
            s.onUi {
                s.app.popups.close()
                askAboutChangedCertificate(s.app.popups, s.app.accounts.security, CertificateQuestion("music.example.com", "AB".repeat(32)))
            }
            s.shot("polish-sheet-trust")
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

    // Scrolls the node's scroll to its end, gliding as bringUp does, and
    // gives how far that is.
    private fun A11yScene.scrollToEnd(node: SemanticsNode): Float {
        val scroll = scrollOf(node)
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        val goal = range.maxValue()
        onUi { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, goal - range.value()) }
        render(1)
        val end = System.currentTimeMillis() + 10_000
        do {
            check(System.currentTimeMillis() < end) { "waited too long" }
            frameAt(System.nanoTime()).close()
        } while (abs(range.value() - goal) > 0.5f)
        return goal
    }

    // Scrolls the node's scroll back to its start, gliding as bringUp does:
    // the card back at its pin with its words at their top.
    private fun A11yScene.scrollToStart(node: SemanticsNode) {
        val scroll = scrollOf(node)
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        onUi { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, -range.value()) }
        render(1)
        val end = System.currentTimeMillis() + 10_000
        do {
            check(System.currentTimeMillis() < end) { "waited too long" }
            frameAt(System.nanoTime()).close()
        } while (range.value() > 0.5f)
    }

    // The rows from `top` until `bottom` where the card shows the page
    // unfrosted. Taking the card's film (0xFF1A1A1E at 0.79) off its pixels
    // leaves the page as the glass shows it, whose chroma (the spread of its
    // channels) the frost raises about 1.8 times over the bare page's beside
    // the card; a row counts when that is under `least`. Each row averages 13
    // columns 6 to 18 in from the card's left edge, against 13 columns 6 to 18
    // out from it.
    private fun unfrostedRows(image: Image, cardLeft: Int, top: Int, bottom: Int, least: Double): List<Int> {
        val bitmap = Bitmap.makeFromImage(image)
        fun mean(from: Int, y: Int): DoubleArray {
            val sum = DoubleArray(3)
            for (x in from until from + 13) {
                val c = bitmap.getColor(x, y)
                sum[0] += (c shr 16 and 0xFF).toDouble()
                sum[1] += (c shr 8 and 0xFF).toDouble()
                sum[2] += (c and 0xFF).toDouble()
            }
            return DoubleArray(3) { sum[it] / 13 }
        }
        val film = doubleArrayOf(26.0, 26.0, 30.0)
        val rows = (top until bottom).filter { y ->
            val card = mean(cardLeft + 6, y)
            val seen = DoubleArray(3) { (card[it] - 0.79 * film[it]) / 0.21 }
            val page = mean(cardLeft - 18, y)
            (seen.max() - seen.min()) / (page.max() - page.min()) < least
        }
        bitmap.close()
        return rows
    }

    // The card's glass stays frosted however far its words scroll: on every
    // row of the card clear of its soft edges, the page seen through the glass
    // has 1.7 times the bare page's chroma or more. The card is 480 wide in
    // the middle of the window. Measured once the scroll has lifted the card
    // from its pin to the window's top pad and taken its words to their end.
    // Red when the glass scrolls with the page (past the rows its frost was
    // first drawn for: film over the raw page, about 1.0), or when the card
    // stays at its pin.
    @Test(timeout = 120_000)
    fun theCardStaysFrostedWhenItsWordsScroll() {
        page().use { s ->
            s.settle(600).close()
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            val title = s.text(SignInWords.ADVANCED)
            val far = s.scrollToEnd(title)
            assertTrue("the words scroll $far", far > 200f)
            val image = s.settle(300)
            val view = scrollOf(title).boundsInRoot
            val flat = unfrostedRows(image, (s.width - 480) / 2, view.top.toInt() + 24, view.bottom.toInt() - 24, 1.7)
            image.close()
            assertTrue("${flat.size} rows unfrosted, the first ${flat.take(5)}", flat.isEmpty())
            assertEquals("the card lifted to the window's top pad", pagePad, s.cardTop(), 0.5f)
        }
    }

    // A wheel turned over the page beside the card scrolls the card's words,
    // as it does over the card. Red when only the card takes the wheel (the
    // words stay at the top).
    @Test(timeout = 120_000)
    fun aWheelBesideTheCardScrollsItsWords() {
        page(height = 600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            val title = s.text(SignInWords.ADVANCED)
            assertEquals(0f, s.scrolled(title), 0.5f)
            s.onUi { repeat(3) { s.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 300f), scrollDelta = Offset(0f, 1f)) } }
            s.settle(600).close()
            assertTrue("scrolled ${s.scrolled(title)}", s.scrolled(title) > 0f)
        }
    }

    // The top of the room the card lifts to in a window 720 or taller: the
    // window's title bar, then the page's 32 over the card.
    private val pagePad = FrameSize.TitleBar.value + 32f

    // The top and bottom, in the window, of the card's glass, found by the
    // tag SignInPage gives it.
    private fun A11yScene.cardEdges(): Pair<Float, Float> {
        val card = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "sign-in-card" }
        assertNotNull("the card's glass is tagged", card)
        return card!!.positionInRoot.y to card.positionInRoot.y + card.size.height
    }

    private fun A11yScene.cardTop(): Float = cardEdges().first

    // One turn of the wheel at `at`, toward the end (1) or the start (-1),
    // drawn on the real clock until `scroll` has been still for four frames.
    private fun A11yScene.wheel(at: Offset, toward: Float, scroll: SemanticsNode) {
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        onUi { scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, toward)) }
        val begin = System.currentTimeMillis()
        var last = range.value()
        var still = 0
        while (still < 4 || System.currentTimeMillis() - begin < 150) {
            check(System.currentTimeMillis() - begin < 10_000) { "waited too long" }
            frameAt(System.nanoTime()).close()
            Thread.sleep(16)
            val now = range.value()
            still = if (abs(now - last) < 0.01f) still + 1 else 0
            last = now
        }
    }

    // Where a turn of the wheel goes: on odd turns over the card, in its
    // left margin halfway down, and on even ones over the page beside it.
    private fun A11yScene.wheelSpot(turn: Int): Offset {
        val (top, bottom) = cardEdges()
        return if (turn % 2 == 1) Offset((width - 480) / 2f + 16f, (top + bottom) / 2f) else Offset(200f, height / 2f)
    }

    // Advanced open on a tall window with more than fits even once the card
    // is lifted: three headers and a sign-in page with its two fields.
    private fun A11yScene.tallAdvanced() {
        threeHeaders()
        onUi { app.signInForm.pickGateway(GatewayKind.Password) }
        settle(600).close()
    }

    // Opens Advanced with a click in a tall window, where the open card
    // glides up to sit balanced, then gives it more than fits (three headers
    // and a sign-in page's two fields): the card stays where it sat, under
    // the window's top pad, with its words running past the room. Gives where
    // the card sat.
    private fun A11yScene.balancedThenTall(): Float {
        settle(600).close()
        click(text(SignInWords.ADVANCED))
        settle(600).close()
        val balanced = cardTop()
        tallAdvanced()
        assertEquals("the card stays where it sat as rows are added", balanced, cardTop(), 1f)
        assertTrue("it sits under the pad at $balanced", balanced > pagePad + 40f)
        return balanced
    }

    // In a tall window with Advanced open and the card under the top pad
    // (it sat balanced, then rows were added; a wheel turned back never takes
    // an open card under its balanced place, so this is the one way there),
    // the wheel's first turns toward the end lift the whole card, its words
    // going up with it and never moving inside it, until its top meets the
    // window's top pad; only then do the words move inside it. Over the card
    // and beside it alike, the words move once for each pixel scrolled. Red
    // with the card held where it rests, where the words move inside it from
    // the first turn.
    @Test(timeout = 120_000)
    fun theWheelLiftsTheCardBeforeItsWordsMoveInsideIt() {
        page(height = 1_600).use { s ->
            val balanced = s.balancedThenTall()
            val title = s.text("Octo")
            val scroll = scrollOf(title)
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("more than fits once lifted: ${range.maxValue() - range.value()} left past a card at $balanced", range.maxValue() - range.value() > balanced - pagePad + 100f)
            val rest = title.positionInRoot.y
            val from = range.value()
            val inside = rest - s.cardTop()
            var turn = 0
            while (title.positionInRoot.y - s.cardTop() > inside - 100f) {
                check(turn < 60) { "the wheel stopped at ${range.value()} of ${range.maxValue()}" }
                turn++
                s.wheel(s.wheelSpot(turn), 1f, scroll)
                val top = s.cardTop()
                val y = title.positionInRoot.y
                assertEquals("turn $turn: the words move once per pixel scrolled", rest - (range.value() - from), y, 0.5f)
                if (top > pagePad + 0.5f) assertEquals("turn $turn: the card at $top lifts with its words still inside it", inside, y - top, 0.5f)
            }
            assertTrue("it took more than one turn", turn > 1)
            assertEquals("the card's top meets the pad", pagePad, s.cardTop(), 0.5f)
        }
    }

    // Opened with a click in a tall window, the card glides up to sit
    // balanced, and there it stays while Advanced is open: ten wheel turns
    // back, over the card and over the page beside it, leave its top within 1
    // of where the glide left it. Red with the scroll free to go back past
    // that place, where the first turn lowers the card toward its rest (431
    // of bare page over it at 1600).
    @Test(timeout = 120_000)
    fun aWheelBackLeavesTheOpenCardWhereItsGlideEnded() {
        page(height = 1_600).use { s ->
            s.settle(600).close()
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(600).close()
            assertTrue(s.app.signInForm.advancedOpen)
            val (top, bottom) = s.cardEdges()
            val (roomTop, roomBottom) = s.room()
            assertEquals("balanced once the glide ends: ${top - roomTop} over, ${roomBottom - bottom} under", top - roomTop, roomBottom - bottom, 1f)
            val scroll = scrollOf(s.text("Octo"))
            for (turn in 1..10) {
                s.wheel(s.wheelSpot(turn), -1f, scroll)
                assertEquals("turn $turn back: the card's top", top, s.cardTop(), 1f)
            }
        }
    }

    // How many pixels of the card's bar lane (its right 14 columns, top to
    // foot) change when the pointer moves over the middle of the card, from a
    // frame with the pointer away from it.
    private fun A11yScene.barShown(): Int {
        val (top, bottom) = cardEdges()
        val right = (width + 480) / 2
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(5f, height - 5f)) }
        val hidden = settle(1_800)
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, (top + bottom) / 2f)) }
        val shown = settle(300)
        val before = Bitmap.makeFromImage(hidden)
        val after = Bitmap.makeFromImage(shown)
        var changed = 0
        for (x in right - 14 until right) {
            for (y in top.toInt() until bottom.toInt()) {
                if (distance(before.getColor(x, y), after.getColor(x, y)) > 24.0) changed++
            }
        }
        before.close()
        after.close()
        hidden.close()
        shown.close()
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(5f, height - 5f)) }
        return changed
    }

    // The balanced open card shows all its words, so a pointer moving over it
    // wakes no thumb: the card's bar counts from where a scroll back stops,
    // and from there the card fits. Red with the bar counting from the
    // scroll's start (a thumb at 83% of the track, saying there is more above
    // a card that shows everything).
    @Test(timeout = 120_000)
    fun theBalancedOpenCardWakesNoThumb() {
        page(height = 1_600).use { s ->
            s.settle(600).close()
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            val (top, bottom) = s.cardEdges()
            val (roomTop, roomBottom) = s.room()
            assertEquals("balanced: ${top - roomTop} over, ${roomBottom - bottom} under", top - roomTop, roomBottom - bottom, 1f)
            assertEquals("pixels of a thumb over the balanced card", 0, s.barShown())
        }
    }

    // With more than fits once lifted, the wheel turned back brings the words
    // home to the top of the lifted card and stops there: on every turn the
    // card's top stays at the window's top pad, and it never lowers toward its
    // pin while Advanced is open. Red with the scroll free to go back past
    // the lift, where the card comes down to its pin once the words are home.
    @Test(timeout = 120_000)
    fun theWheelBackTakesTheWordsHomeAndStopsAtThePad() {
        page(height = 1_600).use { s ->
            s.settle(600).close()
            val inside = s.text("Octo").positionInRoot.y - s.cardTop()
            s.tallAdvanced()
            val title = s.text("Octo")
            val scroll = scrollOf(title)
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            s.scrollToEnd(title)
            assertEquals("lifted to the pad", pagePad, s.cardTop(), 0.5f)
            var turn = 0
            var last = range.value()
            var still = 0
            while (still < 3) {
                check(turn < 80) { "the wheel kept going at ${range.value()} of ${range.maxValue()}" }
                turn++
                s.wheel(s.wheelSpot(turn), -1f, scroll)
                assertEquals("turn $turn back: the card's top stays at the pad", pagePad, s.cardTop(), 0.5f)
                still = if (abs(range.value() - last) < 0.5f) still + 1 else 0
                last = range.value()
            }
            assertEquals("the words are home at the top of the card", inside, title.positionInRoot.y - s.cardTop(), 0.5f)
        }
    }

    // A failure that met a sign-in page, in a tall window, lifts the card
    // toward the window's top as it brings the group up, as the page did
    // before the glass held still: the ringed group (its title and its error
    // note) and the pointer over the buttons all show on the card, clear of
    // its soft edges. Red with the card held at its pin, where the words
    // scroll inside it until the pointer leaves the card.
    @Test(timeout = 120_000)
    fun aCalloutInATallWindowShowsTheGroupAndThePointer() {
        page(height = 1_600).use { s ->
            s.settle(600).close()
            val pin = s.cardTop()
            s.onUi {
                val form = s.app.signInForm
                form.typeAddress("https://music.example.com")
                form.result = false to GatewayWords.failure(Gateway.Authentik, false)
                form.failed(form.attemptAddress, Gateway.Authentik)
            }
            s.until { s.app.signInForm.showsCallout && !s.app.signInForm.calloutPending }
            s.settle(300).close()
            val (top, bottom) = s.cardEdges()
            assertTrue("the card lifted from its pin at $pin to $top", top < pin - 0.5f)
            fun shows(node: SemanticsNode, what: String) {
                val y = node.positionInRoot.y
                assertTrue("$what at $y to ${y + node.size.height}, on a card from $top to $bottom", y >= top + PageFade.value && y + node.size.height <= bottom - PageFade.value)
            }
            shows(s.text(GatewayWords.POINTER), "the pointer")
            shows(s.text(SignInWords.GROUP_GETTING_THERE), "the group's title")
            shows(s.text(GatewayWords.inGroup(s.app.signInForm.result!!.second)), "the group's error")
        }
    }

    // Advanced closing under a lifted card brings the card back down to its
    // pin as the groups fade, its top never rising on the way. Drawn with
    // motion on. Red when the card lets go of its pin as Advanced closes (it
    // fills the window while the groups fade out, then drops back).
    @Test(timeout = 120_000)
    fun closingAdvancedBringsALiftedCardDownToItsPin() {
        A11yScene(folder.newFolder(), height = 900, signedOut = true).use { s ->
            s.settle(800).close()
            val pin = s.cardTop()
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            val title = s.text("Octo")
            s.placeTop(title, title.positionInRoot.y - 60f)
            s.settle(300).close()
            val tops = mutableListOf(s.cardTop())
            s.onUi { s.app.signInForm.advancedOpen = false }
            val end = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < end) {
                s.frameAt(System.nanoTime()).close()
                tops += s.cardTop()
                Thread.sleep(15)
            }
            val rises = tops.zipWithNext().filter { (a, b) -> b < a - 0.5f }
            assertTrue("the card's top rose ${rises.take(3)} on its way from ${tops.first()} to ${tops.last()}", rises.isEmpty())
            assertEquals("back at its pin", pin, tops.last(), 1f)
        }
    }

    // The lift in a tall window: Advanced open with the card where it sat
    // balanced before rows were added (under the top pad), the card lifted
    // half way from there, and lifted to the top with its words moved 120
    // inside it.
    @Test(timeout = 300_000)
    fun picturesOfTheLift() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page(height = 1_600).use { s ->
            val balanced = s.balancedThenTall()
            s.shot("polish-page-lift-rest")
            val title = s.text("Octo")
            s.placeTop(title, title.positionInRoot.y - ((balanced - pagePad) / 2f).toInt())
            s.shot("polish-page-lift-half")
            val range = scrollOf(title).config[SemanticsProperties.VerticalScrollAxisRange]
            val inside = minOf(120f, range.maxValue() - range.value() - (s.cardTop() - pagePad))
            s.placeTop(title, title.positionInRoot.y - (s.cardTop() - pagePad) - inside)
            assertEquals(pagePad, s.cardTop(), 0.5f)
            s.shot("polish-page-lift-top")
        }
    }

    // A wheel turned back on the open card: the balanced card in a 1600
    // window after three turns back, at rest and with the pointer over it;
    // and the card with more than fits, wheeled back from its end until the
    // wheel moves nothing.
    @Test(timeout = 300_000)
    fun picturesOfTheWheelBack() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        page(height = 1_600).use { s ->
            s.settle(600).close()
            s.click(s.text(SignInWords.ADVANCED))
            s.settle(600).close()
            val scroll = scrollOf(s.text("Octo"))
            for (turn in 1..3) s.wheel(s.wheelSpot(turn), -1f, scroll)
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(5f, s.height - 5f)) }
            s.shot("polish-page-open-1600-wheel-back", ms = 1_800)
            val (top, bottom) = s.cardEdges()
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(s.width / 2f, (top + bottom) / 2f)) }
            s.shot("polish-page-open-1600-pointer", ms = 300)
        }
        page(height = 1_600).use { s ->
            s.settle(600).close()
            s.tallAdvanced()
            val title = s.text("Octo")
            val scroll = scrollOf(title)
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            s.scrollToEnd(title)
            var turn = 0
            var last = -1f
            while (abs(range.value() - last) >= 0.5f && turn < 60) {
                last = range.value()
                turn++
                s.wheel(s.wheelSpot(turn), -1f, scroll)
            }
            s.onUi { s.scene.sendPointerEvent(PointerEventType.Move, Offset(5f, s.height - 5f)) }
            s.shot("polish-page-lift-wheel-back", ms = 1_800)
        }
    }

    // The filled button off and ready: each light theme's and Octo's Add
    // sheet before anything is typed and once the form is filled in.
    @Test(timeout = 300_000)
    fun picturesOfTheFilledButtonOffAndReady() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        signedIn().use { s ->
            for (theme in listOf(ThemeChoice.Paper, ThemeChoice.Daylight, ThemeChoice.Octo)) {
                s.onUi {
                    s.app.popups.close()
                    s.app.settings.pickTheme(theme)
                }
                s.settle(600).close()
                s.onUi { addServer(s.app) }
                s.shot("polish-button-off-${theme.name.lowercase()}")
                s.type(s.named(SignInWords.SERVER_ADDRESS).single { it.inPopup() }, s.server.address)
                s.type(s.named(SignInWords.USERNAME).single { it.inPopup() }, "winters")
                s.type(s.named(SignInWords.PASSWORD).single { it.inPopup() }, "pw")
                s.shot("polish-button-ready-${theme.name.lowercase()}")
            }
        }
    }

    // The pop-ups that ask for a password, as sheets: Change password and the
    // certificate password's ask in Octo at the usual size and at 130%, on
    // Paper, and each with its notes (a weak new password and the passwords
    // not matching; a file that can't be opened).
    @Test(timeout = 300_000)
    fun picturesOfThePopupForms() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), reduceMotion = true, textScale = scale).use { s ->
                val size = if (scale == 1f) "" else "-130"
                s.onUi { changePassword(s.app) }
                s.shot("polish-popup-change-password$size")
                s.onUi {
                    s.app.popups.close()
                    askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
                }
                s.shot("polish-popup-certificate-password$size")
                s.onUi { s.app.popups.close() }
            }
        }
        signedIn().use { s ->
            s.onUi { s.app.settings.pickTheme(ThemeChoice.Paper) }
            s.settle(600).close()
            s.onUi { changePassword(s.app) }
            s.shot("polish-popup-change-password-paper")
            s.type(s.named("Current password").single { it.inPopup() }, "pw")
            s.type(s.named("New password").single { it.inPopup() }, "abc")
            s.type(s.named("New password again").single { it.inPopup() }, "abd")
            s.click(s.inPopup("Change password"))
            s.shot("polish-popup-change-password-notes")
            s.onUi {
                s.app.popups.close()
                askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
            }
            s.shot("polish-popup-certificate-password-paper")
            s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inPopup() }, "pw")
            s.click(s.inPopup(SignInWords.CERT_PASSWORD_USE))
            s.shot("polish-popup-certificate-password-error", ms = 1_800)
        }
    }

    // WCAG's contrast between two colors.
    private fun contrast(a: Int, b: Int): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    // The fill of the button `gap` right of the open pop-up's Cancel, 12 in
    // from its left end, against the pop-up 8 in from its left edge, level
    // with the buttons' middle.
    private fun A11yScene.fillRightOfCancel(gap: Float): Double {
        val cancel = named("Cancel").single { it.inPopup() }
        val y = (cancel.positionInRoot.y + cancel.size.height / 2f).toInt()
        val image = settleStill(500, quiet = 150)
        val popup = pixel(image, popupLeft().toInt() + 8, y)
        val button = pixel(image, (cancel.positionInRoot.x + cancel.size.width + gap).toInt() + 12, y)
        image.close()
        return contrast(button, popup)
    }

    // Every sign-in pop-up's main button is the page's filled button, the
    // loudest thing in its foot: its fill stands 1.8:1 or more off the pop-up
    // (3:1 measured on the Edit sheet), in the Edit sheet, Change password and
    // the certificate password's ask. Red with the lit capsules (about 1:1).
    @Test(timeout = 120_000)
    fun everySignInSheetsMainButtonIsTheFilledButton() {
        signedIn().use { s ->
            s.onUi { editServer(s.app, s.app.connection!!.server) }
            s.settle(600).close()
            val save = s.fillRightOfCancel(Space.M.value)
            s.onUi {
                s.app.popups.close()
                changePassword(s.app)
            }
            s.settle(600).close()
            val change = s.fillRightOfCancel(Space.M.value)
            s.onUi {
                s.app.popups.close()
                askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
            }
            s.settle(600).close()
            s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inPopup() }, "pw")
            val use = s.fillRightOfCancel(Space.M.value)
            assertTrue("Save $save:1, Change password $change:1, Use $use:1", save >= 1.8 && change >= 1.8 && use >= 1.8)
        }
    }

    // The open pop-up's main button, named by its words, is as wide as a
    // sheet's main button may least be and never narrower than the Cancel
    // beside it, and its foot still fits the pop-up: the button ends inside
    // it, and Cancel (and Test connection before it) end before the next one
    // starts.
    private fun A11yScene.mainButtonFits(words: String, how: String) {
        val button = generateSequence(inPopup(words)) { it.parent }.first { it.clickable }
        val popup = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }
        val left = button.positionInRoot.x
        val right = left + button.size.width
        assertTrue("$words $how is ${button.size.width} wide", button.size.width >= SheetMainButton.value)
        assertTrue("$words $how ends at $right, past the pop-up's ${popup.positionInRoot.x + popup.size.width}", right <= popup.positionInRoot.x + popup.size.width)
        val cancel = named("Cancel").single { it.inPopup() }
        val cancelButton = generateSequence(cancel) { it.parent }.first { it.clickable }
        assertTrue("$words $how is ${button.size.width}, narrower than Cancel's ${cancelButton.size.width}", button.size.width >= cancelButton.size.width)
        assertTrue("Cancel $how runs into $words", cancel.positionInRoot.x + cancel.size.width <= left)
        // Test connection ends before Cancel on their line, or, where the
        // three do not fit on one (a wider system face), sits on a line of
        // its own above them.
        named("Test connection").singleOrNull { it.inPopup() }?.let { test ->
            val before = test.positionInRoot.x + test.size.width <= cancel.positionInRoot.x
            val above = test.positionInRoot.y + test.size.height <= cancelButton.positionInRoot.y
            assertTrue("Test connection $how runs into Cancel", before || above)
        }
    }

    // Every sign-in pop-up's main button is 112 wide or more and never
    // narrower than its Cancel, at the usual text size and at 130%, and its
    // foot still fits: Add, Save and Sign in in the server sheets, Change
    // password, the certificate password's Use it, and Trust in the
    // changed-certificate question. Red with the buttons at least 96 (Add,
    // Save and Sign in 96 beside a 98 or 99 Cancel at 130%), and with them
    // sized to their words (Add 50 wide beside an 80 Cancel).
    @Test(timeout = 240_000)
    fun everySignInPopupsMainButtonHasItsLeastWidth() {
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), reduceMotion = true, textScale = scale).use { s ->
                val how = "at ${scale}x"
                s.onUi { addServer(s.app) }
                s.settle(600).close()
                s.mainButtonFits("Add", how)
                s.onUi {
                    s.app.popups.close()
                    editServer(s.app, s.app.connection!!.server)
                }
                s.settle(600).close()
                s.mainButtonFits("Save", how)
                s.onUi {
                    s.app.popups.close()
                    signInTo(s.app, s.app.connection!!.server)
                }
                s.settle(600).close()
                s.mainButtonFits("Sign in", how)
                s.onUi {
                    s.app.popups.close()
                    changePassword(s.app)
                }
                s.settle(600).close()
                s.mainButtonFits("Change password", how)
                s.onUi {
                    s.app.popups.close()
                    askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
                }
                s.settle(600).close()
                s.type(s.named(SignInWords.CERT_PASSWORD_FIELD).single { it.inPopup() }, "pw")
                s.mainButtonFits(SignInWords.CERT_PASSWORD_USE, how)
                s.onUi {
                    s.app.popups.close()
                    askAboutChangedCertificate(s.app.popups, s.app.accounts.security, CertificateQuestion("music.example.com", "AB".repeat(32)))
                }
                s.settle(600).close()
                s.mainButtonFits(SignInWords.TRUST_CONFIRM, how)
                s.onUi { s.app.popups.close() }
            }
        }
    }

    // How many rows the first letter of a line of words inks, as drawn: in
    // the letter's own box where the laid-out text gives it, else in the
    // first seven columns of the words (a page title's).
    private fun capRows(image: Image, words: SemanticsNode): Int {
        val laid = mutableListOf<TextLayoutResult>()
        words.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid)
        val box = words.area()
        val letter = laid.firstOrNull()?.getBoundingBox(0)
        val area = if (letter == null) {
            Area(box.left, box.top, box.left + 7, box.bottom)
        } else {
            Area(box.left + letter.left.toInt(), box.top + letter.top.toInt(), box.left + kotlin.math.ceil(letter.right).toInt(), box.top + kotlin.math.ceil(letter.bottom).toInt())
        }
        val rows = inkRows(image, area)
        return rows.last() - rows.first() + 1
    }

    // Capitals that stand on the baseline, with nothing hanging under it.
    private val standing = ('A'..'Z').toSet() - setOf('J', 'Q')

    // The open pop-up's title is its loudest words: its capital stands taller
    // than the capital starting any other words shown whole in it, as drawn.
    // Words inside an entry (its hint, under the caret) are the entry's, not
    // a level of the pop-up's.
    private fun A11yScene.titleIsLoudest(title: String, how: String) {
        val image = settle(700)
        val entries = nodes().filter { it.config.contains(SemanticsProperties.EditableText) }.map { it.boundsInRoot }
        val shown = nodes().filter { node ->
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            words.firstOrNull() in standing && node.inPopup() && node.size.height > 0 &&
                node.boundsInRoot.height >= node.size.height - 0.5f && entries.none { it.contains(node.boundsInRoot.center) }
        }
        val head = shown.filter { node -> node.config[SemanticsProperties.Text].joinToString(" ") { it.text } == title }.minBy { it.positionInRoot.y }
        val tall = capRows(image, head)
        val others = (shown - head).map { node -> node.config[SemanticsProperties.Text].joinToString(" ") { it.text } to capRows(image, node) }
        image.close()
        assertTrue("$how: '$title' shows other words", others.isNotEmpty())
        val next = others.maxBy { it.second }
        println("TITLE $how '$title' $tall rows, next '${next.first.take(40)}' ${next.second}")
        assertTrue("$how: '$title' capital is $tall rows, '${next.first}' ${next.second}", tall > next.second)
    }

    // Every sign-in pop-up's title is its loudest words, at the usual text
    // size and at 130%: the three server sheets, Change password and the
    // certificate password's ask (the form sheets' 24 bold title, 17 rows over
    // 12 at the usual size), and the changed-certificate question (the
    // question sheets' title, as Settings' own questions have). Red with the
    // pop-ups that ask for a password titled by a menu's caption, no taller
    // than the sentence and the buttons under it.
    @Test(timeout = 240_000)
    fun everySignInPopupsTitleIsItsLoudestWords() {
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), reduceMotion = true, textScale = scale).use { s ->
                val how = "at ${scale}x"
                val server = s.app.connection!!.server
                s.onUi { addServer(s.app) }
                s.titleIsLoudest("Add a server", how)
                s.onUi {
                    s.app.popups.close()
                    editServer(s.app, server)
                }
                s.titleIsLoudest("Edit ${server.name}", how)
                s.onUi {
                    s.app.popups.close()
                    signInTo(s.app, server)
                }
                s.titleIsLoudest("Sign in to ${server.name}", how)
                s.onUi {
                    s.app.popups.close()
                    changePassword(s.app)
                }
                s.titleIsLoudest("Change password", how)
                s.onUi {
                    s.app.popups.close()
                    askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
                }
                s.titleIsLoudest(SignInWords.CERT_PASSWORD_TITLE, how)
                s.onUi {
                    s.app.popups.close()
                    askAboutChangedCertificate(s.app.popups, s.app.accounts.security, CertificateQuestion("music.example.com", "AB".repeat(32)))
                }
                s.titleIsLoudest(SignInWords.TRUST_CHANGED_TITLE, how)
                s.onUi { s.app.popups.close() }
            }
        }
    }

    // A pop-up that asks for a password names each field once, over its
    // entry in a group's row, and never again as the entry's own hint, which
    // says the field is required: Change password's three fields and the
    // certificate password's one. Red with the names repeated as their
    // fields' hints ("Current password" over "Current password").
    @Test(timeout = 120_000)
    fun aPopupsFieldsSayTheirNamesOnce() {
        signedIn().use { s ->
            s.onUi { changePassword(s.app) }
            s.settle(600).close()
            for (name in listOf("Current password", "New password", "New password again")) {
                assertEquals("'$name' shown", 1, s.withText(name).count { it.inPopup() })
                assertEquals("an entry named '$name'", 1, s.named(name).count { it.inPopup() })
            }
            assertEquals("hints saying required", 3, s.withText(SignInWords.REQUIRED).count { it.inPopup() })
            s.onUi {
                s.app.popups.close()
                askCertificatePassword(s.app, s.app.signInForm, "identity.p12", Any(), null)
            }
            s.settle(600).close()
            assertEquals("'${SignInWords.CERT_PASSWORD_FIELD}' shown", 1, s.withText(SignInWords.CERT_PASSWORD_FIELD).count { it.inPopup() })
            assertEquals("its hint says required", 1, s.withText(SignInWords.REQUIRED).count { it.inPopup() })
            assertEquals("the line under its name", 1, s.withText(SignInWords.CERT_PASSWORD_HINT).count { it.inPopup() })
        }
    }

    // How many pixels change when the card's scrollbar shows, outside the
    // card's rounded corner at its top (or its foot) in the lane the bar runs
    // down: the corner's arc, 22 round, with a pixel's grace. The bar shows
    // while the pointer moves over the card, and the frame it is compared
    // with has the pointer away from the card. Also how many change inside
    // the card in that lane, 24 to 60 from that end, where the bar's end is.
    private fun A11yScene.barAroundTheCorner(atTop: Boolean): Pair<Int, Int> {
        val (top, bottom) = cardEdges()
        val right = (width + 480) / 2
        val radius = 22f
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(5f, height - 5f)) }
        val hidden = settle(600)
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, (top + bottom) / 2f)) }
        val shown = settle(300)
        val before = Bitmap.makeFromImage(hidden)
        val after = Bitmap.makeFromImage(shown)
        val cx = right - radius
        val cy = if (atTop) top + radius else bottom - radius
        var outside = 0
        var inside = 0
        for (x in right - 14 until right) {
            for (y in (top.toInt() - 2)..(bottom.toInt() + 1)) {
                val changed = distance(before.getColor(x, y), after.getColor(x, y)) > 24.0
                if (!changed) continue
                val dx = x + 0.5f - cx
                val dy = if (atTop) cy - (y + 0.5f) else y + 0.5f - cy
                val beyond = dx > 0f && dy > 0f && dx * dx + dy * dy > (radius + 1f) * (radius + 1f)
                val fromEnd = if (atTop) y - top else bottom - y
                if (beyond) outside++ else if (fromEnd in 24f..60f) inside++
            }
        }
        before.close()
        after.close()
        hidden.close()
        shown.close()
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(5f, height - 5f)) }
        return outside to inside
    }

    // The card's scrollbar keeps inside its rounded corners: shown with the
    // words at their top and again at their end, no pixel outside the
    // corner's arc changes. Red with the bar from 3 under the card's top,
    // where its end stands up to 8 outside the arc over the bare page.
    @Test(timeout = 120_000)
    fun theCardsScrollbarKeepsInsideItsCorners() {
        page(height = 600).use { s ->
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(600).close()
            val title = s.text("Octo")
            s.scrollToStart(title)
            val (topOutside, topInside) = s.barAroundTheCorner(atTop = true)
            assertTrue("the bar shows at the top: $topInside pixels", topInside > 20)
            assertEquals("pixels outside the top corner", 0, topOutside)
            s.scrollToEnd(title)
            val (footOutside, footInside) = s.barAroundTheCorner(atTop = false)
            assertTrue("the bar shows at the foot: $footInside pixels", footInside > 20)
            assertEquals("pixels outside the foot's corner", 0, footOutside)
        }
    }

    // The rows of the card's bar lane where its resting thumb draws: the
    // lane 4 to 7 in from the card's right edge standing 8 or more over the
    // glass 15 to 18 in (where no words are) in lightness, between the ends
    // of the bar's track. Drawn with the pointer away and the bar at rest.
    private fun A11yScene.restingRows(): Int {
        onUi { scene.sendPointerEvent(PointerEventType.Move, Offset(5f, height - 5f)) }
        val image = settle(1_800)
        val (top, bottom) = cardEdges()
        val right = (width + 480) / 2
        val bitmap = Bitmap.makeFromImage(image)
        fun mean(from: Int, y: Int): Double = (from until from + 4).map { lightness(bitmap.getColor(it, y)) }.average()
        val rows = (top.toInt() + 22 until bottom.toInt() - 22).count { y -> mean(right - 7, y) - mean(right - 18, y) >= 8.0 }
        bitmap.close()
        image.close()
        return rows
    }

    // The window height at which the closed card at 130% text runs past its
    // view by `by`, from a window short enough to hold the card to it: there
    // the view is the window less its margins and the card's words are the
    // view and its scroll, so the height holds in any system face (Segoe UI
    // runs past a 900 window by 1, Linux's DejaVu Sans fits in it). The
    // window measured in is 780: its page, under the 40 title bar, is just
    // over the 720 below which the card packs tighter.
    private fun closedCardRunsPastBy(by: Float): Int {
        val measured = 780
        return largerText(measured).use { s ->
            s.settle(600).close()
            val range = scrollOf(s.text("Octo")).config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
            assertTrue("the closed card runs past a $measured window by $range", range > by)
            (measured + range - by).roundToInt()
        }
    }

    // The card's resting thumb says there is more only while words are
    // hidden past the card's own padding: none on the closed card at 130%
    // text in a window it runs past by half its 32 of padding, and there on
    // the open card in that window, lifted with its words running on. Red
    // with the thumb shown whenever the card runs past at all (a rail down
    // the closed card's whole edge for about 1 of empty padding).
    @Test(timeout = 120_000)
    fun theRestingThumbShowsOnlyWhenWordsAreHidden() {
        largerText(closedCardRunsPastBy(16f)).use { s ->
            s.settle(600).close()
            val range = scrollOf(s.text("Octo")).config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("the closed card runs past its view by ${range.maxValue()}, under its padding", range.maxValue() > 0f && range.maxValue() < 32f)
            assertEquals("rows of a resting thumb on the closed card", 0, s.restingRows())
            s.onUi { s.app.signInForm.advancedOpen = true }
            s.settle(800).close()
            assertEquals("lifted to the pad", pagePad, s.cardTop(), 0.5f)
            val open = s.restingRows()
            assertTrue("rows of a resting thumb on the open card: $open", open > 50)
        }
    }

    // Opened, Advanced's chevron still sits on its title, and an open
    // choice's on its name: within 1 of the middle of their words' capitals
    // at the usual text size and at 130%. Red with the chevrons set a fixed
    // offset from the usual size's metrics (about 4 off at 130%). Turned
    // about its box rather than its ink, the open caret's ink sits about 0.4
    // lower, under what this measure resolves, so that alone keeps it green.
    @Test(timeout = 240_000)
    fun openChevronsSitOnTheirWordsAtEveryTextSize() {
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), height = 1_600, reduceMotion = true, signedOut = true, textScale = scale).use { s ->
                s.onUi { s.app.signInForm.advancedOpen = true }
                s.settle(800).close()
                val (advanced, title) = s.advancedChevronAndCaps()
                assertEquals("Advanced open at ${scale}x", title, advanced, 1f)
                s.bringUp(s.named("${SignInWords.METHOD}, Normal").single())
                s.click(s.named("${SignInWords.METHOD}, Normal").single())
                s.settle(300).close()
                assertTrue("the Method's options show", s.withText(SignInWords.methodLabel(AuthMode.LegacyPassword)).isNotEmpty())
                val (choice, name) = s.methodChevronAndCaps()
                assertEquals("Method open at ${scale}x", name, choice, 1f)
            }
        }
    }

    // On the page the certificate question's Trust is the filled button too,
    // and a family link's button is the same filled button as Sign in. Red
    // with Trust a lit capsule, or Try again in the pale accent.
    @Test(timeout = 120_000)
    fun thePagesTrustAndLinkButtonsAreTheFilledButton() {
        page(height = 1_000).use { s ->
            s.fill("https://music.example.com")
            s.settle(600).close()
            val signIn = generateSequence(s.text("Sign in")) { it.parent }.first { it.clickable }
            val ready = s.settle(300)
            val plain = pixel(ready, signIn.positionInRoot.x.toInt() + 12, signIn.middle().toInt())
            ready.close()
            s.onUi { s.app.signInForm.question = CertificateQuestion("music.example.com", "AB".repeat(32)) }
            s.settle(600).close()
            val trust = s.fillRightOfCancel(12f)
            s.onUi {
                // The question's pop-up closes; with no question left, closing
                // it trusts and refuses nothing.
                s.app.popups.close()
                s.app.signInForm.question = null
                val form = s.app.signInForm
                // The result is written before the page draws, so the hand-over does not start.
                form.take(FamilyHandOverLink(base = "https://music.example.com", token = "t2", key = "k", server = "https://music.example.com"))
                form.result = false to SignInWords.WRONG_PASSWORD
            }
            s.settle(600).close()
            val again = generateSequence(s.text("Try again")) { it.parent }.first { it.clickable }
            val shown = s.settle(300)
            val link = pixel(shown, again.positionInRoot.x.toInt() + 12, again.middle().toInt())
            shown.close()
            assertTrue("Trust $trust:1 against the pop-up", trust >= 1.8)
            assertTrue("Try again ${Integer.toHexString(link)} against Sign in ${Integer.toHexString(plain)}", distance(link, plain) < 12.0)
        }
    }

    // CIELAB chroma under D65: how colored, 0 for a gray.
    private fun chroma(c: Int): Double {
        fun lin(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(c shr 16 and 0xFF)
        val g = lin(c shr 8 and 0xFF)
        val b = lin(c and 0xFF)
        fun f(t: Double): Double = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        val x = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
        val y = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
        val z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        return sqrt((500 * (x - y)).pow(2) + (200 * (y - z)).pow(2))
    }

    // Words' contrast as drawn: what they sit on is the middle color, by
    // luminance, of their box's edge, and the pixel standing furthest from it
    // is a stem's full color.
    private fun wordsContrast(image: Image, area: Area): Double {
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
        val under = edge.sortedBy { luminance(it) }[edge.size / 2]
        var most = 1.0
        for (y in area.top until area.bottom) for (x in area.left until area.right) most = maxOf(most, contrast(bitmap.getColor(x, y), under))
        bitmap.close()
        return most
    }

    // A filled button as drawn, found by its words: whether it is off, how
    // far its fill stands off the surface at `surfaceX` level with its middle
    // (its fill read 12 in from its left end), how colored the fill and that
    // surface are, and its words' contrast on its fill. With no `surfaceX`
    // the fill is read 6 in (a long label starts 12 in) and the surface 6
    // over it, in its column: on a glass sheet whose page shows through it (a
    // cover, a tile), only there is the same page under both.
    private class ButtonSeen(val off: Boolean, val fill: Double, val fillChroma: Double, val surfaceChroma: Double, val words: Double) {
        override fun toString() = "${if (off) "off" else "ready"}: fill %.2f:1 off the surface (chroma %.1f against %.1f), words %.2f:1".format(fill, fillChroma, surfaceChroma, words)
    }

    private fun A11yScene.buttonSeen(words: () -> SemanticsNode, surfaceX: Int? = null): ButtonSeen {
        val image = settle(700)
        val label = words()
        val button = generateSequence(label) { it.parent }.first { it.clickable }
        val y = button.middle().toInt()
        val x = button.positionInRoot.x.toInt() + if (surfaceX != null) 12 else 6
        val surface = if (surfaceX != null) pixel(image, surfaceX, y) else pixel(image, x, button.positionInRoot.y.toInt() - 6)
        val fill = pixel(image, x, y)
        val seen = ButtonSeen(button.config.contains(SemanticsProperties.Disabled), contrast(fill, surface), chroma(fill), chroma(surface), wordsContrast(image, label.area()))
        image.close()
        return seen
    }

    // A filled button that cannot be used yet reads as off, never as ready,
    // on every theme: each theme's Add sheet with its Add before anything is
    // typed, against the same Add once the form is filled in, its Change
    // password with the three passwords empty and then typed, and the
    // sign-in page's Sign in (Octo's dark look on every theme) before and
    // after. Off, its words read 3:1 or more on its own fill, as a label, and
    // under the 4.5:1 of words that can be used; its fill stands 1.1 to 1.5
    // off the surface round it (it shows as a button, faintly) and adds no
    // color of its own (its chroma at most 6 over the surface's). Ready, the same
    // button's fill stands 2:1 or more off that surface, with words 4.5:1 or
    // more. Red with the whole button faded: on Paper white words at 2.5:1 on
    // a pale accent fill 2.3:1 off the sheet; and red with Change password's
    // button lit while its fields are empty.
    @Test(timeout = 300_000)
    fun aFilledButtonThatCannotBeUsedReadsOffOnEveryTheme() {
        val problems = mutableListOf<String>()
        fun judge(where: String, off: ButtonSeen, ready: ButtonSeen) {
            println("OFF BUTTON $where: $off; $ready")
            if (!off.off || ready.off) problems += "$where: not off then ready ($off; $ready)"
            if (off.words < 3.0 || off.words >= 4.5) problems += "$where: off words %.2f:1, not 3 to 4.5".format(off.words)
            if (off.fill < 1.1 || off.fill > 1.5) problems += "$where: off fill %.2f:1 off the surface, not 1.1 to 1.5".format(off.fill)
            if (off.fillChroma > off.surfaceChroma + 6.0) problems += "$where: off fill chroma %.1f over the surface's %.1f".format(off.fillChroma, off.surfaceChroma)
            if (ready.fill < 2.0) problems += "$where: ready fill %.2f:1 off the surface".format(ready.fill)
            if (ready.words < 4.5) problems += "$where: ready words %.2f:1".format(ready.words)
        }
        signedIn().use { s ->
            for (theme in ThemeChoice.entries) {
                s.onUi {
                    s.app.popups.close()
                    s.app.settings.pickTheme(theme)
                }
                s.settle(600).close()
                s.onUi { addServer(s.app) }
                s.settle(600).close()
                val left = s.popupLeft().toInt() + 8
                val off = s.buttonSeen({ s.inPopup("Add") }, left)
                s.type(s.named(SignInWords.SERVER_ADDRESS).single { it.inPopup() }, s.server.address)
                s.type(s.named(SignInWords.USERNAME).single { it.inPopup() }, "winters")
                s.type(s.named(SignInWords.PASSWORD).single { it.inPopup() }, "pw")
                judge("${theme.label} Add sheet", off, s.buttonSeen({ s.inPopup("Add") }, left))
                s.onUi {
                    s.app.popups.close()
                    changePassword(s.app)
                }
                s.settle(600).close()
                // Its foot sits over the page's album tiles, which show through the
                // glass at the sheet's edge, so its surface is read over the button;
                // its long label starts where the Add sheet's fill is read.
                val waiting = s.buttonSeen({ s.inPopup("Change password") })
                s.type(s.named("Current password").single { it.inPopup() }, "pw")
                s.type(s.named("New password").single { it.inPopup() }, "a new one")
                s.type(s.named("New password again").single { it.inPopup() }, "a new one")
                judge("${theme.label} Change password", waiting, s.buttonSeen({ s.inPopup("Change password") }))
            }
            s.onUi { s.app.popups.close() }
        }
        page().use { s ->
            s.settle(600).close()
            val left = (s.width - 480) / 2 + 8
            val off = s.buttonSeen({ s.text("Sign in") }, left)
            s.fill("https://music.example.com")
            judge("sign-in page", off, s.buttonSeen({ s.text("Sign in") }, left))
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The page's words set 130% larger, as the text size setting makes them.
    private fun largerText(height: Int = 900) =
        A11yScene(folder.newFolder(), height = height, reduceMotion = true, signedOut = true, textScale = 1.3f)

    // The middle, in the window, of the ink of the first seven columns of a
    // line of words: the middle of its first capital.
    private fun capsMiddleOf(image: Image, words: SemanticsNode): Float {
        val box = words.area()
        return inkMiddle(image, Area(box.left, box.top, box.left + 7, box.bottom))
    }

    // Remember password's switch: its middle, and its name's capitals'.
    private fun A11yScene.rememberSwitchAndCaps(): Pair<Float, Float> {
        val name = text("Remember password")
        val switch = nodes().first { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
        val image = settle(300)
        val caps = capsMiddleOf(image, name)
        image.close()
        return switch.positionInRoot.y + switch.size.height / 2f to caps
    }

    // Advanced's chevron's ink middle, and its title's capitals'.
    private fun A11yScene.advancedChevronAndCaps(): Pair<Float, Float> {
        val title = text(SignInWords.ADVANCED)
        val head = generateSequence(title) { it.parent }.first { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
        val image = settle(300)
        val caps = capsMiddleOf(image, title)
        val right = head.positionInRoot.x.toInt() + head.size.width - RowInset.value.toInt()
        val top = head.positionInRoot.y.toInt()
        val chevron = inkMiddle(image, Area(right - IconSize.Toolbar.value.toInt(), top, right, top + head.size.height))
        image.close()
        return chevron to caps
    }

    // The Method row's chevron's ink middle, and its name's capitals', the
    // row brought to the middle of its scroll first.
    private fun A11yScene.methodChevronAndCaps(): Pair<Float, Float> {
        bringUp(text(SignInWords.METHOD))
        val name = text(SignInWords.METHOD)
        val row = generateSequence(name) { it.parent }.first { it.clickable }
        val image = settle(300)
        val caps = capsMiddleOf(image, name)
        val right = row.positionInRoot.x.toInt() + row.size.width - RowInset.value.toInt()
        val top = row.positionInRoot.y.toInt()
        val chevron = inkMiddle(image, Area(right - IconSize.Table.value.toInt(), top, right, top + row.size.height))
        image.close()
        return chevron to caps
    }

    // The error note's mark's ink middle, and its words' capitals'. The mark
    // is 16 wide and ends 8 before the words.
    private fun A11yScene.errorMarkAndCaps(): Pair<Float, Float> {
        val words = text(SignInWords.WRONG_PASSWORD)
        val image = settle(300)
        val caps = capsMiddleOf(image, words)
        val box = words.area()
        val mark = inkMiddle(image, Area(box.left - 24, box.top, box.left - 8, box.bottom))
        image.close()
        return mark to caps
    }

    // At 130% text Remember password's switch still sits on its name: its
    // middle within 1 of the middle of the name's capitals. Red with the
    // switch lifted a fixed 1 over the name's line (4 high).
    @Test(timeout = 120_000)
    fun atLargerTextTheRememberSwitchSitsOnItsName() {
        largerText().use { s ->
            s.settle(600).close()
            val (switch, caps) = s.rememberSwitchAndCaps()
            assertEquals(caps, switch, 1f)
        }
    }

    // At 130% text Advanced's chevron still sits on its title. Red with the
    // chevron dropped a fixed 3 under the title's line (4 high).
    @Test(timeout = 120_000)
    fun atLargerTextAdvancedsChevronSitsOnItsTitle() {
        largerText(height = 1_200).use { s ->
            s.settle(600).close()
            val (chevron, caps) = s.advancedChevronAndCaps()
            assertEquals(caps, chevron, 1f)
        }
    }

    // A choice row's chevron sits on its name, at the usual text size and at
    // 130%. Red at 130% with the chevron a fixed 2 under the name's line
    // (about 4 high).
    @Test(timeout = 240_000)
    fun aChoicesChevronSitsOnItsNameAtEveryTextSize() {
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), height = 1_600, reduceMotion = true, signedOut = true, textScale = scale).use { s ->
                s.onUi { s.app.signInForm.advancedOpen = true }
                s.settle(800).close()
                val (chevron, caps) = s.methodChevronAndCaps()
                assertEquals("at ${scale}x", caps, chevron, 1f)
            }
        }
    }

    // An error note's mark sits on its words' first line, at the usual text
    // size and at 130%. Red at 130% with the mark a fixed 2 under the line's
    // top (about 3 high).
    @Test(timeout = 240_000)
    fun anErrorNotesMarkSitsOnItsWordsAtEveryTextSize() {
        for (scale in listOf(1f, 1.3f)) {
            A11yScene(folder.newFolder(), reduceMotion = true, signedOut = true, textScale = scale).use { s ->
                s.onUi { s.app.signInForm.result = false to SignInWords.WRONG_PASSWORD }
                s.settle(600).close()
                val (mark, caps) = s.errorMarkAndCaps()
                assertEquals("at ${scale}x", caps, mark, 1f)
            }
        }
    }

    // On a light theme a sheet's field wells stand a step over the tray their
    // card is: on Paper and Daylight 3 or more over it in lightness. Red with
    // the page-colored wells (0.6 under the tray on Paper).
    @Test(timeout = 120_000)
    fun aSheetsWellsStandOverTheTrayOnALightTheme() {
        signedIn().use { s ->
            for (theme in listOf(ThemeChoice.Paper, ThemeChoice.Daylight)) {
                s.onUi {
                    s.app.popups.close()
                    s.app.settings.pickTheme(theme)
                }
                s.settle(600).close()
                s.onUi { addServer(s.app) }
                val image = s.settleStill(700, quiet = 150)
                val name = s.inPopup(SignInWords.USERNAME)
                val field = s.named(SignInWords.USERNAME).single { it.inPopup() }
                val tray = pixel(image, s.popupLeft().toInt() + Space.Xl.value.toInt() + 4, (name.positionInRoot.y + name.size.height / 2f).toInt())
                val well = pixel(image, (field.positionInRoot.x + field.size.width).toInt() - 6, (field.positionInRoot.y + field.size.height / 2f).toInt())
                image.close()
                assertTrue("$theme: well ${lightness(well)} on tray ${lightness(tray)}", lightness(well) - lightness(tray) >= 3.0)
            }
        }
    }

    // A family invite's sheet has a way out as every sheet does, a Cancel
    // capsule that closes it, and its way to a password sign-in sits under
    // the buttons, its words ending on Sign up's right edge. Red with that way
    // in the button row and no Cancel.
    @Test(timeout = 120_000)
    fun theInviteSheetHasCancelAndItsPasswordWayUnderTheButtons() {
        signedIn().use { s ->
            s.onUi { s.app.openFamilyLink(FamilyInviteLink(s.server.address, "t1")) }
            s.settle(600).close()
            val cancel = s.named("Cancel").filter { it.inPopup() }
            assertEquals("a Cancel capsule", 1, cancel.size)
            val signUp = generateSequence(s.inPopup("Sign up")) { it.parent }.first { it.clickable }
            val words = s.inPopup("Sign in with a password instead")
            assertTrue("under the buttons", words.positionInRoot.y >= signUp.positionInRoot.y + signUp.size.height)
            assertEquals(signUp.positionInRoot.x + signUp.size.width, words.positionInRoot.x + words.size.width, 1f)
            s.click(cancel.single())
            assertNull(s.app.popups.request)
        }
    }

    // A family sign-in's way out on the page is a Cancel capsule, as in every
    // sheet. Red with the bare text Cancel (no capsule named for it).
    @Test(timeout = 120_000)
    fun theHandOversCancelIsACapsule() {
        page(height = 1_000).use { s ->
            s.onUi {
                val form = s.app.signInForm
                // The result is written before the page draws, so the hand-over does not start.
                form.take(FamilyHandOverLink(base = "https://music.example.com", token = "t2", key = "k", server = "https://music.example.com"))
                form.result = false to SignInWords.WRONG_PASSWORD
            }
            s.settle(600).close()
            assertEquals(1, s.named("Cancel").size)
        }
    }

    // A family sign-in that failed says it is not signed in yet, naming its
    // server as people read it, with no slash at its end. Red with "Ready to
    // sign in to music.example.com/" over the failure.
    @Test(timeout = 120_000)
    fun aFailedHandOverSaysItIsNotSignedInYet() {
        page(height = 1_000).use { s ->
            s.onUi {
                val form = s.app.signInForm
                // The result is written before the page draws, so the hand-over does not start.
                form.take(FamilyHandOverLink(base = "https://music.example.com/", token = "t2", key = "k", server = "https://music.example.com/"))
                form.result = false to SignInWords.WRONG_PASSWORD
            }
            s.settle(600).close()
            assertEquals(1, s.withText("Not signed in to music.example.com yet").size)
        }
    }

    // An invite's note names its server as people read it, with no slash at
    // its end. Red with "music.example.com/.".
    @Test(timeout = 120_000)
    fun anInvitesNoteNamesItsServerWithoutASlash() {
        page(height = 1_000).use { s ->
            s.onUi { s.app.signInForm.take(FamilyInviteLink("https://music.example.com/", "t1")) }
            s.settle(600).close()
            assertEquals(1, s.withText("You're invited to music.example.com. Choose your name and a password. Your login then works in Octo and any other music app.").size)
        }
    }

    // The invite's page says once that the login works in any music app, in
    // its note, not again in the line under Octo. Red with it in both.
    @Test(timeout = 120_000)
    fun theInvitePageSaysItsLoginSentenceOnce() {
        page(height = 1_000).use { s ->
            s.onUi { s.app.signInForm.take(FamilyInviteLink("https://music.example.com", "t1")) }
            s.settle(600).close()
            val said = s.nodes().count { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("Your login then works in Octo and any other music app.") } == true }
            assertEquals(1, said)
        }
    }
}
