package app.winters.octo.connection

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

// Public keys no chain may use, however the system feels about them: the
// SHA-256 of each certificate's subject public key, in lowercase hex. These
// are the eMudhra keys made with too little randomness (2026-10-17), which
// anyone can sign with, and the root they hang from.
val distrustedKeys = setOf(
    "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105", // EM DV TLS CA - G3B
    "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b", // EM OV TLS CA - G3B
    "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41", // EM EV TLS CA - G3B
    "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f", // EM DV TLS CA - G3C
    "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8", // EM OV TLS CA - G3C
    "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0", // EM EV TLS CA - G3C
    "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6", // EM OV CS CA - G3B
    "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30", // EM OV CS CA - G3C
    "8d417db2dd8bf5e3084d1e3f196d583849d81bdd4c00c70b9d39369e96b8c782", // emSign ECC Root CA - G3
)

// Issuers no certificate may name: the same certificates by name. A server
// does not have to send the certificate that issued its own, and the phone
// fills one in from those it saw before, so the name catches what the key
// check cannot see.
val distrustedIssuers = setOf(
    X500Principal("CN=EM DV TLS CA - G3B, O=eMudhra BV, C=NL"),
    X500Principal("CN=EM OV TLS CA - G3B, O=eMudhra BV, C=NL"),
    X500Principal("CN=EM EV TLS CA - G3B, O=eMudhra BV, C=NL"),
    X500Principal("CN=EM DV TLS CA - G3C, O=eMudhra Inc, C=US"),
    X500Principal("CN=EM OV TLS CA - G3C, O=eMudhra Inc, C=US"),
    X500Principal("CN=EM EV TLS CA - G3C, O=eMudhra Inc, C=US"),
    X500Principal("CN=EM OV CS CA - G3B, O=eMudhra BV, C=NL"),
    X500Principal("CN=EM OV CS CA - G3C, O=eMudhra Inc, C=US"),
    X500Principal("CN=emSign ECC Root CA - G3, O=eMudhra Technologies Limited, OU=emSign PKI, C=IN"),
)

fun X509Certificate.keyFingerprint(): String = sha256Fingerprint(publicKey.encoded)

fun isDistrusted(cert: X509Certificate): Boolean =
    cert.keyFingerprint() in distrustedKeys || cert.issuerX500Principal in distrustedIssuers

const val DISTRUSTED_MESSAGE = "The server's certificate comes from an authority that is no longer trusted."

class DistrustedCertificateException : CertificateException(DISTRUSTED_MESSAGE)

// Whether a failure came from a distrusted certificate, however deeply the
// TLS and HTTP layers wrapped it (OkHttp keeps other routes' failures as
// suppressed ones).
fun Throwable.isDistrusted(): Boolean {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (!seen.add(next)) continue
        if (next is DistrustedCertificateException) return true
        next.cause?.let(queue::add)
        queue.addAll(next.suppressed)
    }
    return false
}

// The chain the system itself built for a host, root included, or a
// failure when the system does not trust it. The phone can say; the
// desktop's checks use only what the server sent, so it passes none.
fun interface BuiltChain {
    fun of(chain: Array<X509Certificate>, authType: String, host: String?): List<X509Certificate>
}

// The given trust, after turning down any chain with a distrusted
// certificate in it. What the server sent is checked before anything else,
// so neither a pin nor the system can let one through and the app never
// asks about one. With a BuiltChain, the chain the system completed is
// checked too, after the given trust accepted it.
@Suppress("CustomX509TrustManager")
class DistrustingTrustManager(
    private val trust: X509TrustManager,
    private val built: BuiltChain? = null,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        refuseDistrusted(chain.asList())
        val extended = trust as? X509ExtendedTrustManager
        if (extended != null) extended.checkServerTrusted(chain, authType, socket) else trust.checkServerTrusted(chain, authType)
        refuseBuilt(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        refuseDistrusted(chain.asList())
        val extended = trust as? X509ExtendedTrustManager
        if (extended != null) extended.checkServerTrusted(chain, authType, engine) else trust.checkServerTrusted(chain, authType)
        refuseBuilt(chain, authType, engine?.handshakeSession?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        refuseDistrusted(chain.asList())
        trust.checkServerTrusted(chain, authType)
        refuseBuilt(chain, authType, null)
    }

    // A chain the system does not trust was let through by the user's pin
    // for exactly this certificate, which needs no authority, so there is
    // no built chain to look at.
    private fun refuseBuilt(chain: Array<X509Certificate>, authType: String, host: String?) {
        val completed = built ?: return
        val path = try {
            completed.of(chain, authType, host)
        } catch (_: CertificateException) {
            return
        }
        refuseDistrusted(path)
    }

    private fun refuseDistrusted(chain: List<X509Certificate>) {
        if (chain.any(::isDistrusted)) throw DistrustedCertificateException()
    }

    // The app is never a server, so client checks go to the given trust.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val extended = trust as? X509ExtendedTrustManager
        if (extended != null) extended.checkClientTrusted(chain, authType, socket) else trust.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        val extended = trust as? X509ExtendedTrustManager
        if (extended != null) extended.checkClientTrusted(chain, authType, engine) else trust.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        trust.checkClientTrusted(chain, authType)

    private val issuers by lazy { trust.acceptedIssuers.filterNot { it.keyFingerprint() in distrustedKeys }.toTypedArray() }

    override fun getAcceptedIssuers(): Array<X509Certificate> = issuers.clone()
}

// Sets up a client builder to trust what the system trusts, less the
// distrusted authorities, for a client of the app's own that must never use
// a server's pins, headers or client certificate.
fun OkHttpClient.Builder.distrustingTls(
    trust: X509TrustManager = DistrustingTrustManager(platformTrustManager()),
): OkHttpClient.Builder {
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return sslSocketFactory(tls.socketFactory, trust)
}
