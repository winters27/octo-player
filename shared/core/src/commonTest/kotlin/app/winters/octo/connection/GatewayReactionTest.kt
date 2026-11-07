package app.winters.octo.connection

import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// How the form answers a gateway: Advanced opens, a kind is picked only
// when none was, the most specific gateway is kept, and results for an
// address no longer typed count for nothing.
class GatewayReactionTest {
    private val closed = GatewayFormState(advancedOpen = false, kind = GatewayKind.None, detected = null, detectedFor = null)
    private val address = "music.example.com"

    @Test
    fun aGatewayOpensAdvancedAndPicksTheKindThatGetsThrough() {
        val opened = reactToFailure(closed, address, address, Gateway.Authentik)
        assertTrue(opened.advancedOpen)
        assertEquals(GatewayKind.Password, opened.kind)
        assertEquals(Gateway.Authentik, opened.detected)
        assertEquals(address, opened.detectedFor)
        assertEquals(GatewayKind.CloudflareToken, reactToFailure(closed, address, address, Gateway.CloudflareAccess).kind)
        assertEquals(closed, reactToFailure(closed, address, address, null))
    }

    @Test
    fun aKindIsPickedOnlyWhenNoneWas() {
        val chosen = closed.copy(kind = GatewayKind.CloudflareToken)
        assertEquals(GatewayKind.CloudflareToken, reactToFailure(chosen, address, address, Gateway.Authentik).kind)
    }

    @Test
    fun theMostSpecificGatewayIsKept() {
        val authentik = reactToFailure(closed, address, address, Gateway.Authentik)
        assertEquals(Gateway.Authentik, reactToFailure(authentik, address, address, Gateway.PasswordPrompt).detected)
        assertEquals(Gateway.Authentik, reactToFailure(authentik, address, address, Gateway.SignInPage).detected)
        val unknown = reactToFailure(closed, address, address, Gateway.SignInPage)
        assertEquals(Gateway.Authelia, reactToFailure(unknown, address, address, Gateway.Authelia).detected)
        assertEquals(Gateway.Authentik, moreSpecific(Gateway.CloudflareAccess, Gateway.Authentik))
        assertEquals(Gateway.CloudflareAccess, moreSpecific(Gateway.CloudflareAccess, Gateway.PasswordPrompt))
        assertEquals(Gateway.SignInPage, moreSpecific(null, Gateway.SignInPage))
    }

    @Test
    fun aResultForAnAddressNoLongerTypedIsDropped() {
        assertEquals(closed, reactToFailure(closed, "old.example.com", address, Gateway.Authentik))
    }

    @Test
    fun anotherAddressForgetsTheGateway() {
        val opened = reactToFailure(closed, address, address, Gateway.Authentik)
        val moved = addressChanged(opened, "other.example.com")
        assertNull(moved.detected)
        assertNull(moved.detectedFor)
        assertEquals(opened, addressChanged(opened, " $address "))
        // A password prompt at the new address is not outranked by the old Authentik.
        assertEquals(Gateway.PasswordPrompt, reactToFailure(moved, "other.example.com", "other.example.com", Gateway.PasswordPrompt).detected)
    }
}
