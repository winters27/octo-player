package app.winters.octo.desktop.pages

import app.winters.octo.connection.AdvancedGroup
import app.winters.octo.connection.GatewayWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The desktop sign-in form's state: header rows by id through the shared
// reducer, a late saved sign-in page that leaves alone what the person
// touched, a typed value in a saved row counting as a change, the callout
// and key row flags, and which saved secret may stand in.
class SignInFormTest {
    private val kept = SavedServer("https://music.example.com/", "winters", headerNames = listOf("X-Token", "Authorization"), id = "w1")
    private val authorization = SavedGateway(GatewayKind.Password, setOf("Authorization"))

    // Red when a write misses the reducer, or an id is reused.
    @Test
    fun headerRowsAreKeptByIdThroughTheReducer() {
        val form = SignInForm(kept)
        assertEquals(listOf("X-Token", "Authorization"), form.headers.map { it.name })
        assertTrue(form.headers.all { it.saved })
        assertEquals(form.headers.size, form.headerIds.size)
        assertEquals(2, form.headerIds.toSet().size)
        val token = form.headerIds[0]
        val auth = form.headerIds[1]
        val added = form.addHeader()
        assertEquals(added, form.headerIds.last())
        assertEquals(HeaderDraft("", ""), form.headers.last())
        form.setHeaderName(added, "X-Extra")
        form.setHeaderValue(added, "e")
        assertEquals(HeaderDraft("X-Extra", "e"), form.headers.last())
        form.removeHeader(auth)
        assertEquals(listOf(token, added), form.headerIds)
        assertEquals(listOf("X-Token", "X-Extra"), form.headers.map { it.name })
        // An id no row has any more changes nothing.
        form.setHeaderValue(auth, "late")
        form.setHeaderName(auth, "Late")
        assertEquals(listOf("", "e"), form.headers.map { it.value })
        val next = form.addHeader()
        assertFalse("an id is never used twice", next in listOf(token, auth, added))
    }

    // Red when the rename rule is lost on the way to the reducer.
    @Test
    fun aSavedRowStaysSavedOnlyUnderItsOwnName() {
        val form = SignInForm(kept)
        val token = form.headerIds[0]
        form.setHeaderName(token, " x-TOKEN ")
        assertTrue(form.headers[0].saved)
        form.setHeaderValue(token, "v")
        assertTrue(form.headers[0].saved)
        form.setHeaderName(token, "X-Other")
        assertFalse(form.headers[0].saved)
    }

    // Red when the late answer takes a row the person typed in.
    @Test
    fun aValueTypedIntoTheSavedRowBeforeTheAnswerLandsKeepsTheRow() {
        val form = SignInForm(kept, keepsSecret = true)
        val ids = form.headerIds
        form.setHeaderValue(ids[1], "Basic typed")
        form.knowSavedGateway(kept, authorization)
        assertEquals(ids, form.headerIds)
        assertEquals(listOf("X-Token", "Authorization"), form.headers.map { it.name })
        assertEquals("Basic typed", form.headers[1].value)
        assertEquals(GatewayKind.None, form.gatewayKind)
        assertEquals(GatewayKind.None, form.savedGateway)
        assertEquals("Basic typed", form.request().headers.single { it.name == "Authorization" }.value)
        assertTrue(form.changesConnection(kept))
    }

    // Red when the late answer brings back a row the person removed.
    @Test
    fun theSavedRowRemovedBeforeTheAnswerLandsStaysRemoved() {
        val form = SignInForm(kept)
        form.removeHeader(form.headerIds[1])
        val ids = form.headerIds
        form.knowSavedGateway(kept, authorization)
        assertEquals(ids, form.headerIds)
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        assertEquals(GatewayKind.None, form.gatewayKind)
        assertEquals(GatewayKind.None, form.savedGateway)
        assertEquals(GatewayKind.None, form.request().gateway.saved)
        assertTrue(form.request().headers.none { it.name.equals("Authorization", ignoreCase = true) })
    }

    // Red when a removed row that is not a saved name stops the answer.
    @Test
    fun anotherRowRemovedBeforeTheAnswerLandsStillLetsTheSavedRowMove() {
        val form = SignInForm(kept)
        form.removeHeader(form.headerIds[0])
        form.knowSavedGateway(kept, authorization)
        assertTrue(form.headers.isEmpty())
        assertEquals(GatewayKind.Password, form.gatewayKind)
        assertEquals(GatewayKind.Password, form.savedGateway)
    }

    // Red when a row the person added under a saved name is swallowed.
    @Test
    fun aRowAddedUnderASavedNameBeforeTheAnswerLandsChangesNothing() {
        val form = SignInForm(kept)
        val added = form.addHeader()
        form.setHeaderName(added, " authorization ")
        form.setHeaderValue(added, "Bearer mine")
        val ids = form.headerIds
        form.knowSavedGateway(kept, authorization)
        assertEquals(ids, form.headerIds)
        assertEquals(GatewayKind.None, form.gatewayKind)
        assertEquals("Bearer mine", form.request().headers.single { it.name == " authorization " }.value)
    }

    // Untouched saved rows still move, and the others keep their ids.
    @Test
    fun untouchedSavedRowsMoveAndTheRestKeepTheirIds() {
        val form = SignInForm(kept)
        val token = form.headerIds[0]
        form.knowSavedGateway(kept, authorization)
        assertEquals(listOf(token), form.headerIds)
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        assertEquals(GatewayKind.Password, form.gatewayKind)
        assertTrue(form.advancedOpen)
    }

