package app.winters.octo.connection

import app.winters.octo.subsonic.HeaderScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The phone's client takes an http address's https twin for the server
// too, so a server that moves its visitors up to https on the same host
// keeps its headers and its sign-in page's cookie, and another host never
// gets them.
@RunWith(RobolectricTestRunner::class)
class HttpsTwinTest {
    // The scope the phone's client puts on a request as it starts, read
    // without sending anything.
    private fun scopeFor(main: HttpUrl, settings: ConnectionSettings): HeaderScope {
        val security = testSecurity()
        security.configure(main, settings)
        var seen: HeaderScope? = null
        val http = security.install(OkHttpClient.Builder()).addInterceptor { chain ->
            seen = chain.request().tag(HeaderScope::class)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("No Content").body("".toResponseBody()).build()
        }.build()
        http.newCall(Request.Builder().url(main.resolve("/rest/ping.view")!!).build()).execute().close()
        return seen!!
    }

    // Red when configure builds the origins without serverOrigins, or
    // gatewayOrigins leaves out the twin.
    @Test
    fun anHttpAddressAlsoCoversTheSameHostOverHttps() {
        val main = "http://192.168.1.20:4533/".toHttpUrl()
        val headers = listOf(ServerHeader("Authorization", "Basic YTpi"), ServerHeader("X-Token", "t"))
        val scope = scopeFor(main, ConnectionSettings(home = "http://10.0.0.5:4533/".toHttpUrl(), headers = headers))
        val all = mapOf("Authorization" to "Basic YTpi", "X-Token" to "t")
        val twin = "https://192.168.1.20/rest/stream.view".toHttpUrl()
        assertTrue(scope.covers(twin))
        assertEquals(all, scope.headersFor(twin))
        assertEquals(all, scope.headersFor("https://10.0.0.5/rest/stream.view".toHttpUrl()))
        assertEquals(mapOf("X-Token" to "t"), scope.headersFor("http://10.0.0.5:4533/rest/stream.view".toHttpUrl()))
        assertFalse(scope.covers("https://192.168.1.21/rest/stream.view".toHttpUrl()))
        assertFalse(scope.covers("https://192.168.1.20:8443/rest/stream.view".toHttpUrl()))
    }
}
