package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.SignInWords
import app.winters.octo.data.KeptServers
import app.winters.octo.data.Session
import app.winters.octo.data.SessionState
import app.winters.octo.data.SignInAsk
import app.winters.octo.data.StoredServer
import app.winters.octo.family.FamilyOpen
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.familyAppLink
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.signin.JoinHarness
import app.winters.octo.ui.signin.SignInViewModel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Where an outside family link and Octo's own sign-in asks go: never over a
// sign-in that is open, never under a link's page, and never left behind for
// later; a join page replaces one on top, a page closes only itself, and
// tapping the tab again stops at a sign-in page while one is open.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutingTest {
    private val harness = JoinHarness()

    @Before
    fun start() = harness.start()

    @After
    fun stop() = harness.stop()

    private val feedback = Feedback()
    private var selects = 0
    private var openings = 0L
    private val reopened = mutableListOf<FamilyOpen>()
    private val askedAgain = mutableListOf<SignInAsk>()
    private val pushed = mutableListOf<NavKey>()
    private val names = mapOf("a" to "Home", "b" to "Studio")
    private val invite = FamilyInviteLink(server = "https://music.example.com", token = "invite-token")
    private val join = FamilyOpen.Join(invite)

    private fun asked(id: String, note: String? = "note $id") = ServerFormRoute(ServerForm.SignIn, id, note = note, asked = true)

    private fun route(what: FamilyOpen, stack: MutableList<NavKey>, nameOf: (String) -> String? = { names[it] }) = routeFamilyOpen(
        what,
        harness.running,
        feedback,
        reopen = { reopened += it },
        select = { selects++ },
        nextOpening = { ++openings },
        stack = stack,
        askAgain = { askedAgain += it },
        nameOf = nameOf,
    )

    // A form whose plain sign-in is held by the switch: open.
    private fun openForm(): SignInViewModel {
        val vm = harness.form()
        vm.typeAddress(harness.link)
        vm.username = "alice"
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        harness.awaitOpen(vm)
        return vm
    }

    private fun letGo(vm: SignInViewModel) {
        harness.signIns.release("alice")
        harness.settle(vm)
    }

    // Red when a link replaces or stacks over an open sign-in, moves the
    // tab, counts an opening, or loses its way back ("Use it").
    @Test
    fun aLinkWhileASignInIsOpenIsRefusedAndNothingMoves() {
        val vm = openForm()
        val stack = mutableListOf<NavKey>(SettingsRoute, asked("a"))
        route(join, stack)
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, vm.linkNote)
        val shown = feedback.message.value!!
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, shown.text)
        assertEquals("Use it", shown.action)
        assertEquals(0, selects)
        assertEquals(0L, openings)
        assertEquals(listOf<NavKey>(SettingsRoute, asked("a")), stack)
        assertEquals(emptyList<SignInAsk>(), askedAgain)
        shown.onAction!!()
        assertEquals(listOf<FamilyOpen>(join), reopened)
        letGo(vm)
    }

    // Red when two openings of a link share a page, or the second stacks
    // over the first.
    @Test
    fun withNothingOpenEachJoinIsItsOwnPageReplacingTheLast() {
        val stack = mutableListOf<NavKey>(SettingsRoute)
        route(join, stack)
        val first = stack.last()
        assertEquals(FamilyJoinRoute(familyAppLink(invite), 1), first)
        route(join, stack)
        assertEquals(listOf<NavKey>(SettingsRoute, FamilyJoinRoute(familyAppLink(invite), 2)), stack)
        assertNotEquals(first.toString(), stack.last().toString())
        assertEquals(2, selects)
    }

    // Red when Family is refused, or not added.
    @Test
    fun familyIsAlwaysAdded() {
        val vm = openForm()
        val stack = mutableListOf<NavKey>(SettingsRoute)
        route(FamilyOpen.Family, stack)
        assertEquals(listOf<NavKey>(SettingsRoute, FamilyRoute), stack)
        letGo(vm)
    }

    // Red when an asked page is left under a link's page (it would show
    // again later, over another server), or its ask is lost.
    @Test
    fun aJoinClosesAskedPagesAndOffersTheTopmostAskAgain() {
        val stack = mutableListOf<NavKey>(SettingsRoute, asked("a"))
        route(join, stack)
        assertEquals(listOf<NavKey>(SettingsRoute, FamilyJoinRoute(familyAppLink(invite), 1)), stack)
        val shown = feedback.message.value!!
        assertEquals(SignInWords.askWhileSignInOpen("Home"), shown.text)
        assertEquals("Sign in", shown.action)
        shown.onAction!!()
        assertEquals(1, askedAgain.size)
        assertEquals("a", askedAgain.single().serverId)
        assertEquals("note a", askedAgain.single().note)
    }

    // Red when only the top page is checked: Family pushed over an asked page
    // would keep it under the join.
    @Test
    fun anAskedPageUnderAnotherPageIsClosedToo() {
        val stack = mutableListOf<NavKey>(asked("a"), FamilyRoute)
        route(join, stack)
        assertEquals(listOf<NavKey>(FamilyRoute, FamilyJoinRoute(familyAppLink(invite), 1)), stack)
        assertEquals(SignInWords.askWhileSignInOpen("Home"), feedback.message.value!!.text)
    }

    // Red when the lowest asked page is offered instead of the topmost.
    @Test
    fun theTopmostOfSeveralAskedPagesIsOffered() {
        val stack = mutableListOf<NavKey>(asked("a"), ImportRoute, asked("b"))
        route(join, stack)
        assertEquals(listOf<NavKey>(ImportRoute, FamilyJoinRoute(familyAppLink(invite), 1)), stack)
        assertEquals(SignInWords.askWhileSignInOpen("Studio"), feedback.message.value!!.text)
    }

    // An asked page whose server is gone, or now the one in use (a link
    // signed in to it), is closed with nothing offered. Red when a notice
    // offers a sign-in there is no need for.
    @Test
    fun anAskedPageNoLongerNeededClosesQuietly() {
        val stack = mutableListOf<NavKey>(SettingsRoute, asked("gone"))
        route(join, stack) { null }
        assertEquals(listOf<NavKey>(SettingsRoute, FamilyJoinRoute(familyAppLink(invite), 1)), stack)
        assertNull(feedback.message.value)
    }

    // Red when the stack under a join's page changes.
    @Test
    fun aJoinLeavesEveryOtherPageAsItIs() {
        val stack = mutableListOf<NavKey>(SettingsRoute, AlbumRoute("al-1"))
        route(join, stack)
        assertEquals(listOf<NavKey>(SettingsRoute, AlbumRoute("al-1"), FamilyJoinRoute(familyAppLink(invite), 1)), stack)
    }

    private val keptAB = KeptServers(
        listOf(
            StoredServer("https://a.example.com/", "alice", id = "a"),
            StoredServer("https://b.example.com/", "alice", id = "b"),
        ),
    )

    private fun inUse(id: String) = SessionState.SignedIn(
        Session(SubsonicClient("https://a.example.com/".toHttpUrl(), Credentials("alice", "pw"), OkHttpClient()), null, null, false, false, emptySet(), ConnectionSettings(), id = id),
    )

    private fun ask(ask: SignInAsk, state: SessionState = SessionState.SignedOut, stack: List<NavKey> = listOf(SettingsRoute)) = routeSignInAsk(
        ask,
        keptAB,
        state,
        harness.running,
        stack,
        feedback,
        askAgain = { askedAgain += it },
        select = { selects++ },
        push = { pushed += it },
    )

    // Red when an ask buries an open sign-in, or its "Sign in" does more
    // than ask again.
    @Test
    fun anAskWhileASignInIsOpenIsANotice() {
        val vm = openForm()
        val asking = SignInAsk("a", "why")
        ask(asking)
        assertEquals(emptyList<NavKey>(), pushed)
        assertEquals(0, selects)
        val shown = feedback.message.value!!
        assertEquals(SignInWords.askWhileSignInOpen(keptAB.find("a")!!.name), shown.text)
        assertEquals("Sign in", shown.action)
        shown.onAction!!()
        assertEquals(listOf(asking), askedAgain)
        assertEquals(emptyList<NavKey>(), pushed)
        letGo(vm)
    }

    // Red when an ask buries a link's page, whether on top or under Family.
    @Test
    fun anAskWithALinksPageOnTheTabIsANotice() {
        val page = FamilyJoinRoute("octo://join", 1)
        ask(SignInAsk("a", "why"), stack = listOf(SettingsRoute, page))
        assertEquals(emptyList<NavKey>(), pushed)
        assertEquals(0, selects)
        assertEquals(SignInWords.askWhileSignInOpen(keptAB.find("a")!!.name), feedback.message.value!!.text)
        ask(SignInAsk("a", "why"), stack = listOf(SettingsRoute, page, FamilyRoute))
        assertEquals(emptyList<NavKey>(), pushed)
        assertEquals(0, selects)
    }

    // Red when an ask with nothing open does not open its form, or opens it
    // unmarked (a later link would leave it under its page).
    @Test
    fun anAskWithNothingOpenOpensItsFormMarked() {
        ask(SignInAsk("a", "why"))
        assertEquals(listOf<NavKey>(ServerFormRoute(ServerForm.SignIn, "a", note = "why", asked = true)), pushed)
        assertEquals(1, selects)
    }

    // Red when an ask for a server gone, or the one in use and signed in, is
    // shown.
    @Test
    fun anAskNoLongerNeededIsDropped() {
        ask(SignInAsk("gone", "why"))
        ask(SignInAsk("a", "why"), state = inUse("a"))
        assertEquals(emptyList<NavKey>(), pushed)
        assertEquals(0, selects)
        assertNull(feedback.message.value)
    }

    // The cold-start ask comes while the sessions still load: it opens. Red
    // when a loading state reads as "in use".
    @Test
    fun anAskWhileTheSessionsLoadOpens() {
        ask(SignInAsk("a", "why"), state = SessionState.Loading)
        assertEquals(1, pushed.size)
    }

    // Red when askName gives a name for a server gone or signed in and in
    // use, or none for a kept one.
    @Test
    fun askNameNamesOnlyAServerToAskFor() {
        assertEquals(keptAB.find("a")!!.name, askName("a", keptAB, SessionState.SignedOut))
        assertEquals(keptAB.find("b")!!.name, askName("b", keptAB, inUse("a")))
        assertNull(askName("gone", keptAB, SessionState.SignedOut))
        assertNull(askName("a", keptAB, inUse("a")))
    }

    // Red when a page closing itself takes the page above it, or more than
    // itself, or throws when it is already gone.
    @Test
    fun aPageRemovesOnlyItsOwnEntry() {
        val page = FamilyJoinRoute("octo://join", 1)
        val stack = mutableListOf<NavKey>(page, FamilyRoute)
        stack.removeOwn(page)
        assertEquals(listOf<NavKey>(FamilyRoute), stack)
        val twice = mutableListOf<NavKey>(SettingsRoute, page, page)
        twice.removeOwn(page)
        assertEquals(listOf<NavKey>(SettingsRoute, page), twice)
        val other = mutableListOf<NavKey>(SettingsRoute, FamilyRoute)
        other.removeOwn(page)
        assertEquals(listOf<NavKey>(SettingsRoute, FamilyRoute), other)
    }

    // Red when tapping the tab again throws away an open form (or stops at a
    // sign-in page with nothing open).
    @Test
    fun tappingTheTabAgainStopsAtASignInPageWhileOneIsOpen() {
        val page = FamilyJoinRoute("octo://join", 1)
        assertEquals(0, reselectTo(listOf(SettingsRoute, page, FamilyRoute), anyOpen = false))
        assertEquals(1, reselectTo(listOf(SettingsRoute, page, FamilyRoute), anyOpen = true))
        assertEquals(1, reselectTo(listOf(SettingsRoute, page), anyOpen = true))
        assertEquals(0, reselectTo(listOf(SettingsRoute, AlbumRoute("al-1")), anyOpen = true))
        val open = mutableListOf<NavKey>(SettingsRoute, page, FamilyRoute)
        open.reselect(true)
        assertEquals(listOf<NavKey>(SettingsRoute, page), open)
        val none = mutableListOf<NavKey>(SettingsRoute, page, FamilyRoute)
        none.reselect(false)
        assertEquals(listOf<NavKey>(SettingsRoute), none)
        val root = mutableListOf<NavKey>(SettingsRoute)
        root.reselect(true)
        root.reselect(false)
        assertEquals(listOf<NavKey>(SettingsRoute), root)
    }

    // The asked pages' asks come back with their server and note. Red when
    // an asked page without an id, or one the person opened, is taken.
    @Test
    fun removeAskedTakesOnlyAskedPages() {
        val stack = mutableListOf<NavKey>(SettingsRoute, ServerFormRoute(ServerForm.SignIn, "a"), asked("b"), ServerFormRoute(ServerForm.Add, asked = true))
        val asks = stack.removeAsked()
        assertEquals(listOf("b"), asks.map { it.serverId })
        assertEquals(listOf<String?>("note b"), asks.map { it.note })
        assertEquals(listOf<NavKey>(SettingsRoute, ServerFormRoute(ServerForm.SignIn, "a"), ServerFormRoute(ServerForm.Add, asked = true)), stack)
        assertTrue(stack.none { it is ServerFormRoute && it.asked && it.id != null })
    }
}
