package app.winters.octo.ui.signin

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.startHome
import app.winters.octo.data.SignInError
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.familyAppLink
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.testing.PageLocals
import app.winters.octo.testing.RobolectricComposeRule
import app.winters.octo.ui.family.HANDOVER_ALLOWED
import app.winters.octo.ui.family.HANDOVER_DENIED
import app.winters.octo.ui.family.HANDOVER_EXPIRED
import app.winters.octo.ui.family.HandOverSignIn
import app.winters.octo.ui.family.shownServer
import app.winters.octo.ui.nav.FamilyJoinRoute
import app.winters.octo.ui.nav.FamilyRoute
import app.winters.octo.ui.nav.SettingsRoute
import app.winters.octo.ui.nav.backOne
import app.winters.octo.ui.nav.removeOwn
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

// An invite's sign-up and a hand-over from another device, run through the
// sign-in view model itself: they go to the family page with the form's
// sign-in page fields, and a page that still turns the app away is named. A
// hand-over that only waits for the other device can always be left; a
// sign-up or a sign-in that commits holds every way out until it ends, and a
// finished sign-up's username is never lost on the way out. A page drawn
// again, or covered while it signs in, keeps all of it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JoinSignInPageTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    private val harness = JoinHarness()
    private val appPassword = basicCredentials("alice", "app-pass")

    @Before
    fun start() = harness.start()

    @After
    fun stop() = harness.stop()

    private fun SignInViewModel.fillSignInPage() {
        pickGateway(GatewayKind.Password)
        gatewayFirst = "alice"
        gatewaySecond = "app-pass"
    }

    private fun invite() = FamilyInviteLink(server = harness.link, token = "invite-token")

    private fun takeOne() = harness.server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("no request came within 10 s")

    private val wrong = SignInError.Failed(SubsonicException.WrongCredentials("no")) to null

    // Red when the invite's sign-up client is built without the form's
    // connection (ConnectionSettings() in its place).
    @Test
    fun anInviteSignsUpWithTheFormsSignInPageFields() {
        harness.server.dispatcher = AuthentikAnswers()
        val vm = harness.form()
        vm.startJoin(invite())
        harness.fillInvite(vm)
        vm.fillSignInPage()
        vm.join()
        harness.settle(vm)
        assertEquals(appPassword, takeOne().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), vm.error)
        assertEquals(Gateway.Authentik, vm.gateway)
    }

    // A hand-over that meets the sign-in page opens its fields; filled in,
    // trying again sends them. Red when the hand-over's client is built
    // without the form's connection.
    @Test
    fun aHandOverTriedAgainSendsTheFormsSignInPageFields() {
        harness.server.dispatcher = AuthentikAnswers()
        val vm = harness.form()
        vm.startJoin(FamilyHandOverLink(base = harness.link, token = "handover-token", key = "key", server = harness.link))
        harness.settle(vm)
        val first = List(harness.server.requestCount) { takeOne() }
        assertNull(first.first().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, false), vm.error)
        assertTrue(vm.advancedOpen)
        vm.fillSignInPage()
        vm.receive()
        harness.settle(vm)
        val again = List(harness.server.requestCount - first.size) { takeOne() }
        assertEquals(appPassword, again.first().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), vm.error)
    }

    // A hand-over waiting for the other device to allow it can be left: the
    // way out is open, leaving cancels the wait at once, and the Allow that
    // comes later signs nothing in. Red when leaving waits, leaves the job
    // running (its late Allow would sign in), or leaves the form busy.
    @Test
    fun aHandOverWaitingForAllowCanBeLeftAndItsAllowSignsNothingIn() {
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        val answers = PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(allowed(box)) }), holding = POLL)
        harness.server.dispatcher = answers
        val vm = harness.form()
        vm.startJoin(link)
        takeOne()
        takeOne()
        assertTrue(vm.busy)
        assertTrue(vm.waiting)
        assertFalse(vm.committing)
        vm.stopJoin()
        assertFalse(vm.busy)
        assertFalse(vm.waiting)
        assertNull(vm.linkJob)
        harness.awaitClosed(vm)
        vm.username = "bob"
        vm.password = "pw"
        vm.submit()
        harness.signIns.awaitCall("bob")
        answers.release()
        harness.settle(vm)
        assertEquals(emptyList<RecordedSignIn>(), harness.signIns.callsFor("alice"))
        assertEquals(1, harness.signIns.callsFor("bob").size)
    }

    // A hand-over the other device turns down, and one the server answers as
    // expired, each end the wait; an invite then taken in the same form and
    // signed up commits, and nothing leaves it. Red when a redeem's end
    // leaves `waiting` set (the sign-up after it could be left halfway), or
    // the Expired answer is not what was read (a failed redeem says the same
    // words).
    @Test
    fun aRefusedOrExpiredHandOverEndsTheWaitAndAnInviteAfterItCommits() {
        val (denied, _) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.server.dispatcher = PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(DENIED) }))
        val first = harness.form()
        first.startJoin(denied)
        harness.settle(first)
        assertFalse(first.waiting)
        assertEquals(HANDOVER_DENIED, first.error)
        repeat(harness.server.requestCount) { takeOne() }

        val (expired, _) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.server.dispatcher = PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(EXPIRED) }))
        val vm = harness.form()
        vm.startJoin(expired)
        harness.settle(vm)
        assertFalse(vm.waiting)
        assertFalse(vm.busy)
        assertEquals(HANDOVER_EXPIRED, vm.error)
        val paths = List(harness.server.requestCount - 2) { takeOne().url.encodedPath }
        assertTrue("$paths", POLL in paths)

        val signUp = PathAnswers(mapOf(JOIN to { joined("alice-new") }), holding = JOIN)
        harness.server.dispatcher = signUp
        vm.startJoin(invite())
        harness.fillInvite(vm)
        vm.join()
        takeOne()
        assertTrue(vm.committing)
        vm.stopJoin()
        assertTrue(vm.joining)
        assertNotNull(vm.invite)
        signUp.release()
        harness.settle(vm)
        assertTrue(vm.signedIn)
    }

    // A hand-over the other device refused says over its failure that it is
    // not signed in yet, never that it is ready. Red with "Ready to sign in"
    // over the refusal.
    @Test
    fun aRefusedHandOversRowSaysItIsNotSignedInYet() {
        val (denied, _) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.server.dispatcher = PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(DENIED) }))
        val vm = harness.form()
        ui.compose.setContent { PageLocals { SignInScreen(onBack = {}, onDone = {}, vm = vm) } }
        vm.startJoin(denied)
        harness.settle(vm)
        ui.compose.waitForIdle()
        assertEquals(HANDOVER_DENIED, vm.error)
        ui.compose.onNodeWithText("Not signed in to ${shownServer(denied.server)} yet").assertExists()
    }

    // A plain sign-in held by the switch commits. Red when `committing` is
    // a list of steps instead of every run that is not waiting.
    @Test
    fun aPlainSignInCommits() {
        val vm = harness.form()
        vm.typeAddress(harness.link)
        vm.username = "alice"
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        assertTrue(vm.committing)
        harness.signIns.release("alice")
        harness.settle(vm)
        assertFalse(vm.busy)
    }

    // While a sign-in commits, Scan a family QR code is off and its action
    // does nothing: the page stays on the sign-in, with no camera, and
    // opening the join form by any other way is refused too; so while its
    // certificate question waits. Once answered, the scanner opens. Red when
    // the button is live, or the model switches to the join form over the
    // running sign-in. The page is taken away before the question (its
    // pop-up window cannot be built in a unit test).
    @Test
    fun scanningAFamilyCodeWaitsForASignInThatCommits() {
        val vm = harness.form()
        var shown by mutableStateOf(true)
        ui.compose.setContent { PageLocals { if (shown) SignInScreen(onBack = {}, onDone = {}, vm = vm) } }
        vm.typeAddress(harness.link)
        vm.username = "alice"
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        ui.compose.waitForIdle()
        assertTrue(vm.committing)
        ui.compose.onNodeWithText("Scan a family QR code").assertIsNotEnabled()
        ui.compose.runOnIdle { vm.scanFamilyCode() }
        assertFalse(vm.joining)
        assertFalse(vm.scanning)
        vm.startJoin()
        assertFalse(vm.joining)
        shown = false
        ui.compose.waitForIdle()
        harness.signIns.release("alice", SignInError.Untrusted("127.0.0.1", "ab12") to null)
        harness.settle(vm)
        assertNotNull(vm.question)
        vm.scanFamilyCode()
        assertFalse(vm.joining)
        assertFalse(vm.scanning)
        vm.distrust()
        vm.scanFamilyCode()
        assertTrue(vm.joining)
        assertTrue(vm.scanning)
    }

    // Every way the sign-in step of an allowed hand-over can fail ends its
    // "Allowed, signing in": a certificate the phone does not trust (its
    // question then waits), a refused password, a sign-in page. Red when a
    // failure leaves the step set (the row would say it is signing in while
    // nothing is).
    @Test
    fun everyFailureOfAnAllowedHandOversSignInClearsItsStep() {
        val failures = listOf(
            SignInError.Untrusted("127.0.0.1", "ab12"),
            SignInError.Failed(SubsonicException.WrongCredentials("no")),
            SignInError.Failed(SubsonicException.NotSubsonic("HTTP 401 from ping", 401, Gateway.Authentik, false)),
        )
        for (failure in failures) {
            val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
            harness.allowAtOnce(box)
            val vm = harness.form()
            harness.signIns.hold("alice")
            val before = harness.signIns.callsFor("alice").size
            vm.startJoin(link)
            harness.signIns.awaitCall("alice", before + 1)
            assertEquals("$failure", HANDOVER_ALLOWED, vm.handOverStep)
            harness.signIns.release("alice", failure to null)
            harness.settle(vm)
            assertNull("$failure", vm.handOverStep)
            assertTrue("$failure", vm.holdsReceived)
        }
    }

    // The sign-in step of a received sign-in commits, from the run that
    // redeemed it and from Try again after it failed; leaving it is refused
    // even called directly, the form stays in the open set, and the way out
    // reads disabled. Red when `waiting` stays set after the redeem, or
    // stopJoin only trusts the button's look.
    @Test
    fun aReceivedSignInsStepCommitsAndCannotBeLeft() {
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.allowAtOnce(box)
        val vm = harness.form()
        ui.compose.setContent { PageLocals { SignInScreen(onBack = {}, onDone = {}, vm = vm) } }
        harness.signIns.hold("alice")
        vm.startJoin(link)
        harness.signIns.awaitCall("alice")
        ui.compose.waitForIdle()
        assertTrue(vm.committing)
        ui.compose.onNodeWithText("Sign in with a password").assertIsNotEnabled()
        vm.stopJoin()
        assertTrue(vm.joining)
        assertNotNull(vm.handOver)
        harness.awaitOpen(vm)
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        assertTrue(vm.holdsReceived)
        harness.signIns.hold("alice")
        vm.receive()
        harness.signIns.awaitCall("alice", 2)
        assertTrue(vm.committing)
        vm.stopJoin()
        assertTrue(vm.joining)
        harness.signIns.release("alice")
        harness.settle(vm)
        assertTrue(vm.signedIn)
    }

    // While a sign-up runs, the way out and the page's back button are
    // disabled and system Back keeps the page with the request running; once
    // the sign-up is kept (its sign-in step failed) Back leaves the link with
    // the new username in its field and stays, and the next Back leaves. Red
    // when Back is free while committing, or leaving loses the username.
    @Test
    fun systemBackHoldsWhileASignUpCommitsThenLeavesTheLinkThenThePage() {
        val signUp = PathAnswers(mapOf(JOIN to { joined("alice-new") }), holding = JOIN)
        harness.server.dispatcher = signUp
        val vm = harness.form()
        vm.startJoin(invite())
        harness.fillInvite(vm)
        var left = false
        var dispatcher: OnBackPressedDispatcher? = null
        ui.compose.setContent {
            PageLocals {
                dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                BackHandler { left = true }
                SignInScreen(onBack = { left = true }, onDone = {}, vm = vm)
            }
        }
        ui.compose.waitForIdle()
        harness.signIns.hold("alice-new")
        vm.join()
        takeOne()
        ui.compose.waitForIdle()
        assertTrue(vm.committing)
        ui.compose.onNodeWithText("Sign in with a password").assertIsNotEnabled()
        ui.compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
        ui.compose.runOnUiThread { dispatcher!!.onBackPressed() }
        ui.compose.waitForIdle()
        assertFalse(left)
        assertTrue(vm.busy)
        signUp.release()
        harness.signIns.awaitCall("alice-new")
        assertTrue(vm.committing)
        harness.signIns.release("alice-new", wrong)
        harness.settle(vm)
        ui.compose.waitForIdle()
        assertTrue(vm.holdsSignUp)
        ui.compose.runOnUiThread { dispatcher!!.onBackPressed() }
        ui.compose.waitForIdle()
        assertFalse(left)
        assertFalse(vm.joining)
        assertFalse(vm.holdsSignUp)
        assertEquals("alice-new", vm.username)
        ui.compose.runOnUiThread { dispatcher!!.onBackPressed() }
        ui.compose.waitForIdle()
        assertTrue(left)
    }

    // The page's back button does what system Back does with a sign-up
    // held. Red when the button pops the page while the sign-up is held.
    @Test
    fun theBackButtonWithASignUpHeldLeavesTheLinkThenThePage() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        var left = false
        ui.compose.setContent { PageLocals { SignInScreen(onBack = { left = true }, onDone = {}, vm = vm) } }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithContentDescription("Back").performClick()
        ui.compose.waitForIdle()
        assertFalse(left)
        assertFalse(vm.holdsSignUp)
        assertEquals("alice-new", vm.username)
        ui.compose.onNodeWithContentDescription("Back").performClick()
        ui.compose.waitForIdle()
        assertTrue(left)
    }

    // With a sign-up held and the camera open, Back closes the camera first.
    // Red when the page's handler is composed after the camera's.
    @Test
    fun backWithASignUpHeldAndTheCameraOpenClosesTheCamera() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        var left = false
        var dispatcher: OnBackPressedDispatcher? = null
        ui.compose.setContent {
            PageLocals {
                dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                BackHandler { left = true }
                SignInScreen(onBack = { left = true }, onDone = {}, vm = vm)
            }
        }
        ui.compose.runOnIdle { vm.scanning = true }
        ui.compose.waitForIdle()
        ui.compose.runOnUiThread { dispatcher!!.onBackPressed() }
        ui.compose.waitForIdle()
        assertFalse(vm.scanning)
        assertTrue(vm.holdsSignUp)
        assertFalse(left)
    }

    // A sign-up the server made at the home address (the main one refused
    // the connection) signs in at the main address with the form's home, and
    // so does Try again with a corrected home; leaving the link lets the
    // sign-up go and gives the address back. Red when the sign-in goes to the
    // address the sign-up answered at, or the held sign-up does not lock the
    // address.
    @Test
    fun aSignUpMadeAtHomeSignsInAtTheMainAddress() {
        // An address with no server behind it: started to take a port, then
        // closed, so a connection to it is refused at once.
        val dead = MockWebServer()
        val deadAt = dead.startHome()
        dead.close()
        harness.server.dispatcher = PathAnswers(mapOf(JOIN to { joined("alice-new") }))
        val vm = harness.form()
        vm.startJoin(FamilyInviteLink(server = deadAt.toString().removeSuffix("/"), token = "invite-token", home = harness.link))
        harness.fillInvite(vm)
        harness.signIns.hold("alice-new")
        vm.join()
        val call = harness.signIns.awaitCall("alice-new")
        assertEquals(deadAt.toString(), call.request.address)
        assertEquals(harness.link, call.request.home)
        harness.signIns.release("alice-new", wrong)
        harness.settle(vm)
        assertTrue(vm.holdsSignUp)
        vm.home = "192.168.1.30:4533"
        harness.signIns.hold("alice-new")
        vm.join()
        val again = harness.signIns.awaitCall("alice-new", 2)
        assertEquals(deadAt.toString(), again.request.address)
        assertEquals("192.168.1.30:4533", again.request.home)
        harness.signIns.release("alice-new", wrong)
        harness.settle(vm)
        vm.stopJoin()
        assertFalse(vm.holdsSignUp)
        assertEquals("alice-new", vm.username)
    }

    // A page covered by another while it signs in closes itself once it is
    // shown again, removing only its own entry. Red when it pops the top of
    // the stack (Family would go and the join page would stay).
    @Test
    fun aJoinPageCoveredWhileItSignsInClosesItselfOnceShown() {
        harness.server.dispatcher = PathAnswers(mapOf(JOIN to { joined("alice-new") }))
        harness.signIns.hold("alice-new")
        val vm = harness.form()
        val link = invite()
        val route = FamilyJoinRoute(familyAppLink(link), opened = 1)
        val stack = mutableStateListOf<NavKey>(SettingsRoute, route)
        ui.compose.setContent {
            PageLocals {
                when (val top = stack.last()) {
                    is FamilyJoinRoute -> SignInScreen(onBack = { stack.backOne() }, onDone = { stack.removeOwn(top) }, join = link, joinOpened = top.opened, vm = vm)
                    else -> BasicText(top.toString())
                }
            }
        }
        ui.compose.waitForIdle()
        harness.fillInvite(vm)
        vm.join()
        harness.signIns.awaitCall("alice-new")
        ui.compose.runOnIdle { stack.add(FamilyRoute) }
        ui.compose.waitForIdle()
        harness.signIns.release("alice-new")
        harness.settle(vm)
        assertTrue(vm.signedIn)
        ui.compose.waitForIdle()
        assertEquals(listOf(SettingsRoute, route, FamilyRoute), stack.toList())
        ui.compose.runOnIdle { stack.backOne() }
        ui.compose.waitForIdle()
        assertEquals(listOf<NavKey>(SettingsRoute), stack.toList())
    }

    // A page drawn again (as after a rotation) while its received sign-in's
    // step runs keeps it and says nothing about a link coming in while busy.
    // Red when the deep-link guard is keyed on the link (the page would take
    // it again, be refused as busy, and show the busy words).
    @Test
    fun aPageDrawnAgainKeepsItsReceivedSignIn() {
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.allowAtOnce(box)
        harness.signIns.hold("alice")
        val vm = harness.form()
        var shown by mutableStateOf(true)
        ui.compose.setContent { PageLocals { if (shown) SignInScreen(onBack = {}, vm = vm, join = link, joinOpened = 1, onDone = {}) } }
        harness.signIns.awaitCall("alice")
        assertTrue(vm.holdsReceived)
        shown = false
        ui.compose.waitForIdle()
        shown = true
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText(SignInWords.LINK_WHILE_BUSY).assertDoesNotExist()
        assertNull(vm.linkNote)
        assertTrue(vm.holdsReceived)
        harness.signIns.release("alice")
        harness.settle(vm)
    }
}
