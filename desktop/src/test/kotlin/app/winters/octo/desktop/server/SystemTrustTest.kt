package app.winters.octo.desktop.server

import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.pin
import app.winters.octo.connection.pinKey
import app.winters.octo.connection.platformTrustManager
import app.winters.octo.connection.trustManagerFor
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

// The computer's own authorities with the runtime's (a private authority
// installed in the system is trusted with no pin), and a host the listener
// trusted one certificate for showing another while in use: asked about
// again, never only a failed request.
class SystemTrustTest {
    @get:Rule val folder = TemporaryFolder()

    private var server: FakeServer? = null

    @After fun stop() {
        server?.close()
    }

    private fun serve(leaf: HeldCertificate): FakeServer =
        FakeServer(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory()).also {
            it.answer("ping")
            server = it
        }

    private val settings by lazy { SettingsStore(File(folder.root, "settings.json")) }

    // The runtime's authorities and, standing in for the system's, the test root.
    private val withSystemRoot: X509TrustManager by lazy {
        trustManagerFor(authorityStore(platformTrustManager().acceptedIssuers.asList() + systemRoot.certificate))
    }

    private fun ServerSecurity.fetch(url: String): Boolean = try {
        install(OkHttpClient.Builder()).callTimeout(20, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url.toHttpUrl().resolve("rest/ping.view")!!).build()).execute().use { it.isSuccessful }
    } catch (_: IOException) {
        false
    }

    @Test
    fun aCertificateFromAnAuthorityTheSystemTrustsIsReachedWithNoPin() = runTest {
        val fake = serve(issuedBySystemRoot)
        val security = ServerSecurity(settings, authorities = { withSystemRoot })
        val accounts = Accounts(settings, SessionOnlySecrets(), security.install(OkHttpClient.Builder()).build(), security)
        assertTrue(accounts.test(fake.address, "winters", "pw") is TestOutcome.Reached)
        assertTrue(settings.current.trustedCertificates.isEmpty())
    }

    // Red when the runtime's trust alone is used: the system's authority is
    // missing and the server is a stranger.
    @Test
    fun theRuntimesTrustAloneDoesNotKnowTheSystemsAuthority() = runTest {
        val fake = serve(issuedBySystemRoot)
        val security = ServerSecurity(settings, authorities = { platformTrustManager() })
        val accounts = Accounts(settings, SessionOnlySecrets(), security.install(OkHttpClient.Builder()).build(), security)
        assertTrue(accounts.test(fake.address, "winters", "pw") is TestOutcome.Untrusted)
    }

    // The server in use renews its self-made certificate: the next request
    // fails, the question is asked again with the new certificate, and
    // trusting it lets the request after through.
    @Test
    fun aChangedCertificateInUseIsAskedAboutAgain() {
        val fake = serve(renewed)
        val security = ServerSecurity(settings, authorities = { platformTrustManager() })
        val host = fake.address.toHttpUrl().host
        security.trust(host, selfMade.certificate.pin())
        security.configure(fake.address.toHttpUrl(), null, emptyMap())
        assertFalse(security.fetch(fake.address))
        val asked = security.certificateChange.value
        assertEquals(CertificateQuestion(pinKey(host), renewed.certificate), asked)
        security.trust(asked!!.host, asked.pin)
        security.answered(asked, trusted = true)
        assertNull(security.certificateChange.value)
        assertTrue(security.fetch(fake.address))
        assertEquals(renewed.certificate.pin(), settings.current.trustedCertificates[pinKey(host)])
    }

    // Said no to, the same certificate is not asked about again; a check of
    // a server (its own security) never asks in the window.
    @Test
    fun aCertificateSaidNoToAndACheckAskNothing() = runTest {
        val fake = serve(renewed)
        val security = ServerSecurity(settings, authorities = { platformTrustManager() })
        val host = fake.address.toHttpUrl().host
        security.trust(host, selfMade.certificate.pin())
        val accounts = Accounts(settings, SessionOnlySecrets(), security.install(OkHttpClient.Builder()).build(), security)
        assertTrue(accounts.test(fake.address, "winters", "pw") is TestOutcome.Untrusted)
        assertNull(security.certificateChange.value)
        security.configure(fake.address.toHttpUrl(), null, emptyMap())
        assertFalse(security.fetch(fake.address))
        security.answered(security.certificateChange.value!!, trusted = false)
        assertFalse(security.fetch(fake.address))
        assertNull(security.certificateChange.value)
    }

    // A pin kept before key pins, the whole certificate's SHA-256, still
    // lets its certificate through.
    @Test
    fun anOldWholeCertificatePinStillWorks() {
        val fake = serve(selfMade)
        settings.update { it.copy(trustedCertificates = mapOf(pinKey(fake.address.toHttpUrl().host) to selfMade.certificate.fingerprint())) }
        val security = ServerSecurity(settings, authorities = { platformTrustManager() })
        security.configure(fake.address.toHttpUrl(), null, emptyMap())
        assertTrue(security.fetch(fake.address))
    }

    @Test
    fun aBundleIsReadPastItsComments() {
        val pem = { cert: HeldCertificate -> cert.certificatePem() }
        val bundle = "# Fedora names each one\n" + pem(systemRoot) + "\n# a broken one\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n" + pem(selfMade)
        assertEquals(listOf(systemRoot.certificate, selfMade.certificate), authoritiesInPem(bundle))
    }

    // This computer's own store is read (the Windows root store, the macOS
    // keychains, or the Linux bundle).
    @Test
    fun thisComputersAuthoritiesAreRead() {
        assertTrue(systemAuthorities(System.getProperty("os.name")).isNotEmpty())
        assertTrue(systemTrustManager().acceptedIssuers.size >= platformTrustManager().acceptedIssuers.size)
    }

    private companion object {
        val systemRoot: HeldCertificate = HeldCertificate.Builder().commonName("Company root").certificateAuthority(0).build()
        val issuedBySystemRoot: HeldCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .signedBy(systemRoot)
            .build()
        val selfMade: HeldCertificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val renewed: HeldCertificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    }
}
