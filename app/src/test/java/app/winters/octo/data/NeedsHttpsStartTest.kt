package app.winters.octo.data

import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.resolveConnectionHeaders
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.ui.nav.ServerForm
import app.winters.octo.ui.signin.keepsSavedSecret
import app.winters.octo.ui.signin.signsInAgainTo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// A kept server whose sign-in page password would cross the internet
// readable (an older version's raw Authorization row on a plain http
// address outside the home network) is never signed in to at start and
// never asked anything, in use or not; its sign-in form opens instead.
class NeedsHttpsStartTest {
    private val public = "http://music.example.com/".toHttpUrl()
    private val signIn = ConnectionSettings(headers = listOf(ServerHeader("Authorization", "Basic YTpi")))

    // Red when atStart skips the needs-https rule, or puts it before the
    // lost-key case.
    @Test
    fun aStartNeverSignsInWhereThePasswordWouldCrossTheInternetReadable() {
        assertEquals(AtStart.NeedsHttps, atStart("pw", signIn, public))
        assertEquals(AtStart.Lost, atStart(null, signIn, public))
        assertEquals(AtStart.Lost, atStart("pw", null, public))
        assertEquals(AtStart.Lost, atStart("pw", signIn, null))
        assertTrue(atStart("pw", signIn, "https://music.example.com/".toHttpUrl()) is AtStart.SignIn)
        assertTrue(atStart("pw", signIn, "http://192.168.1.20:4533/".toHttpUrl()) is AtStart.SignIn)
        assertTrue(atStart("pw", ConnectionSettings(headers = listOf(ServerHeader("X-Token", "t"))), public) is AtStart.SignIn)
    }

    private fun session(url: HttpUrl, settings: ConnectionSettings) =
        Session(SubsonicClient(url, Credentials("winters", "pw"), OkHttpClient()), null, null, false, false, emptySet(), settings)

    // Red when inUseReach reads another address than the main one, or no
    // headers.
    @Test
    fun theServerInUseIsNotAskedWhereThePasswordWouldCrossTheInternetReadable() {
        assertEquals(Reach.NeedsHttps, inUseReach(session(public, signIn)))
        assertNull(inUseReach(session("https://music.example.com/".toHttpUrl(), signIn)))
        assertNull(inUseReach(session(public, ConnectionSettings())))
    }

    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText()

    // Red when restore or check stops going by these rules, or when the
    // shell stops opening the sign-in form asked for.
    @Test
    fun restoreCheckAndTheShellGoByThem() {
        val sessions = source("data/SessionRepository.kt")
        val restore = sessions.substringAfter("private suspend fun restore()").substringBefore("private suspend fun activate(")
        assertTrue(restore.contains("atStart("))
        assertTrue(restore.contains("SignInAsk(saved.id, GatewayWords.NEEDS_HTTPS)"))
        val check = sessions.substringAfter("suspend fun check(id: String)").substringBefore("suspend fun signOut()")
        assertTrue(check.contains("inUseReach(inUse)"))
        val shell = source("ui/nav/MainShell.kt")
        assertTrue(shell.contains("signInAsked(ask)"))
        assertTrue(shell.contains("ServerFormRoute(ServerForm.SignIn, ask.serverId, note = ask.note, asked = true)"))
    }

    // Signing in again at the https address keeps the saved sign-in page
    // values while its fields stay empty, and the plain http address is
    // still refused. Red when the resolve rule drops saved values left
    // empty.
    @Test
    fun signingInAgainAtHttpsKeepsTheSavedSignInPageValues() {
        val draft = GatewayDraft(GatewayKind.Password, saved = GatewayKind.Password)
        val ready = resolveConnectionHeaders(emptyList(), draft, signIn.headers, true, false) as ResolvedHeaders.Ready
        assertEquals("Basic YTpi", ready.headers.single { it.name.equals("Authorization", ignoreCase = true) }.value)
        assertTrue(resolveConnectionHeaders(emptyList(), draft, signIn.headers, false, false) is ResolvedHeaders.Problem)
    }

