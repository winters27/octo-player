package app.winters.octo.desktop.server

import app.winters.octo.desktop.pages.SignInForm
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.Scheme
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.automaticScheme
import app.winters.octo.subsonic.serverUrl
import app.winters.octo.subsonic.shownAddress
import app.winters.octo.subsonic.splitScheme
import java.net.ConnectException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun aTypedSchemeIsSplitOff() {
        assertEquals(Scheme.Http to "192.168.1.20:4533", splitScheme("http://192.168.1.20:4533"))
        assertEquals(Scheme.Https to "music.example.com/navidrome", splitScheme("HTTPS://music.example.com/navidrome"))
        assertEquals(Scheme.Https to "music.example.com", splitScheme("  https://music.example.com"))
        assertEquals(null to "music.example.com", splitScheme("music.example.com"))
        // Only the start counts, and only a whole scheme.
        assertEquals(null to "music.example.com/http://", splitScheme("music.example.com/http://"))
        assertEquals(null to "http:/music", splitScheme("http:/music"))
        assertEquals(null to "", splitScheme(""))
    }

    @Test
    fun theHomeNetworkGetsHttpAndEverythingElseHttps() {
        assertEquals(Scheme.Http, automaticScheme("192.168.1.20:4533"))
        assertEquals(Scheme.Http, automaticScheme("10.0.0.5"))
        assertEquals(Scheme.Http, automaticScheme("localhost:4533"))
        assertEquals(Scheme.Http, automaticScheme("nas.local"))
        assertEquals(Scheme.Http, automaticScheme("music.lan/navidrome"))
        assertEquals(Scheme.Https, automaticScheme("music.example.com"))
        assertEquals(Scheme.Https, automaticScheme("8.8.8.8"))
        // Half typed, or not an address yet: the safe choice.
        assertEquals(Scheme.Https, automaticScheme(""))
        assertEquals(Scheme.Https, automaticScheme("192.168"))
    }

    @Test
    fun theFinalAddressJoinsTheSchemeAndTheRest() {
        assertEquals("https://music.example.com/", serverUrl(Scheme.Https, "music.example.com").toString())
        assertEquals("http://192.168.1.20:4533/", serverUrl(Scheme.Http, " 192.168.1.20:4533/ ").toString())
        assertEquals("https://music.example.com/navidrome", serverUrl(Scheme.Https, "music.example.com/navidrome/rest").toString())
        assertNull(serverUrl(Scheme.Https, ""))
        assertNull(serverUrl(Scheme.Https, "   "))
        // A second scheme in the rest is not an address.
        assertNull(serverUrl(Scheme.Https, "ftp://music.example.com"))
        assertEquals("https://music.example.com", shownAddress(serverUrl(Scheme.Https, "music.example.com")!!))
    }

    @Test
    fun theFormFollowsTheAddressUntilASchemeIsPicked() {
        val form = SignInForm()
        assertEquals(Scheme.Https, form.scheme)
        form.typeAddress("192.168.1.20:4533")
        assertEquals(Scheme.Http, form.scheme)
        assertEquals("http://192.168.1.20:4533/", form.url.toString())
        form.typeAddress("music.example.com")
        assertEquals(Scheme.Https, form.scheme)
        // Picked by hand, it stays whatever is typed.
        form.toggleScheme()
        assertEquals(Scheme.Http, form.scheme)
        form.typeAddress("music.example.com:4533")
        assertEquals(Scheme.Http, form.scheme)
        assertTrue(form.insecure)
    }

    @Test
    fun aPastedFullAddressSetsTheSchemeAndLeavesTheRest() {
        val form = SignInForm()
        form.typeAddress("https://192.168.1.20:4533")
        assertEquals(Scheme.Https, form.scheme)
        assertEquals("192.168.1.20:4533", form.address)
        // Typed by hand counts as picked.
        form.typeAddress("192.168.1.21")
        assertEquals(Scheme.Https, form.scheme)
        assertFalse(form.insecure)
    }

    @Test
    fun aRememberedServerFillsBothPartsAndStartsAtThePassword() {
        val form = SignInForm(SavedServer("http://music.example.com:4533/", "winters", rememberSignIn = false))
        assertEquals(Scheme.Http, form.scheme)
        assertEquals("music.example.com:4533", form.address)
        assertEquals("winters", form.username)
        assertFalse(form.rememberPassword)
        assertTrue(form.startsAtPassword)
        assertFalse(SignInForm().startsAtPassword)
    }

    @Test
    fun anHttpsHomeAddressThatCannotConnectGetsAHint() {
        val down = SubsonicException.Unreachable(ConnectException("refused"))
        assertTrue(homeHttpsHint("https://192.168.1.20:4533/".toHttpUrl(), down).contains("Switch to http://"))
        assertEquals("", homeHttpsHint("http://192.168.1.20:4533/".toHttpUrl(), down))
        assertEquals("", homeHttpsHint("https://music.example.com/".toHttpUrl(), down))
        assertEquals("", homeHttpsHint("https://192.168.1.20/".toHttpUrl(), SubsonicException.WrongCredentials("no")))
    }
}
