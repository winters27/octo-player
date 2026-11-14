package app.winters.octo.connection

import androidx.test.core.app.ApplicationProvider
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

// The client certificate over real handshakes. Each change of certificate
// starts a fresh TLS context behind the one factory; a connection made for
// another certificate is dropped and its call made once more; a key the
// server cannot take is declined. The test server wants a certificate
// (never needs one) and records the one each request came with; the
// phone's certificate store and its built chain, which the JVM has not
// got, are given here.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClientIdentityTest {
    private val server = MockWebServer()

    // The context behind the factory as each secure connection was made.
    private val made: MutableList<SSLSocketFactory> = Collections.synchronizedList(mutableListOf())

    @After
    fun stop() = server.close()

    // An https test server at 127.0.0.1 over HTTP/1.1 with `answers` answers
    // queued, asking for a client certificate unless told not to.
    private fun start(answers: Int, asks: Boolean = true): HttpUrl {
        val certificates = HandshakeCertificates.Builder().heldCertificate(serverCertificate).addTrustedCertificate(root.certificate).build()
        server.useHttps(certificates.sslSocketFactory())
        if (asks) server.requestClientAuth()
        server.protocols = listOf(Protocol.HTTP_1_1)
        repeat(answers) { server.enqueue(MockResponse.Builder().body("ok").build()) }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url("/").newBuilder().host("127.0.0.1").build()
    }

    // The server's certificate pinned for its host (the phone does not trust
    // the test root), and the client certificate by its alias.
    private fun settings(alias: String?) =
        ConnectionSettings(pins = mapOf("127.0.0.1" to serverCertificate.certificate.fingerprint()), clientCertAlias = alias)

    private fun security(keys: Map<String, PrivateKey> = keysByAlias) = ConnectionSecurity(
        ApplicationProvider.getApplicationContext(),
        testDevices(),
        { null },
        { keys[it] },
        { chainsByAlias[it] },
    )

    // A client as the app installs one. A secure connection is made just
    // after it starts, so the factory's context then is the one it uses.
    private fun client(security: ConnectionSecurity): OkHttpClient {
        lateinit var http: OkHttpClient
        http = security.install(OkHttpClient.Builder())
            .protocols(listOf(Protocol.HTTP_1_1))
            .callTimeout(20, TimeUnit.SECONDS)
            .eventListener(
                object : EventListener() {
                    override fun secureConnectStart(call: Call) {
                        made += (http.sslSocketFactory as SwappingFactory).delegate
                    }
                },
            )
            .build()
        return http
    }

    private fun ping(url: HttpUrl) = Request.Builder().url(url.resolve("/rest/ping.view")!!).build()

    private fun OkHttpClient.answer(url: HttpUrl): String = newCall(ping(url)).execute().use { it.body.string() }

    // The next request the server took, waiting at most 10 s.
    private fun next(): RecordedRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) { "no request reached the server" }

    // The client certificate a request came with, or null.
    private fun RecordedRequest.leaf(): X509Certificate? = handshake?.peerCertificates?.firstOrNull() as? X509Certificate

    // The same factory across a change, with a new context behind it. Red
    // when the change keeps the old context (A's session could be resumed
    // and the request go with A), or a client installed later gets a factory
    // of its own.
    @Test
    fun aChangeKeepsTheFactoryWithANewContextBehindIt() {
        val url = start(answers = 2)
        val security = security()
        security.configure(url, settings("a"))
        val http = client(security)
        assertEquals("ok", http.answer(url))
        security.configure(url, settings("b"))
        assertEquals("ok", http.answer(url))
        assertSame(http.sslSocketFactory, client(security).sslSocketFactory)
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
        assertEquals(certificateA.certificate, next().leaf())
        assertEquals(certificateB.certificate, next().leaf())
    }

    // A, then a sign-out, then none at the same host: the request goes with
    // no certificate, on a context of its own. Red when clear() forgets the
    // context installed (none would look like no change, and A's context,
    // with its session, would stay).
    @Test
    fun aThenASignOutThenNoneResumesNothing() {
        val url = start(answers = 2)
        val security = security()
        security.configure(url, settings("a"))
        val http = client(security)
        assertEquals("ok", http.answer(url))
        security.clear()
        security.configure(url, settings(null))
        assertEquals("ok", http.answer(url))
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
        assertEquals(certificateA.certificate, next().leaf())
        assertNull(next().leaf())
    }

    // The stale check, with exactly one retry. A connection made with A is
    // busy while the change to B comes in (so the change cannot close it),
    // then waits in the pool; the next call finds it, drops it, and is made
    // once more on a new connection, with B. Red without the check (the call
    // would go out on A's connection) or without the retry (it would fail).
    @Test
    fun aConnectionMadeForAnotherCertificateIsDroppedAndTheCallMadeOnceMore() {
        val url = start(answers = 2)
        val security = security()
        security.configure(url, settings("a"))
        val http = client(security)
        val busy = http.newCall(ping(url)).execute()
        security.configure(url, settings("b"))
        assertEquals("ok", busy.use { it.body.string() })
        assertEquals(1, http.connectionPool.idleConnectionCount())
        assertEquals("ok", http.answer(url))
        assertEquals(1, security.staleRetries.get())
        val first = next()
        val second = next()
        assertEquals(certificateA.certificate, first.leaf())
        assertEquals(certificateB.certificate, second.leaf())
        assertEquals(first.connectionIndex + 1, second.connectionIndex)
        assertEquals(2, server.requestCount)
    }

    // The key-type decline over a handshake. A key of a kind no server takes
    // is turned down when the server asks; a server that only wants a
    // certificate keeps working with none, on one connection, with no retry.
    // Red when the key's kind is not checked (the handshake would try to
    // sign with it and fail), or a declined socket counts as stale.
    @Test
    fun aKeyOfAKindTheServerCannotTakeIsDeclined() {
        val url = start(answers = 2)
        val security = security(keys = mapOf("a" to NoServerKey))
        security.configure(url, settings("a"))
        val http = client(security)
        repeat(2) { assertEquals("ok", http.answer(url)) }
        assertEquals(0, security.staleRetries.get())
        val first = next()
        val second = next()
        assertNull(first.leaf())
        assertNull(second.leaf())
        assertEquals(first.connectionIndex, second.connectionIndex)
    }

    // A server that never asks keeps its connection while a certificate is
    // set. Red when a connection that was never asked counts as stale.
    @Test
    fun aServerThatNeverAsksKeepsItsConnection() {
        val url = start(answers = 2, asks = false)
        val security = security()
        security.configure(url, settings("a"))
        val http = client(security)
        repeat(2) { assertEquals("ok", http.answer(url)) }
        assertEquals(0, security.staleRetries.get())
        val first = next()
        val second = next()
        assertNull(first.leaf())
        assertEquals(first.connectionIndex, second.connectionIndex)
    }

    // The key manager by itself, for music.example.com under alias "a".
    private fun manager(marks: MutableMap<Socket, SocketMark> = mutableMapOf(), keys: (String) -> PrivateKey?) = ClientCertKeyManager(
        ApplicationProvider.getApplicationContext(),
        choice = { ClientCertChoice("a", setOf("music.example.com")) },
        marks = marks,
        keys = keys,
        chains = { chainsByAlias[it] },
    )

    // A fresh manager whose key source has given nothing yet, an RSA key and
    // a server that takes RSA: the first call offers the alias, and the key
    // is read once. Red when the check looks only at keys read before (the
    // first call would decline).
    @Test
    fun aFreshManagersFirstCallOffersTheAlias() {
        val reads = AtomicInteger()
        val manager = manager {
            reads.incrementAndGet()
            certificateA.keyPair.private
        }
        assertEquals("a", manager.aliasFor("music.example.com", arrayOf("RSA"), null))
        assertEquals(1, reads.get())
        assertEquals("a", manager.aliasFor("Music.Example.com", arrayOf("EC", "RSA"), null))
        assertEquals(1, reads.get())
    }

    // The key-type decline by itself, and the record of each socket asked.
    // Red when a key of another kind, or no key at all, is offered;
    // when a socket asked is not noted with the alias its host had (offered
    // or declined); or when another host's socket is noted with one.
    @Test
    fun aKeyOfAnotherKindOrNoneIsDeclinedAndEverySocketAskedIsNoted() {
        val marks = mutableMapOf<Socket, SocketMark>()
        val manager = manager(marks) { certificateA.keyPair.private }
        val sockets = List(3) { Socket() }
        val (declined, offered, elsewhere) = sockets
        assertNull(manager.aliasFor("music.example.com", arrayOf("EC"), declined))
        assertEquals("a", marks.getValue(declined).alias)
        assertEquals("a", manager.aliasFor("music.example.com", arrayOf("RSA"), offered))
        assertEquals("a", marks.getValue(offered).alias)
        assertNull(manager.aliasFor("other.example.com", arrayOf("RSA"), elsewhere))
        assertTrue(elsewhere in marks)
        assertNull(marks.getValue(elsewhere).alias)
        assertNull(manager(keys = { null }).aliasFor("music.example.com", arrayOf("RSA"), null))
        sockets.forEach { it.close() }
    }

    // The stale check's two halves for a host of the server's: a socket
    // asked under another alias, and a connection that presented another
    // leaf (a resumed session is never asked). Red without the leaf half, or
    // when either half fires on the certificate in use.
    @Test
    fun aConnectionIsStaleOnlyForAnotherAliasOrLeaf() {
        val security = security()
        val host = "music.example.com"
        security.configure("https://$host/".toHttpUrl(), settings("b"))
        assertTrue(security.stale(SocketMark("a"), host, emptyList()))
        assertFalse(security.stale(SocketMark("b"), host, emptyList()))
        assertTrue(security.stale(null, host, listOf(certificateA.certificate)))
        assertFalse(security.stale(null, host, listOf(certificateB.certificate)))
        assertFalse(security.stale(null, host, emptyList()))
        security.clear()
        assertTrue(security.stale(null, host, listOf(certificateB.certificate)))
        assertTrue(security.stale(SocketMark("b"), host, emptyList()))
    }

    private companion object {
        val root: HeldCertificate = HeldCertificate.Builder().commonName("Octo test root").certificateAuthority(0).rsa2048().build()
        val serverCertificate: HeldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("localhost")
            .rsa2048()
            .signedBy(root)
            .build()
        val certificateA: HeldCertificate = HeldCertificate.Builder().commonName("a").rsa2048().signedBy(root).build()
        val certificateB: HeldCertificate = HeldCertificate.Builder().commonName("b").rsa2048().signedBy(root).build()
        val keysByAlias: Map<String, PrivateKey> = mapOf("a" to certificateA.keyPair.private, "b" to certificateB.keyPair.private)
        val chainsByAlias: Map<String, Array<X509Certificate>> =
            mapOf("a" to arrayOf(certificateA.certificate), "b" to arrayOf(certificateB.certificate))
    }

    // A key of a kind no server takes.
    private object NoServerKey : PrivateKey {
        override fun getAlgorithm(): String = "Octo-none"

        override fun getFormat(): String? = null

        override fun getEncoded(): ByteArray? = null
    }
}
