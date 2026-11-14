package app.winters.octo.connection

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

// A pin is kept as this mark and the SHA-256 of the certificate's public
// key (its SubjectPublicKeyInfo), so a renewed certificate with the same
// key still counts. A pin without the mark is the SHA-256 of a whole
// certificate, as pins were kept before; those count for exactly that
// certificate, as they always did.
const val KEY_PIN_MARK = "spki:"

// The pin a certificate the user trusts is kept as.
fun X509Certificate.pin(): String = KEY_PIN_MARK + keyFingerprint()

// A pin as it is kept: a key pin keeps its mark, and the hex after it (or
// a whole-certificate pin's hex) loses any marks and spaces, like a
// fingerprint.
fun normalizePin(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.startsWith(KEY_PIN_MARK, ignoreCase = true)) {
        KEY_PIN_MARK + normalizeFingerprint(trimmed.substring(KEY_PIN_MARK.length))
    } else {
        normalizeFingerprint(trimmed)
    }
}

// Whether a kept pin stands for this certificate.
fun pinMatches(pin: String, leaf: X509Certificate): Boolean {
    val kept = normalizePin(pin)
    return if (kept.startsWith(KEY_PIN_MARK)) kept == leaf.pin() else kept == leaf.fingerprint()
}

// Whether a certificate the system does not trust may be used anyway: only
// when the user trusted this certificate (or its key) for exactly this host.
fun pinAllows(pins: Map<String, String>, host: String?, leaf: X509Certificate): Boolean {
    val pin = host?.let { pins[pinKey(it)] } ?: return false
    return pinMatches(pin, leaf)
}

// Whether the user trusted a certificate for this host before, so a
// different one there is a changed certificate to ask about again rather
// than a stranger.
fun isPinnedHost(pins: Map<String, String>, host: String?): Boolean = host != null && pins.containsKey(pinKey(host))

// The system's own trust, with the certificates the user chose to trust
// added, each for its own host only. Every certificate is first checked
// the normal way; a pin is only looked at when that fails. A certificate
// that fails and is not pinned is remembered by host, so the app can show
// it and ask. It never trusts more than the system does except for a pin
// the user made, which is why the custom trust manager is allowed here.
// Shared by the phone and desktop apps.
@Suppress("CustomX509TrustManager")
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

    // With no connection to say which host this is, only the system's trust counts.
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkServerTrusted(chain, authType)

    private inline fun check(chain: Array<X509Certificate>, host: String?, platformCheck: () -> Unit) {
        try {
            platformCheck()
        } catch (e: CertificateException) {
            // A distrusted authority is never a question for the user.
            if (e is DistrustedCertificateException) throw e
            val leaf = chain.firstOrNull() ?: throw e
            if (pinAllows(pins(), host, leaf)) return
            if (host != null) onRejected(host, leaf)
            throw e
        }
    }

    // The app is never a server, so client checks go to the system's trust.
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
        if (leaf != null && pinAllows(pins(), host, leaf)) return true
        return usual.verify(host, session).also { ok -> if (!ok && leaf != null) onRejected(host, leaf) }
    }
}

// The system's own trust: its certificates, and on the phone as the
// network security config allows.
fun platformTrustManager(): X509TrustManager = trustManagerFor(null)

// Trust in the authorities of this key store, or the system's own with
// none.
fun trustManagerFor(store: KeyStore?): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(store)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
