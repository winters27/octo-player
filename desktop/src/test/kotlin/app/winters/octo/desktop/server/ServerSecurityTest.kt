package app.winters.octo.desktop.server

import app.winters.octo.connection.DISTRUSTED_MESSAGE
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.connection.pin
import app.winters.octo.connection.pinKey
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.gateway.GatewayKind
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
import java.security.cert.CertificateFactory
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
        security.trust(asked.host.uppercase(), asked.pin)
        assertTrue(accounts.test(server.address, "winters", "pw") is TestOutcome.Reached)
        // Kept by host, in lowercase, as the pin of the certificate's key.
        assertEquals(mapOf(pinKey(host) to cert.pin()), SettingsStore(settingsFile).current.trustedCertificates)
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

    // The same certificate, sent with a distrusted eMudhra intermediate, is
    // refused even with the leaf trusted, with no sign-in page, a password
    // one (a proxy's, Authentik's or Authelia's) or a Cloudflare Access
    // token. Red when ServerSecurity's trust is not distrusting.
    @Test
    fun aChainThroughADistrustedKeyIsRefusedEvenWhenItsLeafIsTrusted() = runTest {
        val distrusted = ServerSecurityTest::class.java.getResourceAsStream("/certs/distrusted-chains/dv-g3b-emudhra.we-have-a-problem.com.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate }[1]
        }
        val sending = KeyStore.getInstance("JKS").apply {
            load(null, null)
            setKeyEntry("music", keys.getKey("music", PASSWORD), PASSWORD, arrayOf(cert, distrusted))
        }
        FakeServer(
            SSLContext.getInstance("TLS").apply {
                val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(sending, PASSWORD) }
                init(factory.keyManagers, null, null)
            }.socketFactory,
        ).use { other ->
            other.answer("ping")
            val otherHost = other.address.toHttpUrl().host
            security.trust(otherHost, cert.fingerprint())
            val signIns = mapOf(
                "none" to GatewayDraft(),
                "password" to GatewayDraft(GatewayKind.Password, "alice", "pw"),
                "cloudflare" to GatewayDraft(GatewayKind.CloudflareToken, "id.access", "secret"),
            )
            for ((kind, gateway) in signIns) {
                val outcome = accounts.test(SignInRequest(other.address, "winters", "pw", gateway = gateway))
                assertTrue("$kind not refused: $outcome", outcome is TestOutcome.Failed)
                // The distrust alone, with no hint to try plain http.
                assertEquals(kind, DISTRUSTED_MESSAGE, (outcome as TestOutcome.Failed).message)
                assertEquals(kind, null, security.takeRejected(otherHost))
            }
            assertTrue("nothing reached the server", other.calls.isEmpty())
        }
    }

    @Test
    fun signingInAsksTheSameWay() = runTest {
        server.answer("ping")
        val outcome = accounts.signIn(server.address, "winters", "pw")
        assertTrue(outcome is SignInOutcome.Untrusted)
        assertEquals(null, settings.current.server)
    }

    // A kept server is looked at through a client of its own, which honors
    // the certificate trusted for it. Red when the check client is built
    // without the trusted certificates.
    @Test
    fun aKeptServerIsLookedAtWithItsTrustedCertificate() = runTest {
        server.answer("ping")
        val asked = asked()
        security.trust(asked.host, asked.pin)
        val done = accounts.signIn(server.address, "winters", "pw") as SignInOutcome.Done
        assertEquals(Reach.Answers, accounts.check(done.connection.server.id).reach)
    }

    private companion object {
        val PASSWORD = "octo-test".toCharArray()
    }
}
