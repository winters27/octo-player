package app.winters.octo.ui.signin

import app.winters.octo.data.SignInError
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.family.HandOverSignIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A form is in the app's set of open sign-ins while it runs, while a
// certificate question waits, and while it holds a received sign-in or a
// finished sign-up, and out of it once it leaves the link, signs in or is
// cleared. The set changes on the view model's thread, so every row waits
// for it (at most 5 s), never reads it at once.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInRunningTest {
    private val harness = JoinHarness()

    @Before
    fun start() = harness.start()

    @After
    fun stop() = harness.stop()

    private val wrong = SignInError.Failed(SubsonicException.WrongCredentials("no")) to null

    private fun typed(): SignInViewModel = harness.form().apply {
        typeAddress(harness.link)
        username = "alice"
        password = "pw"
    }

    // Red when the marker misses `busy`, or a sign-in leaves the form marked.
    @Test
    fun aFormIsOpenWhileItRunsAndNotOnceSignedIn() {
        val vm = typed()
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        harness.awaitOpen(vm)
        harness.signIns.release("alice")
        harness.settle(vm)
        harness.awaitClosed(vm)
        assertTrue(vm.signedIn)
    }

    // Red when the marker misses a question.
    @Test
    fun aFormIsOpenWhileACertificateQuestionWaits() {
        val vm = typed()
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", SignInError.Untrusted("127.0.0.1", "ab12") to null)
        harness.settle(vm)
        harness.awaitOpen(vm)
        vm.distrust()
        harness.awaitClosed(vm)
    }

    // Red when `received` is not state the marker sees.
    @Test
    fun aFormIsOpenWhileItHoldsAReceivedSignIn() {
        val vm = harness.form()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.allowAtOnce(box)
        harness.signIns.hold("alice")
        vm.startJoin(link)
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        assertTrue(vm.holdsReceived)
        harness.awaitOpen(vm)
        vm.stopJoin()
        harness.awaitClosed(vm)
    }

    // Red when `signedUp` is not state the marker sees, or leaving the link
    // keeps the sign-up.
    @Test
    fun aFormIsOpenWhileItHoldsASignUpAndLeavingTheLinkClosesIt() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        harness.awaitOpen(vm)
        vm.stopJoin()
        harness.awaitClosed(vm)
        assertEquals("alice-new", vm.username)
    }

    // Red when a cleared form stays in the set (a link would be refused for
    // ever).
    @Test
    fun aClearedFormLeavesTheSet() {
        val vm = typed()
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        harness.awaitOpen(vm)
        harness.clear()
        harness.awaitClosed(vm)
    }
}
