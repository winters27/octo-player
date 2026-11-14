package app.winters.octo.ui.signin

import app.winters.octo.data.ConnectionDraft
import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.SignInRequest
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.gateway.GatewayKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The view model's own rules that need no page: a saved header holding only
// spaces is no change to save, and a family link the page was opened for
// starts once per opening.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInWiringTest {
    // The switch's sign-in, recorded: no row here signs in, and a call made
    // inside the view model's work would never reach JUnit by itself.
    private val signIns = GatedSignIns()

    @After
    fun noSignInWasMade() = assertEquals(emptyList<RecordedSignIn>(), signIns.calls.value)

    private val saved = ConnectionDraft(
        address = "https://music.example.com",
        username = "alice",
        authMode = AuthMode.Token,
        home = "",
        headers = listOf(HeaderDraft("X-Token", "", saved = true)),
        pins = emptyMap(),
        clientCertAlias = null,
        takesApiKeys = false,
        gateway = GatewayKind.None,
    )

    private fun request(value: String) = SignInRequest(
        address = "https://music.example.com",
        username = "alice",
        secret = "",
        headers = listOf(HeaderDraft("X-Token", value, saved = true)),
    )

    // Red when a value of only spaces in a saved row counts as a change (the
    // full save would then send it and drop the saved one).
    @Test
    fun aSavedHeaderHoldingOnlySpacesIsNoChange() {
        assertFalse(connectionChanged(request("  "), saved))
        assertTrue(connectionChanged(request("x"), saved))
    }

    // Red when the guard is keyed on the link (a redraw would start it again)
    // or kept nowhere.
    @Test
    fun anOpeningStartsItsLinkOnce() {
        val vm = signInForm(signIns)
        val link = FamilyInviteLink(server = "https://music.example.com", token = "invite-token")
        var befores = 0
        vm.open(link, 1) { befores++ }
        assertTrue(vm.joining)
        vm.stopJoin()
        vm.open(link, 1) { befores++ }
        assertFalse(vm.joining)
        assertEquals(1, befores)
        vm.open(link, 2) { befores++ }
        assertTrue(vm.joining)
        assertEquals(2, befores)
    }
}
