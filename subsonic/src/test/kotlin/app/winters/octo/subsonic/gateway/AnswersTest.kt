package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.subsonic.FamilyWeb
import app.winters.octo.subsonic.OCTO_FAMILY
import app.winters.octo.subsonic.PagedValues
import app.winters.octo.subsonic.StarredItem
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.redeemFamilySignIn
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Every JSON route reads a gateway's answer through the answer reader, on
// a client set up as the apps set up theirs.
class AnswersTest {
    private val own = MockWebServer()
    private val other = MockWebServer()
    private val basic = basicCredentials("alice", "app password")

    @Before
    fun start() {
        own.start()
        other.start()
    }

    @After
    fun stop() {
        own.close()
        other.close()
    }

    private fun answer(code: Int, vararg headers: Pair<String, String>, body: String = AUTHENTIK_PAGE) =
        MockResponse.Builder().code(code).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.body(body).build()

    // Each gateway as a stub gives it, with the gateway it must be named.
    private fun cases(): List<Pair<Gateway, () -> Unit>> = listOf(
        Gateway.Authentik to {
            own.enqueue(answer(302, "Location" to "/outpost.goauthentik.io/start?rd=x", "Set-Cookie" to "authentik_proxy_t=1; Path=/", body = ""))
            own.enqueue(answer(200, "Content-Type" to HTML))
        },
        Gateway.Authentik to { own.enqueue(answer(401, "Content-Type" to HTML)) },
        Gateway.Authelia to {
            own.enqueue(answer(401, "Content-Type" to HTML, "Location" to "https://auth.example.com/?rd=x&rm=GET", body = "<a>401 Unauthorized</a>"))
        },
        Gateway.CloudflareAccess to {
            own.enqueue(answer(403, "Content-Type" to "text/html", "cf-access-aud" to "4714c135", "cf-access-domain" to "music.example.com", body = "<html>Forbidden</html>"))
        },
        Gateway.PasswordPrompt to {
            own.enqueue(answer(401, "Content-Type" to "text/html", "WWW-Authenticate" to "Basic realm=\"Restricted\"", body = "<html>401</html>"))
        },
        Gateway.SignInPage to {
            own.enqueue(answer(302, "Location" to other.url("/login").toString(), body = ""))
            other.enqueue(answer(200, "Content-Type" to HTML, body = "<html><form>Connect</form></html>"))
        },
    )

    private suspend fun refusal(call: suspend () -> Unit): SubsonicException.NotSubsonic {
        val error = runCatching { call() }.exceptionOrNull()
        assertTrue("got $error", error is SubsonicException.NotSubsonic)
        return error as SubsonicException.NotSubsonic
    }

    private fun client(headers: Map<String, String> = emptyMap()) =
        SubsonicClient(own.url("/"), Credentials("alice", "pw"), securedClient(own.url("/"), headers))

    @Test
    fun everyGatewayIsNamedOnASubsonicCall() = runTest {
        for ((gateway, enqueue) in cases()) {
            enqueue()
            assertEquals(gateway, refusal { client().ping() }.gateway)
        }
    }

