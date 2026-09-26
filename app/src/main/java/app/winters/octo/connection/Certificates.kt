package app.winters.octo.connection

import android.annotation.SuppressLint
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

// A certificate's SHA-256 fingerprint as it is kept: lowercase hex, no marks.
fun sha256Fingerprint(encoded: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

fun X509Certificate.fingerprint(): String = sha256Fingerprint(encoded)

// A fingerprint as people compare it: uppercase pairs split by colons.
// Anything that is not hex (spaces, colons) is ignored on the way in.
fun formatFingerprint(fingerprint: String): String =
    normalizeFingerprint(fingerprint).uppercase().chunked(2).joinToString(":")

fun normalizeFingerprint(text: String): String = text.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

// Pins are kept by host alone, in lowercase.
fun pinKey(host: String): String = host.lowercase()

// Whether a certificate the phone does not trust may be used anyway: only
// when the user trusted exactly this certificate for exactly this host.
fun pinAllows(pins: Map<String, String>, host: String?, fingerprint: String): Boolean =
    host != null && pins[pinKey(host)] == normalizeFingerprint(fingerprint)

// The phone's own trust, with the certificates the user chose to trust
// added, each for its own host only. Every certificate is first checked
// the normal way; a pin is only looked at when that fails. A certificate
// that fails and is not pinned is remembered by host, so the app can show
// it and ask. It never trusts more than the phone does except for a pin
// the user made, which is why the custom trust manager is allowed here.
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(
    private val platform: X509TrustManager,
    private val pins: () -> Map<String, String>,
    private val onRejected: (host: String, leaf: X509Certificate) -> Unit,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val host = (socket as? SSLSocket)?.handshakeSession?.peerHost
        check(chain, host) {
            val extended = platform as? X509ExtendedTrustManager
            if (extended != null) extended.checkServerTrusted(chain, authType, socket) else platform.checkServerTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        check(chain, engine?.peerHost) {
            val extended = platform as? X509ExtendedTrustManager
            if (extended != null) extended.checkServerTrusted(chain, authType, engine) else platform.checkServerTrusted(chain, authType)
        }
    }

    // With no connection to say which host this is, only the phone's trust counts.
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkServerTrusted(chain, authType)

    private inline fun check(chain: Array<X509Certificate>, host: String?, platformCheck: () -> Unit) {
        try {
            platformCheck()
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (pinAllows(pins(), host, leaf.fingerprint())) return
            if (host != null) onRejected(host, leaf)
            throw e
        }
    }

    // The app is never a server, so client checks go to the phone's trust.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

// Checks the name on a certificate, as usual. A pinned certificate is the
// host's identity by itself, so it passes for its own host even when the
// name on it does not match (self-made certificates often name something
// else). A mismatch is remembered like a rejected certificate.
class PinningHostnameVerifier(
    private val pins: () -> Map<String, String>,
    private val onRejected: (host: String, leaf: X509Certificate) -> Unit,
    private val usual: HostnameVerifier = OkHostnameVerifier,
) : HostnameVerifier {
    override fun verify(host: String, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (leaf != null && pinAllows(pins(), host, leaf.fingerprint())) return true
        return usual.verify(host, session).also { ok -> if (!ok && leaf != null) onRejected(host, leaf) }
    }
}

// The phone's own trust: the system's certificates, as the network
// security config allows.
fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
