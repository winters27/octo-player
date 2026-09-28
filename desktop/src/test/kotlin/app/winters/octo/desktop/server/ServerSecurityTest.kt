package app.winters.octo.desktop.server

import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.connection.pinKey
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ServerSecurityTest {
    @get:Rule val folder = TemporaryFolder()

    // A server with a self-made certificate for "music.test", made for these
    // tests only, which no system trusts.
    private val keys: KeyStore = KeyStore.getInstance("PKCS12").apply {
        ServerSecurityTest::class.java.getResourceAsStream("/certs/music-test.p12")!!.use { load(it, PASSWORD) }
    }
    private val cert = keys.getCertificate("music") as X509Certificate
    private val server = FakeServer(
        SSLContext.getInstance("TLS").apply {
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }
            init(factory.keyManagers, null, null)
        }.socketFactory,
    )
    private val settingsFile get() = File(folder.root, "settings.json")
    private val settings by lazy { SettingsStore(settingsFile) }
    private val security by lazy { ServerSecurity(settings) }
    private val accounts by lazy { Accounts(settings, SessionOnlySecrets(), security.install(OkHttpClient.Builder()).build(), security) }
    private val host get() = server.address.toHttpUrl().host

    @After fun stop() = server.close()

    // The question a test of the server asks, or a failure saying what it did instead.
    private suspend fun asked(): CertificateQuestion = when (val outcome = accounts.test(server.address, "winters", "pw")) {
        is TestOutcome.Untrusted -> outcome.question
        is TestOutcome.Failed -> throw AssertionError("failed: ${outcome.message}")
        is TestOutcome.Reached -> throw AssertionError("reached ${server.address}")
    }

    @Test
    fun anUntrustedCertificateIsAskedAboutWithItsFingerprint() = runTest {
        server.answer("ping")
        val asked = asked()
        assertEquals(host, asked.host)
        assertEquals(formatFingerprint(cert.fingerprint()), formatFingerprint(asked.fingerprint))
        assertTrue("nothing reached the server", server.calls.isEmpty())
    }

    @Test
    fun trustingItConnectsAndTheTrustIsKept() = runTest {
        server.answer("ping")
        val asked = asked()
        security.trust(asked.host.uppercase(), formatFingerprint(asked.fingerprint))
        assertTrue(accounts.test(server.address, "winters", "pw") is TestOutcome.Reached)
        // Kept by host, in lowercase, as the plain fingerprint.
        assertEquals(mapOf(pinKey(host) to cert.fingerprint()), SettingsStore(settingsFile).current.trustedCertificates)
        // A later run trusts it too.
        val again = SettingsStore(settingsFile)
        val later = ServerSecurity(again)
        val next = Accounts(again, SessionOnlySecrets(), later.install(OkHttpClient.Builder()).build(), later)
        assertTrue(next.test(server.address, "winters", "pw") is TestOutcome.Reached)
    }

    @Test
    fun aTrustedCertificateCountsOnlyForItsOwnHost() = runTest {
        server.answer("ping")
        security.trust("elsewhere.test", cert.fingerprint())
        asked()
        assertEquals(setOf("elsewhere.test"), settings.current.trustedCertificates.keys)
    }

    @Test
    fun signingInAsksTheSameWay() = runTest {
        server.answer("ping")
        val outcome = accounts.signIn(server.address, "winters", "pw")
        assertTrue(outcome is SignInOutcome.Untrusted)
        assertEquals(null, settings.current.server)
    }

    private companion object {
        val PASSWORD = "octo-test".toCharArray()
    }
}
