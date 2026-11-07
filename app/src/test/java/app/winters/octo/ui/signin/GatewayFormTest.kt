package app.winters.octo.ui.signin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testDevices
import app.winters.octo.data.ConnectionDraft
import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The form's side of a sign-in page in front of the server: a join or a
// hand-over checks with the form's fields, an edit of the sign-in page
// alone is a real edit, and a failure names the page it met.
@RunWith(RobolectricTestRunner::class)
class GatewayFormTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun stop() = server.close()

    private fun ready(main: HttpUrl, gateway: GatewayDraft, home: String = "", pins: Map<String, String> = emptyMap()) =
        (formConnection(main, home, emptyList(), gateway, pins, null) as FormConnection.Ready).settings

    // Red when the join or hand-over client is built from
    // ConnectionSettings() or emptyList() instead of the form.
    @Test
    fun aJoinOrHandOverClientCarriesTheFormsSignInPageFields() {
        val main = server.startHome()
        server.enqueue(MockResponse.Builder().body("{}").build())
        val settings = ready(main, GatewayDraft(GatewayKind.Password, "alice", "app-pass"))
        val (http, _) = ServerClients(context, testDevices()).forJoin(main, settings)
        http.newCall(Request.Builder().url(main.resolve("/api/family/signin")!!).build()).execute().close()
        assertEquals(basicCredentials("alice", "app-pass"), server.takeRequest().headers["Authorization"])
    }

    // Red when formConnection drops the resolved headers.
    @Test
    fun aCloudflareTokenGoesAsItsTwoHeaders() {
        val settings = ready("https://music.example.com/".toHttpUrl(), GatewayDraft(GatewayKind.CloudflareToken, "id.access", "s3cret"))
        val sent = settings.headers.associate { it.name.lowercase() to it.value }
        assertEquals("id.access", sent["cf-access-client-id"])
        assertEquals("s3cret", sent["cf-access-client-secret"])
    }

    // Red when formConnection ignores the home address or the pins.
    @Test
    fun theHomeAddressAndCertificatesComeFromTheForm() {
        val settings = ready("https://music.example.com/".toHttpUrl(), GatewayDraft(), home = "192.168.1.20:4533", pins = mapOf("music.example.com" to "ab12"))
        assertEquals("192.168.1.20", settings.home?.host)
        assertEquals(mapOf("music.example.com" to "ab12"), settings.pins)
    }

    // Red when the form's problem is not passed on.
    @Test
    fun aPublicHttpAddressRefusesTheFields() {
        val form = formConnection("http://music.example.com/".toHttpUrl(), "", emptyList(), GatewayDraft(GatewayKind.Password, "alice", "pw"), emptyMap(), null)
        assertEquals(GatewayWords.NEEDS_HTTPS, (form as FormConnection.Problem).text)
    }

    private val saved = ConnectionDraft(
        address = "https://music.example.com",
        username = "alice",
        authMode = AuthMode.Token,
        home = "",
        headers = emptyList(),
        pins = emptyMap(),
        clientCertAlias = null,
        takesApiKeys = false,
        gateway = GatewayKind.Password,
    )

    private fun edit(gateway: GatewayDraft) = SignInRequest(address = "https://music.example.com", username = "alice", secret = "", gateway = gateway)

    // Red when connectionChanged leaves out the sign-in page: an edit of it
    // alone would take the rename-only path and never be checked or saved.
    @Test
    fun anEditOfTheSignInPageAloneIsMoreThanARename() {
        assertFalse(connectionChanged(edit(GatewayDraft(GatewayKind.Password, saved = GatewayKind.Password)), saved))
        assertTrue(connectionChanged(edit(GatewayDraft(GatewayKind.Password, "alice", "new-app-pass", GatewayKind.Password)), saved))
        assertTrue(connectionChanged(edit(GatewayDraft(GatewayKind.None, saved = GatewayKind.Password)), saved))
        assertTrue(connectionChanged(edit(GatewayDraft(GatewayKind.CloudflareToken, saved = GatewayKind.Password)), saved))
    }

    // Red when gatewayOf never finds the gateway (the form would not open).
    @Test
    fun aFailureNamesTheSignInPageItMet() {
        val met = SignInError.Failed(SubsonicException.NotSubsonic("HTTP 401 from ping", 401, Gateway.Authentik, false))
        assertEquals(Gateway.Authentik, gatewayOf(met))
        assertNull(gatewayOf(SignInError.Failed(SubsonicException.NotSubsonic("HTTP 404 from ping", 404))))
        assertNull(gatewayOf(SignInError.MissingSecret))
    }
}
