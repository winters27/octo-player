package app.winters.octo.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun bareHostAndPortGetsHttp() {
        assertEquals("http://192.168.50.21:5274/", normalizeServerUrl("192.168.50.21:5274").toString())
    }

    @Test
    fun pastedApiPathIsDropped() {
        assertEquals("https://x.com/", normalizeServerUrl("https://x.com/rest").toString())
    }

    @Test
    fun subPathIsKept() {
        assertEquals("https://x.com/music", normalizeServerUrl("https://x.com/music/").toString())
    }

    @Test
    fun webPlayerAddressIsTrimmed() {
        assertEquals("https://x.com/", normalizeServerUrl("https://x.com/app/#/album").toString())
    }

    @Test
    fun nonsenseIsRejected() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("http://"))
    }

    @Test
    fun privateHosts() {
        listOf("192.168.50.21", "10.0.0.2", "172.20.1.1", "nas.local", "localhost").forEach {
            assertTrue(it, isPrivateHost("http://$it/".toHttpUrl()))
        }
        listOf("172.32.0.1", "navidrome.winters.app", "8.8.8.8").forEach {
            assertFalse(it, isPrivateHost("http://$it/".toHttpUrl()))
        }
    }
}
