package app.winters.octo.connection

import androidx.test.core.app.ApplicationProvider
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

// The server's connections over real handshakes: a server whose certificate
// comes from an authority the user installed is trusted with no pin (a
// private step-ca renewing a 24-hour certificate every day), and a host the
// user trusted one certificate for, showing another, asks again rather than
// failing every request. The phone's AndroidCAStore, which the JVM has not
// got, is stood in for by a key store holding the test authority.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerAuthorityTest {
    private val server = MockWebServer()

    @After
    fun stop() = server.close()

    private fun start(leaf: HeldCertificate, answers: Int = 1): HttpUrl {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory())
        server.protocols = listOf(Protocol.HTTP_1_1)
        repeat(answers) { server.enqueue(MockResponse.Builder().body("ok").build()) }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url("/").newBuilder().host("127.0.0.1").build()
    }

    // The system's authorities and the user's: here, only the user's.
    private fun withUserAuthority(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("user:octo-test-root", userRoot.certificate)
        }
        return trustManagerFor(store)
    }

    private fun security(trust: X509TrustManager) = ConnectionSecurity(
        ApplicationProvider.getApplicationContext(),
        testDevices(),
        { null },
        { null },
        { null },
        trust,
    )

    private fun client(security: ConnectionSecurity): OkHttpClient =
        security.install(OkHttpClient.Builder()).protocols(listOf(Protocol.HTTP_1_1)).callTimeout(20, TimeUnit.SECONDS).build()

    private fun OkHttpClient.answer(url: HttpUrl): String =
        newCall(Request.Builder().url(url.resolve("/rest/ping.view")!!).build()).execute().use { it.body.string() }

    private fun OkHttpClient.fails(url: HttpUrl): Boolean = try {
        answer(url)
        false
    } catch (_: IOException) {
        true
    }

    // Red when the server's connections stay on the system's authorities.
    @Test
    fun aCertificateFromAUserInstalledAuthorityIsTrustedWithNoPin() {
        val url = start(issuedByUserRoot)
        val security = security(withUserAuthority())
        security.configure(url, ConnectionSettings())
        assertEquals("ok", client(security).answer(url))
        assertNull(security.certificateChange.value)
    }

    // The same server without the user's authority is a stranger: turned
    // down, kept to ask about at sign-in, and not a changed certificate.
    @Test
    fun withoutTheAuthorityTheCertificateIsAStranger() {
        val url = start(issuedByUserRoot)
        val security = security(platformTrustManager())
        security.configure(url, ConnectionSettings())
        assertTrue(client(security).fails(url))
        assertNotNull(security.takeRejected("127.0.0.1"))
        assertNull(security.certificateChange.value)
    }

    // A pinned host showing a new certificate: the request fails, and the
    // question is asked again with the new certificate's fingerprint and
    // pin. Trusting it lets the next request through.
    @Test
    fun aChangedCertificateOnAPinnedHostAsksAgain() {
        val url = start(renewed, answers = 1)
        val security = security(platformTrustManager())
        val old = mapOf("127.0.0.1" to selfMade.certificate.pin())
        security.configure(url, ConnectionSettings(pins = old))
        val http = client(security)
        assertTrue(http.fails(url))
        val asked = security.certificateChange.value
        assertEquals(CertificateChange("127.0.0.1", renewed.certificate.fingerprint(), renewed.certificate.pin()), asked)
        security.configure(url, ConnectionSettings(pins = mapOf("127.0.0.1" to asked!!.pin)))
        security.answered(asked, trusted = true)
        assertNull(security.certificateChange.value)
        assertEquals("ok", http.answer(url))
    }

    // Said no to once, the same certificate is not asked about again.
    @Test
    fun aCertificateSaidNoToIsNotAskedAboutAgain() {
        val url = start(renewed, answers = 0)
        val security = security(platformTrustManager())
        security.configure(url, ConnectionSettings(pins = mapOf("127.0.0.1" to selfMade.certificate.pin())))
        val http = client(security)
        assertTrue(http.fails(url))
        security.answered(security.certificateChange.value!!, trusted = false)
        assertTrue(http.fails(url))
        assertNull(security.certificateChange.value)
    }

    // A pin kept before key pins (the whole certificate's SHA-256) still
    // loads and lets its certificate through.
    @Test
    fun anOldWholeCertificatePinStillWorks() {
        val url = start(selfMade)
        val security = security(platformTrustManager())
        security.configure(url, ConnectionSettings(pins = mapOf("127.0.0.1" to selfMade.certificate.fingerprint())))
        assertEquals("ok", client(security).answer(url))
        val stored = decodeConnection("""{"pins":{"127.0.0.1":"${formatFingerprint(selfMade.certificate.fingerprint())}"}}""")
        assertEquals(formatFingerprint(selfMade.certificate.fingerprint()), stored?.pins?.get("127.0.0.1"))
    }

    // Only the server's own connections take the user's authorities; the
    // app's other clients keep the system's (phoneTls).
    @Test
    fun onlyTheServerConnectionsTakeTheUsersAuthorities() {
        val root = File("src/main/java/app/winters/octo")
        val users = root.walk().filter { it.extension == "kt" && "serverPlatformTrust()" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("connection/ConnectionSecurity.kt", "connection/PhoneTrust.kt"), users)
    }

    private companion object {
        val userRoot: HeldCertificate = HeldCertificate.Builder().commonName("Home step-ca root").certificateAuthority(0).ecdsa256().build()
        val issuedByUserRoot: HeldCertificate = HeldCertificate.Builder()
            .commonName("127.0.0.1")
            .addSubjectAlternativeName("127.0.0.1")
            .duration(1, TimeUnit.DAYS)
            .signedBy(userRoot)
            .build()
        val selfMade: HeldCertificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
        val renewed: HeldCertificate = HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
    }
}
