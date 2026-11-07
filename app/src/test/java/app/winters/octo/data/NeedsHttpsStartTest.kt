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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
        assertTrue(shell.contains("ServerFormRoute(ServerForm.SignIn, ask.serverId, ask.note)"))
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

    // The form opened for a kept server signs in to that server again (one
    // server, its id, its saved values as the base), and a switch to a
    // server that needs https opens that form. Red when the form, the
    // switcher or signInAgain go back to signing in as a new server, or
    // switchTo answers Failed.
    @Test
    fun theFormForAKeptServerSignsInToItAgain() {
        val sessions = source("data/SessionRepository.kt")
        val again = sessions.substringAfter("suspend fun signInAgain(").substringBefore("suspend fun edit(")
        assertTrue(again.contains("keep(request, base, use = true)"))
        assertTrue(sessions.contains("SwitchResult.NeedsPassword(server, GatewayWords.NEEDS_HTTPS)"))
        assertTrue(source("playback/ServerSwitch.kt").contains("sessions.signInAgain(keptId, request)"))
        assertTrue(source("ui/signin/SignInViewModel.kt").contains("switcher.signIn(request, serverId.takeIf { form == ServerForm.SignIn })"))
    }
}
