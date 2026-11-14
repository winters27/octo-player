package app.winters.octo.connection

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Another client certificate never rides on a session or a connection made
// for the last one: every client the security installs keeps one factory,
// whose context is swapped on a change, and its idle connections are closed.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionFlushTest {
    private val server = MockWebServer()
    private val main = "https://music.example.com/".toHttpUrl()

    @After
    fun stop() = server.close()

    // Red when install hands out a factory made for one certificate, or a
    // change leaves the old context behind it, or no change swaps it.
    @Test
    fun aChangeSwapsTheContextBehindTheSameFactory() {
        val security = testSecurity()
        val http = security.install(OkHttpClient.Builder()).build()
        val factory = http.sslSocketFactory as SwappingFactory
        val first = factory.delegate
        security.configure(main, ConnectionSettings(clientCertAlias = "phone-cert"))
        assertSame(factory, http.sslSocketFactory)
        assertNotSame(first, factory.delegate)
        val second = factory.delegate
        security.configure(main, ConnectionSettings(clientCertAlias = "phone-cert"))
        assertSame(second, factory.delegate)
    }

    // A certificate, then a sign-out, then none at the same host. Red when
    // clear() forgets what is installed (the next configuration would see no
    // change and keep the old certificate's sessions).
    @Test
    fun signingOutThenSigningInWithoutACertificateStartsAFreshContext() {
        val security = testSecurity()
        val http = security.install(OkHttpClient.Builder()).build()
        val factory = http.sslSocketFactory as SwappingFactory
        security.configure(main, ConnectionSettings(clientCertAlias = "phone-cert"))
        val withCertificate = factory.delegate
        security.clear()
        assertSame(withCertificate, factory.delegate)
        security.configure(main, ConnectionSettings())
        assertNotSame(withCertificate, factory.delegate)
    }

    // Red when a change leaves idle connections pooled, or an unchanged
    // configuration closes them.
    @Test
    fun aChangeClosesTheIdleConnectionsAndNoChangeKeepsThem() {
        val home = server.startHome()
        repeat(2) { server.enqueue(MockResponse.Builder().body("ok").build()) }
        val security = testSecurity()
        security.configure(home, ConnectionSettings())
        val http = security.install(OkHttpClient.Builder()).build()
        http.newCall(Request.Builder().url(home.resolve("/rest/ping.view")!!).build()).execute().use { it.body.string() }
        assertEquals(1, http.connectionPool.connectionCount())
        security.configure(home, ConnectionSettings())
        assertEquals(1, http.connectionPool.connectionCount())
        security.configure(home, ConnectionSettings(clientCertAlias = "phone-cert"))
        assertEquals(0, http.connectionPool.connectionCount())
    }
}
