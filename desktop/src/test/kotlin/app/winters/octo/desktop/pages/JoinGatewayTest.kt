package app.winters.octo.desktop.pages

import app.winters.octo.connection.GatewayWords
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.ui.family.HANDOVER_DENIED
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Joining a family and taking a hand-over through a sign-in page: the
// form's fields go along on a client of its own, and a refusal stops and
// opens them.
class JoinGatewayTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()

    @After fun stop() = server.close()

    private fun accounts(): Accounts {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val security = ServerSecurity(settings)
        return Accounts(settings, FakeSecrets(), security.install(OkHttpClient.Builder()).build(), security)
    }

    private fun fill(form: SignInForm) {
        form.pickGateway(GatewayKind.Password)
        form.typeGatewayFirst("alice")
        form.typeGatewaySecond("app-pw")
    }

    // Authelia in front of the server: only requests with alice's app
    // password get through to `answer`.
    private fun guarded(endpoint: String, answer: (RecordedRequest) -> MockResponse) {
        server.respond(endpoint) { request -> if (request.headers["Authorization"] == basicCredentials("alice", "app-pw")) answer(request) else SignInPages.authelia() }
    }

    private fun invited(): SignInForm = SignInForm().also { form ->
        form.take(FamilyInviteLink(server.address, "t1"))
        form.inviteName = "Sam"
        form.invitePassword = "a-long-password"
        form.inviteAgain = "a-long-password"
    }

    // Red when the join goes without the form's sign-in page fields.
    @Test
    fun anInviteJoinsAndSignsInThroughTheSignInPageFields() = runTest {
        guarded("join") { MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"username":"sam"}""").build() }
        guarded("ping") { server.okResponse(type = "octo") }
        guarded("getOpenSubsonicExtensions") { server.okResponse(""""openSubsonicExtensions":[]""", type = "octo") }
        val form = invited()
        fill(form)
        assertNotNull(form.result?.second, signUpFromForm(accounts(), form))
        assertEquals(basicCredentials("alice", "app-pw"), server.calls.first { it.url.pathSegments.last() == "join" }.headers["Authorization"])
    }

    // Red when the refusal does not reach the form reaction.
    @Test
    fun aRefusedInviteOpensTheSignInPageFields() = runTest {
        guarded("join") { MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"username":"sam"}""").build() }
        val form = invited()
        assertNull(signUpFromForm(accounts(), form))
        assertTrue(form.advancedOpen)
        assertEquals(GatewayKind.Password, form.gatewayKind)
        assertEquals(GatewayWords.failure(Gateway.Authelia, false), form.result?.second)
        // The group calls the refusal out and waits to be brought into view.
        assertTrue(form.callout && form.calloutPending && form.showsCallout)
    }

    // A hand-over meeting a sign-in page stops (no "expired"), opens the
    // fields, and Try again sends them. Red when the refusal is read as
    // expired, or the fields do not go along.
    @Test
    fun aHandOverMeetingASignInPageStopsAndTryAgainSendsTheFields() = runTest {
        guarded("redeemFamilySignIn") { server.okResponse(""""familySignInRedeemed":{"id":"r1"}""", type = "octo") }
        guarded("getFamilySignInRedeem") { server.okResponse(""""familySignInRedeem":{"state":"Denied"}""", type = "octo") }
        val form = SignInForm()
        form.take(FamilyHandOverLink(base = server.address, token = "t1", key = "k", server = server.address))
        val accounts = accounts()
        assertNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertTrue(form.advancedOpen)
        assertEquals(GatewayKind.Password, form.gatewayKind)
        assertEquals(GatewayWords.failure(Gateway.Authelia, false), form.result?.second)
        assertTrue(form.callout && form.calloutPending && form.showsCallout)
        fill(form)
        assertFalse("typing in the group ends the callout", form.callout)
        assertNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertEquals(HANDOVER_DENIED, form.result?.second)
        assertEquals(basicCredentials("alice", "app-pw"), server.calls.last { it.url.pathSegments.last() == "redeemFamilySignIn" }.headers["Authorization"])
    }
}