    // A typed value in a saved row is a change; only spaces are not. Red on
    // the old rule, which saw no change and dropped the typed value.
    @Test
    fun aValueTypedIntoASavedRowIsAChangeButSpacesAreNot() {
        val form = SignInForm(kept, keepsSecret = true)
        assertFalse(form.changesConnection(kept))
        form.setHeaderValue(form.headerIds[0], " ")
        assertFalse(form.changesConnection(kept))
        form.setHeaderValue(form.headerIds[0], "x")
        assertTrue(form.changesConnection(kept))
    }

    // Red when the holder drops the callout fields, or a write does not calm.
    @Test
    fun aFailureThatMetASignInPageCallsItOutAndAnyWriteCalmsIt() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.result = false to GatewayWords.failure(Gateway.Authentik, false)
        form.failed(form.attemptAddress, Gateway.Authentik)
        assertTrue(form.callout && form.calloutPending && form.advancedOpen)
        assertTrue(form.showsCallout)
        form.calloutShown()
        assertTrue(form.callout)
        assertFalse(form.calloutPending)
        form.pointTo()
        assertTrue(form.calloutPending)
        form.result = null
        assertFalse(form.callout || form.calloutPending)
        assertFalse(form.showsCallout)
        form.pointTo()
        assertFalse("nothing to point to", form.calloutPending)
        // The same failure again calls it out again.
        form.result = false to GatewayWords.failure(Gateway.Authentik, false)
        form.failed(form.attemptAddress, Gateway.Authentik)
        assertTrue(form.callout && form.calloutPending)
        form.sending()
        assertFalse(form.callout || form.calloutPending)
    }

    // Red when typing in the sign-in page group leaves the callout up.
    @Test
    fun typingInTheSignInPageGroupEndsTheCallout() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.result = false to GatewayWords.failure(Gateway.Authentik, false)
        form.failed(form.attemptAddress, Gateway.Authentik)
        form.typeGatewayFirst("alice")
        assertFalse(form.callout)
        assertFalse(form.showsCallout)
        assertEquals(null, form.result)
    }

    // Red when the key row is not asked for, or a write drops the ask.
    @Test
    fun pickingApiKeyWithNoKeyKeptAsksForTheKeyRow() {
        val form = SignInForm()
        form.pickMethod(AuthMode.ApiKey)
        assertTrue(form.useApiKey)
        assertEquals(AuthMode.ApiKey, form.mode)
        assertTrue(form.keyPending)
        form.result = false to "Wrong username or password."
        assertTrue("a write leaves the key row waiting", form.keyPending)
        form.keyShown()
        assertFalse(form.keyPending)
        form.pickMethod(AuthMode.LegacyPassword)
        assertTrue(form.legacyPassword)
        assertFalse(form.useApiKey)
        assertEquals(AuthMode.LegacyPassword, form.mode)
        form.pickMethod(AuthMode.ApiKey)
        assertTrue(form.keyPending)
        form.pickMethod(AuthMode.Token)
        assertFalse(form.keyPending)
        assertFalse(form.legacyPassword || form.useApiKey)
        form.pickMethod(AuthMode.ApiKey)
        form.sending()
        assertFalse(form.keyPending)
    }

    // A kept key stands in for an empty key field and is not asked for; it
    // never stands in for a password. Red on the old rule, where any kept
    // secret stood in for either.
    @Test
    fun aKeptKeyStandsInOnlyForAnApiKey() {
        val keyServer = kept.copy(authMode = AuthMode.ApiKey, username = "")
        val form = SignInForm(keyServer, keepsSecret = true)
        assertEquals(AuthMode.ApiKey, form.mode)
        assertTrue(form.keepsKey)
        assertTrue(form.ready)
        form.pickMethod(AuthMode.Token)
        form.username = "winters"
        assertFalse("a kept key is no password", form.keepsPassword)
        assertFalse(form.ready)
        form.pickMethod(AuthMode.ApiKey)
        assertFalse("the key is kept, so its row is not asked for", form.keyPending)
    }

    @Test
    fun aKeptPasswordStandsInOnlyForAPasswordMethod() {
        val form = SignInForm(kept, keepsSecret = true)
        assertTrue(form.keepsPassword)
        assertTrue(form.ready)
        form.pickMethod(AuthMode.ApiKey)
        assertFalse(form.keepsKey)
        assertFalse(form.ready)
        assertTrue(form.keyPending)
    }

    // Red when focus skips the API key of a kept server signed in with one.
    @Test
    fun focusStartsAtTheSecretOfAKnownServerAndAccount() {
        assertTrue(SignInForm(kept).startsAtPassword)
        assertFalse(SignInForm().startsAtPassword)
        assertTrue(SignInForm(kept.copy(authMode = AuthMode.ApiKey, username = "")).startsAtPassword)
        assertFalse(SignInForm(kept.copy(username = "")).startsAtPassword)
    }

    // Red when the form draws Signing in while joining.
    @Test
    fun advancedLeavesOutSigningInWhileJoining() {
        val form = SignInForm()
        assertEquals(listOf(AdvancedGroup.GettingThere, AdvancedGroup.SigningIn, AdvancedGroup.SpecialSetups), form.layout.groups.map { it.group })
        assertEquals(listOf(AuthMode.Token, AuthMode.LegacyPassword, AuthMode.ApiKey), form.layout.methods)
        form.take(FamilyInviteLink("https://music.example.com", "t1"))
        assertEquals(listOf(AdvancedGroup.GettingThere, AdvancedGroup.SpecialSetups), form.layout.groups.map { it.group })
        assertTrue(form.layout.methods.isEmpty())
    }
}
