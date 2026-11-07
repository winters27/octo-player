package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The sign-in page fields and the raw rows made into the headers to keep,
// or the problem that keeps the form from going.
class GatewayFormTest {
    private val savedBasic = "Basic YWxpY2U6eA=="

    private fun resolve(
        gateway: GatewayDraft,
        rows: List<HeaderDraft> = emptyList(),
        saved: List<ServerHeader> = emptyList(),
        https: Boolean = true,
        private: Boolean = false,
    ) = resolveConnectionHeaders(rows, gateway, saved, https, private)

    private fun ready(result: ResolvedHeaders): Map<String, String> = (result as ResolvedHeaders.Ready).headers.associate { it.name to it.value }

    private fun problem(result: ResolvedHeaders): String = (result as ResolvedHeaders.Problem).text

    @Test
    fun noneSendsNoGatewayHeadersAndDropsTheSavedOnes() {
        val saved = listOf(ServerHeader("Authorization", savedBasic), ServerHeader("X-Proxy-Token", "t"))
        val rows = listOf(HeaderDraft("X-Proxy-Token", "", saved = true))
        assertEquals(mapOf("X-Proxy-Token" to "t"), ready(resolve(GatewayDraft(GatewayKind.None, saved = GatewayKind.Password), rows, saved)))
    }

    @Test
    fun anUnchangedEditOfAServerSavedBeforeKeepsWorking() {
        // An older version saved the gateway as a raw Basic row; the form
        // shows it as the fields and hides the row.
        val saved = listOf(ServerHeader("Authorization", savedBasic), ServerHeader("X-Proxy-Token", "t"))
        val rows = listOf(HeaderDraft("X-Proxy-Token", "", saved = true))
        val draft = GatewayDraft(GatewayKind.Password, saved = GatewayKind.Password)
        assertEquals(mapOf("X-Proxy-Token" to "t", "Authorization" to savedBasic), ready(resolve(draft, rows, saved)))
        val token = listOf(ServerHeader("CF-Access-Client-Id", "id.access"), ServerHeader("CF-Access-Client-Secret", "s3cret"))
        assertEquals(
            mapOf("CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "s3cret"),
            ready(resolve(GatewayDraft(GatewayKind.CloudflareToken, saved = GatewayKind.CloudflareToken), saved = token)),
        )
    }

    @Test
    fun bothFieldsMakeTheHeaders() {
        assertEquals(
            mapOf("Authorization" to basicCredentials("alice", " app password ")),
            ready(resolve(GatewayDraft(GatewayKind.Password, "  alice ", " app password "))),
        )
        assertEquals(
            mapOf("CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "s3cret"),
            ready(resolve(GatewayDraft(GatewayKind.CloudflareToken, "id.access", "s3cret"))),
        )
    }

    @Test
    fun oneFieldOrANewKindWithAnEmptyFieldIsAProblem() {
        assertEquals(GatewayWords.PROBLEM_BOTH_PASSWORD, problem(resolve(GatewayDraft(GatewayKind.Password, "alice", ""))))
        assertEquals(GatewayWords.PROBLEM_BOTH_PASSWORD, problem(resolve(GatewayDraft(GatewayKind.Password, "   ", "pw"))))
        assertEquals(GatewayWords.PROBLEM_BOTH_TOKEN, problem(resolve(GatewayDraft(GatewayKind.CloudflareToken, "", "s3cret"))))
        val saved = listOf(ServerHeader("Authorization", savedBasic))
        assertEquals(GatewayWords.PROBLEM_BOTH_TOKEN, problem(resolve(GatewayDraft(GatewayKind.CloudflareToken, saved = GatewayKind.Password), saved = saved)))
        // The saved kind with one field typed is not a keep.
        assertEquals(GatewayWords.PROBLEM_BOTH_PASSWORD, problem(resolve(GatewayDraft(GatewayKind.Password, "alice", "", GatewayKind.Password), saved = saved)))
    }

    @Test
    fun aColonInTheUsernameIsAProblem() {
        assertEquals(GatewayWords.PROBLEM_COLON, problem(resolve(GatewayDraft(GatewayKind.Password, "al:ice", "pw"))))
        // A colon in the password is fine.
        assertTrue(resolve(GatewayDraft(GatewayKind.Password, "alice", "p:w")) is ResolvedHeaders.Ready)
    }

    @Test
    fun aControlCharacterOrAnOddTokenCharacterIsAProblem() {
        assertEquals(GatewayWords.PROBLEM_CONTROL, problem(resolve(GatewayDraft(GatewayKind.Password, "alice", "pass\nword"))))
        assertEquals(GatewayWords.PROBLEM_CONTROL, problem(resolve(GatewayDraft(GatewayKind.Password, "al\u0007ice", "pw"))))
        assertEquals(GatewayWords.PROBLEM_CONTROL, problem(resolve(GatewayDraft(GatewayKind.CloudflareToken, "idé", "s3cret"))))
        // A non-ASCII password is fine: it goes as UTF-8.
        assertTrue(resolve(GatewayDraft(GatewayKind.Password, "alice", "pässword")) is ResolvedHeaders.Ready)
    }

    @Test
    fun aRowWithTheChosenKindsNameIsAProblemAndOnlyThat() {
        val bearer = listOf(HeaderDraft("authorization", "Bearer t0k"))
        assertEquals(GatewayWords.PROBLEM_RESERVED_ROW, problem(resolve(GatewayDraft(GatewayKind.Password, "alice", "pw"), bearer)))
        // A Bearer row with no fields keeps working (oauth2-proxy).
        assertEquals(mapOf("authorization" to "Bearer t0k"), ready(resolve(GatewayDraft(GatewayKind.None), bearer)))
        // Cloudflare in front of a Bearer setup: both go.
        assertEquals(
            mapOf("authorization" to "Bearer t0k", "CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "s3cret"),
            ready(resolve(GatewayDraft(GatewayKind.CloudflareToken, "id.access", "s3cret"), bearer)),
        )
        val cfRow = listOf(HeaderDraft("cf-access-client-id", "x"))
        assertEquals(GatewayWords.PROBLEM_RESERVED_ROW, problem(resolve(GatewayDraft(GatewayKind.CloudflareToken, "id.access", "s3cret"), cfRow)))
    }

    @Test
    fun plainHttpAcrossTheInternetIsRefusedAndAtHomeAllowed() {
        val draft = GatewayDraft(GatewayKind.Password, "alice", "pw")
        assertEquals(GatewayWords.NEEDS_HTTPS, problem(resolve(draft, https = false, private = false)))
        assertTrue(resolve(draft, https = false, private = true) is ResolvedHeaders.Ready)
        // A raw gateway row is held to the same rule.
        assertEquals(GatewayWords.NEEDS_HTTPS, problem(resolve(GatewayDraft(), listOf(HeaderDraft("Authorization", "Bearer t0k")), https = false)))
        // Other headers are not.
        assertTrue(resolve(GatewayDraft(), listOf(HeaderDraft("X-Proxy-Token", "t")), https = false) is ResolvedHeaders.Ready)
        assertTrue(gatewayAllowed(mainHttps = true, mainPrivate = false))
        assertTrue(gatewayAllowed(mainHttps = false, mainPrivate = true))
        assertFalse(gatewayAllowed(mainHttps = false, mainPrivate = false))
    }

    @Test
    fun theDraftNeverPrintsItsFields() {
        val draft = GatewayDraft(GatewayKind.CloudflareToken, "id.access", "s3cret")
        assertFalse("id.access" in draft.toString())
        assertFalse("s3cret" in draft.toString())
    }
}