    @Test
    fun everyGatewayIsNamedOnAnAnswerReadAsItArrives() = runTest {
        val file = File.createTempFile("answers", ".json").apply { deleteOnExit() }
        try {
            for ((gateway, enqueue) in cases()) {
                enqueue()
                assertEquals(gateway, refusal { client().starredInto { } }.gateway)
                enqueue()
                assertEquals(gateway, refusal { client().playlistInto("p", file) { } }.gateway)
                enqueue()
                assertEquals(gateway, refusal { client().createPlaylist("Road", PagedValues.of(listOf("s1"))) }.gateway)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun anAnswerReadAsItArrivesStillReadsWhole() = runTest {
        own.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","starred2":{"song":[{"id":"s1","title":"One"}]}}}""").build())
        val pages = mutableListOf<StarredItem>()
        client().starredInto { pages += it }
        assertEquals(1, pages.size)
    }

    @Test
    fun everyGatewayIsNamedOnAFamilyPageJoin() = runTest {
        for ((gateway, enqueue) in cases()) {
            enqueue()
            assertEquals(gateway, refusal { FamilyWeb(own.url("/"), securedClient(own.url("/"))).join("tok", "a long password", "Alex") }.gateway)
        }
    }

    @Test
    fun everyGatewayIsNamedOnAnOpenFamilyCall() = runTest {
        for ((gateway, enqueue) in cases()) {
            enqueue()
            assertEquals(gateway, refusal { redeemFamilySignIn(own.url("/"), securedClient(own.url("/")), "tok", "Phone", FamilyPlatform.Android) }.gateway)
        }
    }

    @Test
    fun loginSentOnlyWithTheGatewayConfigured() = runTest {
        own.enqueue(answer(401, "Content-Type" to HTML))
        val signedIn = refusal { client(mapOf("Authorization" to basic)).ping() }
        assertTrue(signedIn.loginSent)
        assertEquals(Gateway.Authentik, signedIn.gateway)
        assertEquals(basic, own.takeRequest().headers["Authorization"])
        own.enqueue(answer(401, "Content-Type" to HTML))
        assertFalse(refusal { client(mapOf("X-Proxy-Token" to "t")).ping() }.loginSent)
    }

    @Test
    fun loginSentReadsTheFirstHopWhenTheLastWentElsewhere() = runTest {
        own.enqueue(answer(302, "Location" to other.url("/login").toString(), body = ""))
        other.enqueue(answer(200, "Content-Type" to HTML, body = "<html><form>Connect</form></html>"))
        val page = refusal { client(mapOf("Authorization" to basic)).ping() }
        assertEquals(Gateway.SignInPage, page.gateway)
        assertTrue(page.loginSent)
        assertNull(other.takeRequest().headers["Authorization"])
    }

    @Test
    fun anErrorStatusKeepsItsWords() = runTest {
        own.enqueue(answer(401, "Content-Type" to HTML))
        val page = refusal { client().ping() }
        assertEquals("HTTP 401 from ping", page.message)
        assertEquals(401, page.status)
    }

    @Test
    fun aNoContentOrBlankAnswerPassesAsBefore() = runTest {
        own.enqueue(MockResponse.Builder().code(204).build())
        own.enqueue(MockResponse.Builder().body("  \n").build())
        val web = FamilyWeb(own.url("/"), securedClient(own.url("/")))
        assertEquals("", web.join("tok", "a long password", "Alex"))
        assertEquals("", web.join("tok", "a long password", "Alex"))
        own.enqueue(MockResponse.Builder().code(204).build())
        val ping = refusal { client().ping() }
        assertNull(ping.gateway)
        assertNull(ping.status)
    }

    @Test
    fun aFamilyPageBehindAGatewayNeverCrashesOnItsPage() = runTest {
        own.enqueue(answer(302, "Location" to "/if/flow/default-authentication-flow/", body = ""))
        own.enqueue(answer(200, "Content-Type" to HTML))
        val web = FamilyWeb(own.url("/"), securedClient(own.url("/")))
        assertEquals(Gateway.Authentik, refusal { web.addMember("sam", "Sam", FamilyPreset.Kid) }.gateway)
        own.enqueue(MockResponse.Builder().body("""{"inviteLink":[]}""").build())
        assertNull(refusal { web.newInvite("sam") }.gateway)
    }

    @Test
    fun aPasswordPromptOnTheFamilyPageIsNotAWrongPassword() = runTest {
        own.enqueue(answer(401, "WWW-Authenticate" to "Basic realm=\"Restricted\"", body = ""))
        assertEquals(Gateway.PasswordPrompt, refusal { FamilyWeb(own.url("/"), securedClient(own.url("/"))).resetMember("sam") }.gateway)
    }

    @Test
    fun aGatewayLeavesAnExtensionUnknown() = runTest {
        own.enqueue(answer(401, "Content-Type" to HTML))
        own.enqueue(MockResponse.Builder().code(404).body("Not found").build())
        val signedIn = client()
        assertNull(signedIn.supportsIfKnown(OCTO_FAMILY))
        assertEquals(false, signedIn.supportsIfKnown(OCTO_FAMILY))
    }
}
