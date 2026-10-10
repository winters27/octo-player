package app.winters.octo.desktop.pages

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Scheme
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.joinWithInvite
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Family links on the sign-in page: a sign-in link fills in the server and
// username for the password; an invite signs up, then signs in with the
// password chosen, like any typed sign-in.
class FamilyJoinTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aSignInLinkFillsInTheServerAndUsername() {
        val form = SignInForm()
        assertFalse(form.takeJoinLink("music.example.com"))
        form.password = "left over"
        assertTrue(form.takeJoinLink(" octo://signin?server=https%3A%2F%2Fmusic.example.com&home=http%3A%2F%2F192.168.1.20%3A4533&username=alex "))
        assertEquals("music.example.com", form.address)
        assertEquals(Scheme.Https, form.scheme)
        assertEquals("alex", form.username)
        assertEquals("http://192.168.1.20:4533", form.home)
        // The password is for the person to type.
        assertEquals("", form.password)
        assertNull(form.invite)
        assertNull(form.handOver)
    }

    @Test
    fun anInviteSignsUpThenSignsInWithTheChosenPassword() = runTest {
        FakeServer().use { server ->
            server.answerBy("join") { """{"username":"alex"}""" }
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoFamily","versions":[1]}]""", type = "octo")
            val form = SignInForm()
            assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(server.address, Charsets.UTF_8)}&invite=tok_1"))
            assertEquals("tok_1", form.invite!!.token)
            form.inviteName = "Alex"
            form.invitePassword = "long enough"
            assertEquals("The two passwords are not the same", form.inviteProblem)
            form.inviteAgain = "long enough"
            assertNull(form.inviteProblem)
            assertTrue(form.inviteReady)

            val http = OkHttpClient()
            val joined = joinWithInvite(form.url!!, form.invite!!.token, form.inviteName, form.invitePassword, http) as JoinOutcome.SignedUp
            val request = form.signUpRequest(joined.username, joined.password)
            assertEquals("alex", request.username)
            assertEquals(AuthMode.Token, request.mode)
            val accounts = Accounts(SettingsStore(File(folder.root, "settings.json")), SessionOnlySecrets(), http)
            val done = accounts.signIn(request) as SignInOutcome.Done
            assertTrue(done.connection.family)
            // Signed in with a token made from the chosen password, never the password itself.
            val signed = server.calls.last().url
            assertEquals("alex", signed.queryParameter("u"))
            assertEquals(md5("long enough" + signed.queryParameter("s")), signed.queryParameter("t"))
            assertNull(signed.queryParameter("p"))
        }
    }

    @Test
    fun anInvitesHomeAddressSignsUpAtHomeAndIsSavedAsTheHomeAddress() = runTest {
        FakeServer().use { home ->
            home.answerBy("join") { """{"username":"alex"}""" }
            home.answer("ping", type = "octo")
            home.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoFamily","versions":[1]}]""", type = "octo")
            // The outside address, with its path, does not loop back from here.
            val outside = "http://127.0.0.1:1/octo"
            val form = SignInForm()
            assertTrue(form.takeJoinLink("$outside/family/join#invite=tok_1&home=${java.net.URLEncoder.encode(home.address, Charsets.UTF_8)}"))
            assertEquals(home.address.removeSuffix("/"), form.home)
            val http = OkHttpClient()
            val joined = joinWithInvite(form.url!!, "tok_1", "Alex", "long enough", http, home = form.homeUrl) as JoinOutcome.SignedUp
            assertEquals(form.homeUrl, joined.at)

            val request = form.signUpRequest(joined.username, joined.password)
            assertEquals(outside, request.address)
            val accounts = Accounts(SettingsStore(File(folder.root, "settings.json")), SessionOnlySecrets(), http)
            assertTrue(accounts.signIn(request) is SignInOutcome.Done)
            val saved = accounts.servers.single()
            // The outside address is the main one, path and all; home is used at home.
            assertEquals(outside, saved.address.removeSuffix("/"))
            assertEquals(home.address.removeSuffix("/"), saved.home?.removeSuffix("/"))
        }
    }

    @Test
    fun aHandOverLinkWaitsToBeReceived() {
        val form = SignInForm()
        assertTrue(form.takeJoinLink("https://music.example.com/family/signin#t=tok_1&k=a-key_of-43-characters-made-for-this-test-0&s=https%3A%2F%2Fmusic.example.com"))
        assertEquals("tok_1", form.handOver!!.token)
        assertNull(form.invite)
        form.leaveLink()
        assertNull(form.handOver)
    }

    @Test
    fun aLinkWithoutAHomeAddressLeavesItAlone() {
        val form = SignInForm()
        form.home = "http://music.lan"
        form.takeJoinLink("https://example.com/family/join#invite=tok_1")
        assertEquals("http://music.lan", form.home)
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
