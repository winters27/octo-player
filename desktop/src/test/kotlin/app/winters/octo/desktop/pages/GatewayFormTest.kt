package app.winters.octo.desktop.pages

import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayWords
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The sign-in form's sign-in page: a failure opens its fields with the kind
// picked, a stale answer changes nothing, another address forgets it, a
// saved one shows as its kind, a change to it is a change to the
// connection, and a link to another server leaves the last one's details
// behind.
class GatewayFormTest {
    private val kept = SavedServer("https://music.example.com/", "winters", headerNames = listOf("X-Token", "Authorization"), id = "w1")

    // Red when `failed` does not follow the form reaction.
    @Test
    fun aSignInPageOpensItsFieldsWithTheKindPicked() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.failed(form.attemptAddress, Gateway.CloudflareAccess)
        assertTrue(form.advancedOpen)
        assertEquals(GatewayKind.CloudflareToken, form.gatewayKind)
        assertEquals(GatewayWords.hint(Gateway.CloudflareAccess), form.gatewayHint)
    }

    // Red when the try's address is not passed (the current one is used twice).
    @Test
    fun anAnswerForAnAddressNoLongerTypedChangesNothing() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        val tried = form.attemptAddress
        form.typeAddress("https://other.example.com")
        form.failed(tried, Gateway.Authentik)
        assertFalse(form.advancedOpen)
        assertEquals(GatewayKind.None, form.gatewayKind)
    }

    // Red when typing an address does not call addressChanged.
    @Test
    fun anotherAddressForgetsTheSignInPageMet() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.failed(form.attemptAddress, Gateway.Authentik)
        assertEquals(Gateway.Authentik, form.detected)
        form.typeAddress("https://other.example.com")
        assertNull(form.detected)
    }

    // Red when the saved kind's rows stay among the headers, or when the
    // rename-only check leaves the sign-in page out.
    @Test
    fun aSavedSignInPageShowsAsItsKindAndAChangeToItIsAConnectionChange() {
        val form = SignInForm(kept, keepsSecret = true)
        form.knowSavedGateway(kept, SavedGateway(GatewayKind.Password, setOf("Authorization")))
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        assertEquals(GatewayKind.Password, form.gatewayKind)
        assertTrue(form.keepsSavedGateway)
        assertTrue(form.advancedOpen)
        assertFalse("the saved sign-in page alone changes nothing", form.changesConnection(kept))
        form.typeGatewayFirst("alice")
        assertTrue(form.changesConnection(kept))
        form.typeGatewayFirst("")
        form.pickGateway(GatewayKind.None)
        assertTrue(form.changesConnection(kept))
    }

    // Red when take(link) keeps another server's rows and sign-in page.
    @Test
    fun aLinkToAnotherServerLeavesTheLastOnesDetailsBehind() {
        val form = SignInForm(kept)
        form.knowSavedGateway(kept, SavedGateway(GatewayKind.Password, setOf("Authorization")))
        form.take(FamilySignInPrefill("https://music.example.com"))
        assertEquals("the same server keeps them", listOf("X-Token"), form.headers.map { it.name })
        assertEquals(GatewayKind.Password, form.request().gateway.saved)
        form.take(FamilyInviteLink("https://other.example.com", "t1"))
        assertTrue(form.headers.isEmpty())
        assertEquals(GatewayDraft(), form.request().gateway)
    }
}
