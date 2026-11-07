package app.winters.octo.desktop.server

import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.fingerprint
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.subsonic.isPrivateHost
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

// A server at a plain http address that moves its visitors up to https on
// the same host is still the server: its headers (a sign-in page's among
// them) and the sign-in page's cookie follow the move, and none of them
// follow a move to another host. The https end is a test server with a
// certificate made for these tests, trusted by pin as the listener would,
// reached on port 443 through sockets that land on its own port, since a
// test cannot listen on 443 itself. And a kept server signed in to again
// at its https address stays the one server it was.
class HttpsUpgradeTest {
    @get:Rule val folder = TemporaryFolder()

    private val keys: KeyStore = KeyStore.getInstance("PKCS12").apply {
        HttpsUpgradeTest::class.java.getResourceAsStream("/certs/music-test.p12")!!.use { load(it, PASSWORD) }
    }
    private val cert = keys.getCertificate("music") as X509Certificate
    private val secure = FakeServer(
        SSLContext.getInstance("TLS").apply {
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }
            init(factory.keyManagers, null, null)
        }.socketFactory,
    )
    private val plain = FakeServer()
    private val headers = mapOf("Authorization" to "Basic YTpi", "X-Token" to "t")

    @After
    fun stop() {
        plain.close()
        secure.close()
    }

    // Sockets that reach port 443 at the https test server instead.
    private class To443(private val landing: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val at = endpoint as InetSocketAddress
                super.connect(if (at.port == 443) InetSocketAddress(at.address, landing) else at, timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)

        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket(address, port)
    }

    private fun ping(http: OkHttpClient, main: HttpUrl): Int =
        http.newCall(Request.Builder().url(main.resolve("/rest/ping")!!).build()).execute().use { it.code }

    // Red when ServerSecurity builds its origins without serverOrigins
    // (nothing reaches the https end), when gatewayOrigins leaves out the
    // twin (no Authorization there), or when the twin is any https host.
    @Test
    fun theServersHeadersAndCookieFollowAMoveUpToHttpsOnTheSameHostOnly() {
        val main = plain.address.toHttpUrl()
        assertTrue("the test needs an address on the home network", isPrivateHost(main))
        val elsewhere = if (main.host == "localhost") "127.0.0.1" else "localhost"
        val security = ServerSecurity(SettingsStore(File(folder.root, "settings.json")))
        security.trust(main.host, cert.fingerprint())
        security.trust(elsewhere, cert.fingerprint())
        security.configure(main, null, headers)
        val http = security.install(OkHttpClient.Builder().socketFactory(To443(secure.address.toHttpUrl().port))).build()
        plain.respond("ping") { SignInPages.redirect("https://${main.host}/rest/ping") }
        secure.respond("ping") { secure.okResponse(setCookie = "authentik_proxy_s=ok; Path=/") }

        assertEquals(200, ping(http, main))
        val first = secure.calls.single()
        assertEquals("Basic YTpi", first.headers["Authorization"])
        assertEquals("t", first.headers["X-Token"])
        assertNull(first.headers["Cookie"])
        assertEquals(200, ping(http, main))
        assertEquals("the sign-in page's cookie follows", "authentik_proxy_s=ok", secure.calls.last().headers["Cookie"])

        plain.respond("ping") { SignInPages.redirect("https://$elsewhere/rest/ping") }
        secure.calls.clear()
        assertEquals(200, ping(http, main))
        val moved = secure.calls.single()
        assertNull(moved.headers["Authorization"])
        assertNull(moved.headers["X-Token"])
        assertNull(moved.headers["Cookie"])
    }

    // A kept server signed out of, signed in to again from its own form at
    // its https address, stays one server under its id, with the address
    // moved and its saved sign-in page values sent there. Red when
    // signInAgain keeps the request as a new server, or the form's saved
    // values are not read from the kept server.
    @Test
    fun signingInAgainAtTheHttpsAddressKeepsTheSameServer() = runTest {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val security = ServerSecurity(settings)
        val accounts = Accounts(settings, FakeSecrets(), security.install(OkHttpClient.Builder()).build(), security)
        plain.answer("ping", type = "octo")
        secure.answer("ping", type = "octo")
        val first = accounts.signIn(SignInRequest(plain.address, "winters", "pw", gateway = GatewayDraft(GatewayKind.Password, "alice", "right"))) as SignInOutcome.Done
        val id = first.connection.server.id
        accounts.signOut(id)
        security.trust(secure.address.toHttpUrl().host, cert.fingerprint())
        val saved = GatewayDraft(GatewayKind.Password, saved = GatewayKind.Password)
        val again = accounts.signInAgain(id, SignInRequest(secure.address, "winters", "pw", gateway = saved)) as SignInOutcome.Done
        assertEquals(id, again.connection.server.id)
        assertEquals(listOf(id), accounts.servers.map { it.id })
        assertEquals(secure.address, accounts.servers.single().address)
        assertEquals(false, accounts.servers.single().signedOut)
        assertEquals(basicCredentials("alice", "right"), secure.calls.last().headers["Authorization"])
    }

    private companion object {
        val PASSWORD = "octo-test".toCharArray()
    }
}
