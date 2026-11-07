package app.winters.octo.ui.family

import app.winters.octo.connection.GATEWAY_PAGE
import app.winters.octo.connection.GatewayFormState
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.PAGE_TYPE
import app.winters.octo.connection.reactToFailure
import app.winters.octo.connection.securedHttp
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Joining, handing over and managing the family behind a sign-in page:
// the form's gateway headers go along, and a gateway's answer is named,
// never taken for an expired link or a crash.
class GatewayJoinTest {
    private val server = FamilyFakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val basic = basicCredentials("alice", "app password")

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun signedHttp() = securedHttp(server.url, mapOf("Authorization" to basic))

    private fun authentikFlow(path: String) {
        server.page(path) { MockResponse.Builder().code(302).addHeader("Location", "/if/flow/default-authentication-flow/").build() }
        server.page("/if/flow/") { MockResponse.Builder().addHeader("Content-Type", PAGE_TYPE).body(GATEWAY_PAGE).build() }
    }

    private fun until(check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) delay(10) }
    }

    @Test
    fun anInviteBehindAGatewayFailsWithItsWordsAndOpensAdvanced() = runBlocking {
        authentikFlow("/api/family/join")
        val outcome = joinWithInvite(server.url, "tok_1", "Alex", "a long password", signedHttp()) as JoinOutcome.Failed
        assertEquals(Gateway.Authentik, outcome.gateway)
        assertTrue(outcome.loginSent)
        assertFalse(outcome.unreachable)
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), outcome.message)
        assertEquals(basic, server.calls.first().headers["Authorization"])
        val form = reactToFailure(GatewayFormState(false, GatewayKind.None, null, null), "music", "music", outcome.gateway)
        assertTrue(form.advancedOpen)
        assertEquals(GatewayKind.Password, form.kind)
    }

    @Test
    fun aGatewayInTheHandOverPollIsNotAnExpiredLink() = runBlocking {
        val link = FamilyHandOverLink(server.url.toString().removeSuffix("/"), "tok_9", HandOverBox.newKey(), "https://music.example.com")
        server.answer("redeemFamilySignIn") { """"familySignInRedeemed":{"id":"r_1"}""" }
        server.page("/rest/getFamilySignInRedeem") {
            MockResponse.Builder().code(401).addHeader("Content-Type", PAGE_TYPE)
                .addHeader("Location", "https://auth.example.com/?rd=x&rm=GET").body("<a>401 Unauthorized</a>").build()
        }
        val result = receiveHandOver(link, signedHttp(), "Pixel 9", FamilyPlatform.Android, pollMs = 10) as HandOverResult.Refused
        assertEquals(Gateway.Authelia, result.gateway)
        assertTrue(result.loginSent)
        assertEquals(GatewayWords.failure(Gateway.Authelia, true), result.message)
        assertNotEquals(HANDOVER_EXPIRED, result.message)
        assertEquals(basic, server.called("redeemFamilySignIn").single().headers["Authorization"])
    }

    @Test
    fun aGatewayAtRedeemSaysSoToo() = runBlocking {
        val link = FamilyHandOverLink(server.url.toString().removeSuffix("/"), "tok_9", HandOverBox.newKey(), "https://music.example.com")
        server.page("/rest/redeemFamilySignIn") {
            MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"Restricted\"").body("").build()
        }
        val result = receiveHandOver(link, signedHttp(), "Pixel 9", FamilyPlatform.Android, pollMs = 10) as HandOverResult.Refused
        assertEquals(Gateway.PasswordPrompt, result.gateway)
        assertEquals(GatewayWords.failure(Gateway.PasswordPrompt, true), result.message)
    }

    @Test
    fun aManagersChangeBehindAGatewaySaysWhatAnswered() {
        authentikFlow("/api/family/members")
        val model = FamilyModel({ server.client() }, { true }, scope, pollMs = 60_000)
        model.addMember("sam", "Sam", FamilyPreset.Kid)
        until { model.said != null }
        assertEquals(GatewayWords.failure(Gateway.Authentik, false), model.said)
    }
}
