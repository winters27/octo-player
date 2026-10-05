package app.winters.octo.admin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OctoAdminSignInTest {
    private val home = setOf("http://192.168.50.21:5274/".toHttpUrl())

    @Test
    fun signsOnlyForAnAddressTheAppTrusts() {
        assertTrue(mayCarrySignIn("http://192.168.50.21:5274/api/admin/status".toHttpUrl(), home))
        assertTrue(mayCarrySignIn("http://192.168.50.21:5274/api/admin/lastfm/radio?user=me".toHttpUrl(), home))
    }

    @Test
    fun neverSignsForAnotherHostOrPort() {
        assertFalse(mayCarrySignIn("http://10.0.0.5:5274/api/admin/status".toHttpUrl(), home))
        assertFalse(mayCarrySignIn("http://192.168.50.21:5275/api/admin/status".toHttpUrl(), home))
        assertFalse(mayCarrySignIn("https://192.168.50.21:5274/api/admin/status".toHttpUrl(), home))
        assertFalse(mayCarrySignIn("http://192.168.50.21:5274/api/admin/status".toHttpUrl(), emptySet()))
    }
}
