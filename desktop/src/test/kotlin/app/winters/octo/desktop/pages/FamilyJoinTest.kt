package app.winters.octo.desktop.pages

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.Scheme
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.joinFamily
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

// Joining a family from the sign-in page: a pasted link fills the form, the
// code pairs, and the secret it answers signs in like a password.
class FamilyJoinTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aPastedLinkFillsEverythingIn() {
        val form = SignInForm()
        assertFalse(form.takeJoinLink("music.example.com"))
        assertTrue(form.takeJoinLink(" octo://join?server=https%3A%2F%2Fmusic.example.com&username=alex&code=482913 "))
        assertTrue(form.joining)
        assertEquals("music.example.com", form.address)
        assertEquals(Scheme.Https, form.scheme)
        assertEquals("alex", form.username)
        assertEquals("482913", form.code)
        assertNull(form.joinProblem)
        assertTrue(form.joinReady)
        form.code = "4829"
        assertEquals("The family code is 6 digits", form.joinProblem)
    }

    @Test
    fun theCodePairsAndTheSecretSignsIn() = runTest {
        FakeServer().use { server ->
            server.answer("octoFamilyPair", """"familyPair":{"username":"alex","secret":"abcdefghijklmnopqrstuvwxyz012345","deviceId":"d_11","server":"https://music.example.com"}""", type = "octo")
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoFamily","versions":[1]}]""", type = "octo")
            val form = SignInForm()
            form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(server.address, Charsets.UTF_8)}&username=alex&code=482913")
            val http = OkHttpClient()
            val joined = joinFamily(form.url!!, form.username, form.code, "Studio PC", FamilyPlatform.Windows, http) as JoinOutcome.Paired
            val pairing = server.calls.single { it.url.pathSegments.last() == "octoFamilyPair" }.url
            assertEquals("Windows", pairing.queryParameter("platform"))
            assertEquals("Studio PC", pairing.queryParameter("deviceName"))

            val request = form.joinRequest(joined.pair)
            assertEquals("alex", request.username)
            assertEquals(AuthMode.Token, request.mode)
            assertTrue(request.rememberPassword)
            val accounts = Accounts(SettingsStore(File(folder.root, "settings.json")), SessionOnlySecrets(), http)
            val done = accounts.signIn(request) as SignInOutcome.Done
            assertTrue(done.connection.family)
            // Signed with a token made from the secret, never the secret itself.
            val signed = server.calls.last().url
            assertEquals("alex", signed.queryParameter("u"))
            assertNull(signed.queryParameter("p"))
            assertFalse(signed.toString().contains("abcdefghijklmnopqrstuvwxyz012345"))
        }
    }

    @Test
    fun aLinksHomeAddressPairsAtHomeAndIsSavedAsTheHomeAddress() = runTest {
        FakeServer().use { home ->
            home.answer("octoFamilyPair", """"familyPair":{"username":"alex","secret":"abcdefghijklmnopqrstuvwxyz012345","deviceId":"d_11"}""", type = "octo")
            home.answer("ping", type = "octo")
            home.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoFamily","versions":[1]}]""", type = "octo")
            // The outside address, with its path, does not loop back from here.
            val outside = "http://127.0.0.1:1/octo"
            val form = SignInForm()
            assertTrue(form.takeJoinLink("$outside/family/join#u=alex&c=482913&home=${java.net.URLEncoder.encode(home.address, Charsets.UTF_8)}"))
            assertEquals(home.address.removeSuffix("/"), form.home)
            val http = OkHttpClient()
            val joined = joinFamily(form.url!!, form.username, form.code, "Studio PC", FamilyPlatform.Windows, http, home = form.homeUrl) as JoinOutcome.Paired
            assertEquals(form.homeUrl, joined.at)

            val request = form.joinRequest(joined.pair)
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
    fun aLinkWithoutAHomeAddressLeavesItAlone() {
        val form = SignInForm()
        form.home = "http://music.lan"
        form.takeJoinLink("https://example.com/family/join#u=alex&c=482913")
        assertEquals("http://music.lan", form.home)
    }
}
