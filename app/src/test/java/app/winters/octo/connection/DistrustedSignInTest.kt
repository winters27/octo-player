package app.winters.octo.connection

import app.winters.octo.data.SignInError
import app.winters.octo.data.untrusted
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.GatewayNames
import app.winters.octo.subsonic.gateway.basicCredentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509ExtendedTrustManager

// A server whose chain runs through a distrusted eMudhra key is refused by
// the client the app signs in with, whatever sign-in page sits in front of
// it, and the sign-in says why instead of asking about the certificate.
@RunWith(RobolectricTestRunner::class)
class DistrustedSignInTest {
    private val host = "dv-g3b-emudhra.we-have-a-problem.com"
    private val chain: Array<X509Certificate> =
        File("../shared/core/src/commonTest/resources/certs/distrusted-chains/$host.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).map { cert -> cert as X509Certificate }.toTypedArray()
        }

    // No sign-in page, a password one (a proxy's, Authentik's or
    // Authelia's) and a Cloudflare Access token.
    private val signIns = mapOf(
        "none" to emptyList(),
        "password" to listOf(ServerHeader(GatewayNames.AUTHORIZATION, basicCredentials("alice", "pw"))),
        "cloudflare" to listOf(ServerHeader(GatewayNames.CF_ID, "id.access"), ServerHeader(GatewayNames.CF_SECRET, "secret")),
    )

    // Red when ConnectionSecurity's trust is not distrusting, or a pin for
    // the leaf turns the refusal into a certificate question.
    @Test
    fun everySignInRefusesADistrustedChainWithItsOwnWords() {
        val main = "https://$host/".toHttpUrl()
        for ((kind, headers) in signIns) {
            val security = testSecurity()
            security.configure(main, ConnectionSettings(headers = headers, pins = mapOf(host to chain[0].fingerprint())))
            val trust = security.install(OkHttpClient.Builder()).build().x509TrustManager as X509ExtendedTrustManager
            val engine = SSLContext.getDefault().createSSLEngine(host, 443)
            val refused = assertThrows(kind, DistrustedCertificateException::class.java) {
                trust.checkServerTrusted(chain, "ECDHE_ECDSA", engine)
            }
            assertNull(kind, security.takeRejected(host))
            val failure = SubsonicException.Unreachable(SSLHandshakeException("handshake failed").apply { initCause(refused) })
            assertNull(kind, untrusted(security, failure, main))
            assertEquals(kind, DISTRUSTED_MESSAGE, SignInError.Failed(failure).userMessage())
        }
    }
}