    private val kept = StoredServer("http://music.example.com/", "alice", id = "alice-plain")

    // A keep from the form opened for a kept server builds on that server,
    // whatever its address is now, so one moved to https stays one server
    // under its id with its saved values as the base. Red when keepBase
    // stops building on the server the form was opened for, or a sign-in
    // form stops naming it.
    @Test
    fun theFormForAKeptServerBuildsOnItAtItsHttpsAddress() {
        val secure = "https://music.example.com/"
        assertEquals(KeepBase.Kept(kept), keepBase("alice-plain", secure, "alice", listOf(kept)))
        assertEquals(KeepBase.Kept(kept), keepBase("alice-plain", secure, "bob", listOf(kept)))
        // A new sign-in at the new address builds on nothing, which is why
        // the form names its server.
        assertEquals(KeepBase.New, keepBase(null, secure, "alice", listOf(kept)))
        assertEquals(KeepBase.Kept(kept), keepBase(null, kept.serverUrl, "alice", listOf(kept)))
        assertEquals("alice-plain", signsInAgainTo(ServerForm.SignIn, "alice-plain"))
        assertNull(signsInAgainTo(ServerForm.Edit, "alice-plain"))
        assertNull(signsInAgainTo(ServerForm.Add, null))
        assertNull(signsInAgainTo(null, "alice-plain"))
    }

    // Red when a gone server or another kept one with the same account is
    // let through.
    @Test
    fun aFormForAKeptServerThatIsGoneOrMeetsAnotherSaysSo() {
        val secure = "https://music.example.com/"
        val other = StoredServer(secure, "alice", id = "alice-secure")
        assertEquals(KeepBase.Gone, keepBase("missing", secure, "alice", listOf(kept)))
        assertEquals(KeepBase.AlreadyKept(other), keepBase("alice-plain", secure, "alice", listOf(kept, other)))
        assertEquals(KeepBase.Kept(other), keepBase(null, secure, "alice", listOf(kept, other)))
    }

    // The sign-in form a server that needs https opens (not signed out of)
    // keeps its saved password and key, as an edit does; a server signed
    // out of has none. Red when the sign-in form always asks for the
    // password again, or a signed-out one falls back on a saved one.
    @Test
    fun aSignInFormForAServerNotSignedOutOfKeepsItsSavedPassword() {
        assertTrue(keepsSavedSecret(ServerForm.SignIn, signedOut = false))
        assertFalse(keepsSavedSecret(ServerForm.SignIn, signedOut = true))
        assertTrue(keepsSavedSecret(ServerForm.Edit, signedOut = false))
        assertFalse(keepsSavedSecret(ServerForm.Edit, signedOut = true))
        assertFalse(keepsSavedSecret(ServerForm.Add, signedOut = false))
    }

    // The wiring the pure rules above do not reach: signInAgain and edit
    // name the server their form was opened for, the switcher signs in
    // again to a named server, and a switch to a server that needs https
    // opens its form. Red when any of them goes back to signing in as a new
    // server, or switchTo answers Failed.
    @Test
    fun signInAgainTheSwitcherAndTheSwitchGoByThem() {
        val sessions = source("data/SessionRepository.kt")
        val again = sessions.substringAfter("suspend fun signInAgain(").substringBefore("suspend fun edit(")
        assertTrue(again.contains("keep(request, formFor = id, use = true)"))
        assertTrue(sessions.contains("SwitchResult.NeedsPassword(server, GatewayWords.NEEDS_HTTPS)"))
        assertTrue(source("playback/ServerSwitch.kt").contains("sessions.signInAgain(keptId, request)"))
        assertTrue(source("ui/signin/SignInViewModel.kt").contains("signIns.signIn(request, signsInAgainTo(form, serverId))"))
    }
}
