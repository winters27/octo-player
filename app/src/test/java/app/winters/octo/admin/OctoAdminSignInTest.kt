package app.winters.octo.admin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
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

    @Test
    fun aDownloadsCoverComesFromOctoSigned() {
        val base = "http://192.168.50.21:5274/".toHttpUrl()
        val sign = { url: okhttp3.HttpUrl -> url.newBuilder().addQueryParameter("u", "me").build() }
        val record = DownloadRecord(
            title = "Glass",
            coverArtUrl = "https://cdn-images.dzcdn.net/images/cover/x/1000x1000.jpg",
            cover = "/api/admin/downloads/cover?at=2026-10-30T01%3A30%3A00.9766532Z",
        )
        assertEquals(
            "http://192.168.50.21:5274/api/admin/downloads/cover?at=2026-10-30T01%3A30%3A00.9766532Z&u=me",
            withServedCover(record, base, sign).coverArtUrl,
        )
    }

    @Test
    fun anOlderServerOrAnotherHostKeepsTheSavedCover() {
        val base = "http://192.168.50.21:5274/".toHttpUrl()
        val sign = { url: okhttp3.HttpUrl -> url.newBuilder().addQueryParameter("u", "me").build() }
        val saved = "https://cdn-images.dzcdn.net/images/cover/x/1000x1000.jpg"
        for (cover in listOf(null, "https://elsewhere.example/c.jpg", "//elsewhere.example/c.jpg")) {
            val record = DownloadRecord(title = "Glass", coverArtUrl = saved, cover = cover)
            assertEquals(cover.toString(), saved, withServedCover(record, base, sign).coverArtUrl)
        }
    }

    @Test
    fun theFullPagesOpenAtTheCardNamed() {
        val base = "http://192.168.50.21:5274/".toHttpUrl()
        assertEquals("http://192.168.50.21:5274/admin/#spotiflac", adminPageUrl(base, "spotiflac").toString())
        assertEquals("http://192.168.50.21:5274/admin/", adminPageUrl(base).toString())
    }
}
