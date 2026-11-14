package app.winters.octo.desktop.server

import app.winters.octo.connection.DistrustingTrustManager
import app.winters.octo.connection.PinningHostnameVerifier
import app.winters.octo.connection.PinningTrustManager
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.isConnectionError
import app.winters.octo.connection.isPinnedHost
import app.winters.octo.connection.normalizePin
import app.winters.octo.connection.pin
import app.winters.octo.connection.pinKey
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.DeviceIdentity
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.gateway.RefuseHtml
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.serverHeaders
import app.winters.octo.subsonic.serverOrigins
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.connection.RealConnection
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

// A certificate the system does not trust, to ask the listener about: the
// host that showed it, its SHA-256 fingerprint (what they compare) and its
// pin (its key's, what is kept when they trust it). A pin left out is the
// whole certificate's, the fingerprint itself.
data class CertificateQuestion(val host: String, val fingerprint: String, val pin: String = fingerprint) {
    constructor(host: String, leaf: X509Certificate) : this(host, leaf.fingerprint(), leaf.pin())
}

// How a call that failed went wrong at the network's level, weakest first:
// nothing, anything else, the connection cut off, the secure handshake.
enum class TransportFailure { None, Other, Reset, Tls }

// The strongest failure in an exception's tree, its causes and suppressed
// ones included (a name with several addresses can hide the handshake's
// failure behind another address's refusal). Each is judged by its own
// class.
fun transportFailureOf(e: Throwable?): TransportFailure {
    if (e == null) return TransportFailure.None
    val own = when (e) {
        is ConnectException, is NoRouteToHostException, is PortUnreachableException, is UnknownHostException, is InterruptedIOException -> TransportFailure.Other
        is SSLException -> TransportFailure.Tls
        is SocketException -> TransportFailure.Reset
        else -> TransportFailure.Other
    }
    val inner = listOfNotNull(e.cause?.takeIf { it !== e }) + e.suppressed
    return (inner.map(::transportFailureOf) + own).max()
}

// A connection made for another client certificate than the one in use
// now: dropped, and the call made again on a fresh one.
class StaleClientIdentity : IOException("made for another client certificate")

// One client certificate as a key manager offers it, under one alias,
// never changed.
internal class KeyIdentity(val certificate: LoadedCertificate) {
    val alias: String = "octo-" + certificate.chainHash.take(16)
    val chain: Array<X509Certificate> = certificate.chain.toTypedArray()
}

// What the factory knows of a socket it made: where it goes, and what the
// key manager had for it when the server asked for a certificate.
internal class SocketMark(val origin: TlsOrigin?) {
    @Volatile var asked = false
    @Volatile var alias: String? = null
    @Volatile var offered = false
}

// The latest handshake with one origin that asked for a certificate: the
// alias the key manager had for it (none, or one offered or turned down
// for its key type), and how the call over it ended. A response read over
// it ends the record, except nginx's page for a certificate.
internal class OriginRecord(val alias: String?, val offered: Boolean) {
    @Volatile var failure = TransportFailure.None
    @Volatile var nginx = false
}

// nginx's own pages for a client certificate it wanted and did not get, or
// turned down.
private val NGINX_TITLES = listOf("<title>400 No required SSL certificate was sent</title>", "<title>400 The SSL certificate error</title>")

private fun nginxCertificatePage(response: Response): Boolean {
    val text = runCatching { response.peekBody(4_096).string() }.getOrDefault("")
    return NGINX_TITLES.any { text.contains(it, ignoreCase = true) }
}

private fun fitsKey(type: String, key: PrivateKey): Boolean =
    type.equals(key.algorithm, ignoreCase = true) || (key.algorithm == "EdDSA" && type.equals("Ed25519", ignoreCase = true))

// Offers the one certificate it was made with, to the server's own
// origins only, and only when the server takes its kind of key. It notes
// on each socket what it had when asked.
internal class HostKeyManager(
    private val identity: KeyIdentity?,
    private val origins: () -> Set<TlsOrigin>,
    private val markOf: (Socket) -> SocketMark?,
    private val asked: (TlsOrigin, String?, Boolean) -> Unit,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? {
        val mark = socket?.let(markOf) ?: return null
        val origin = mark.origin ?: return null
        val had = identity?.takeIf { origin in origins() }
        val fits = had != null && keyType.orEmpty().any { fitsKey(it, had.certificate.key) }
        synchronized(mark) {
            mark.asked = true
            if (had != null) mark.alias = had.alias
            if (fits) mark.offered = true
        }
        asked(origin, had?.alias, mark.offered)
        return if (fits) had?.alias else null
    }

    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? = null

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = identity?.let { arrayOf(it.alias) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = identity?.takeIf { it.alias == alias }?.chain

    override fun getPrivateKey(alias: String?): PrivateKey? = identity?.takeIf { it.alias == alias }?.certificate?.key

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
}

// One factory for a client's whole life, over the context of the
// certificate in use: a change swaps the context behind it, so a session
// made with an old certificate is never resumed. Sockets come back as the
// context made them, never wrapped, each noted with where it goes.
internal class SwappingFactory(@Volatile var delegate: SSLSocketFactory, private val made: (Socket, TlsOrigin?) -> Unit) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose).also { made(it, host?.let { h -> TlsOrigin(h.lowercase(), port) }) }

    override fun createSocket(host: String?, port: Int): Socket =
        delegate.createSocket(host, port).also { made(it, host?.let { h -> TlsOrigin(h.lowercase(), port) }) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).also { made(it, host?.let { h -> TlsOrigin(h.lowercase(), port) }) }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        delegate.createSocket(host, port).also { made(it, host?.let { h -> TlsOrigin(h.hostAddress.lowercase(), port) }) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).also { made(it, address?.let { h -> TlsOrigin(h.hostAddress.lowercase(), port) }) }

    override fun createSocket(): Socket = delegate.createSocket().also { made(it, null) }
}

// What the network needs to know about the signed-in server, as on the
// phone: its extra headers and the addresses they may go to (each address
// and the same host over https; a sign-in page's headers only to the main
// address and over https), the sign-in page's session cookies, the
// certificates the listener chose to trust, each for its own host, its
// client certificate, offered only to its own https addresses, and this
// computer's id and name for the server's own addresses. The one shared
// HTTP client (the server's API, covers and lyrics) reads it on every
// request, so a change applies everywhere at once. A check of a server gets
// one of its own (forCheck).
class ServerSecurity(
    private val settings: SettingsStore,
    // The authorities trusted before any pin: the runtime's and the
    // system's (systemTrust).
    private val authorities: () -> X509TrustManager = { systemTrust },
    // This computer's id and name, told to the signed-in server only.
    device: () -> DeviceIdentity = { desktopDevice(settings) },
) {
    private val device by lazy(device)

    // One configuration: the server's addresses and headers, the
    // certificates trusted when it was made, its client certificate and the
    // origins that goes to, the scope every request made under it goes by,
    // and the sign-in page cookies their answers set.
    private class Current(
        val main: HttpUrl,
        val home: HttpUrl?,
        val headers: Map<String, String>,
        val pins: Map<String, String>,
        val cookies: GatewayCookies,
        val scope: HeaderScope,
        val identity: KeyIdentity?,
        val identityOrigins: Set<TlsOrigin>,
    )

    @Volatile private var current: Current? = null

    // Certificates that failed, by host, for asking the listener about.
    private val rejected = ConcurrentHashMap<String, X509Certificate>()

    // Called when a request to the server could not connect at all, so the
    // address in use can be checked again.
    @Volatile var onConnectionError: (() -> Unit)? = null

    // A host of the server in use the listener trusted a certificate for,
    // showing another one: the question to ask again, one at a time. Only
    // the shared security asks; a check's own (forCheck) keeps its
    // rejected certificate for the sign-in form.
    private val _certificateChange = MutableStateFlow<CertificateQuestion?>(null)
    val certificateChange: StateFlow<CertificateQuestion?> = _certificateChange

    // Pins of certificates the listener said no to, not asked about again
    // until Octo starts again or the server is signed out of.
    private val declined: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The trusted certificates, by host.
    val pins: () -> Map<String, String> = { settings.current.trustedCertificates }
    private val remember: (String, X509Certificate) -> Unit = { host, leaf ->
        rejected[pinKey(host)] = leaf
        if (isServerHost(host) && isPinnedHost(pins(), host)) {
            val asked = CertificateQuestion(pinKey(host), leaf)
            if (asked.pin !in declined) _certificateChange.compareAndSet(null, asked)
        }
    }

    // Whether this is the main or home host of the server in use.
    private fun isServerHost(host: String): Boolean {
        val now = current ?: return false
        return pinKey(host) == pinKey(now.main.host) || now.home?.let { pinKey(host) == pinKey(it.host) } == true
    }

    // The answer to a changed certificate: the question goes, and one said
    // no to is not asked about again.
    fun answered(question: CertificateQuestion, trusted: Boolean) {
        if (!trusted) declined += question.pin
        _certificateChange.compareAndSet(question, null)
    }

    // Distrusted authorities are turned down before a pin is looked at. The
    // system's authorities come in with the runtime's.
    private val trustManager by lazy { DistrustingTrustManager(PinningTrustManager(authorities(), pins, remember)) }

    // The sockets the factory made, with what the key manager did for each.
    private val marks: MutableMap<Socket, SocketMark> = Collections.synchronizedMap(WeakHashMap())

    // By origin, the latest handshake that asked for a certificate.
    private val records = ConcurrentHashMap<TlsOrigin, OriginRecord>()

    // The client certificate the factory's context was made with, by its
    // chain hash; null for none. Kept apart from `current`, which clear()
    // lets go of: the next configuration compares with what is installed.
    @Volatile private var installedIdentity: String? = null

    private val factory by lazy { SwappingFactory(contextFor(null).socketFactory) { socket, origin -> marks[socket] = SocketMark(origin) } }

    // Calls made again because their connection was made for another
    // client certificate, for tests.
    internal val staleRetries = AtomicInteger()

    // The leaf of the client certificate in use, for tests.
    internal val identityFingerprint: String? get() = current?.identity?.certificate?.fingerprint

    private fun contextFor(identity: KeyIdentity?): SSLContext = SSLContext.getInstance("TLS").apply {
        val keys = HostKeyManager(identity, { current?.identityOrigins.orEmpty() }, { marks[it] }) { origin, alias, offered ->
            records[origin] = OriginRecord(alias, offered)
        }
        init(arrayOf(keys), arrayOf(trustManager), null)
    }

    // This computer's id and name as headers, for the audio engine, which
    // fetches songs from the server by itself.
    fun deviceHeaders(): Map<String, String> = device.headers()

    // This computer's name, as the family sees it.
    fun deviceName(): String = device.name

    // Sends these headers, and offers this client certificate, to the
    // server at its main and home addresses from now on. The sign-in page
    // cookies kept so far stay only when nothing changed: the addresses,
    // every header value, the trusted certificates and the client
    // certificate. Any change starts with none, so a cookie never speaks for
    // another sign-in. Another client certificate swaps the context behind
    // the factory first.
    fun configure(main: HttpUrl, home: HttpUrl?, headers: Map<String, String>, certificate: LoadedCertificate? = null) {
        val pins = settings.current.trustedCertificates
        val identity = certificate?.let(::KeyIdentity)
        val kept = current?.takeIf {
            it.main == main && it.home == home && it.headers == headers && it.pins == pins &&
                it.identity?.certificate?.fingerprint == identity?.certificate?.fingerprint
        }?.cookies
        val cookies = kept ?: GatewayCookies()
        val scope = HeaderScope(
            origins = serverOrigins(main, home),
            headers = headers,
            device = device,
            gatewayOrigins = gatewayOrigins(main, home),
            cookies = cookies,
        )
        val wanted = certificate?.chainHash
        if (wanted != installedIdentity) {
            factory.delegate = contextFor(identity).socketFactory
            installedIdentity = wanted
        }
        current = Current(main, home, headers, pins, cookies, scope, identity, if (identity == null) emptySet() else certificateOrigins(main, home))
    }

    // Lets go of the configuration. What each origin's last handshake said
    // about a client certificate stays, for the words after a check.
    fun clear() {
        current = null
        onConnectionError = null
        rejected.clear()
        declined.clear()
        _certificateChange.value = null
    }

    // Trusts the certificate with this pin (CertificateQuestion.pin, or a
    // whole certificate's fingerprint as older pins were) for this host
    // only, from now on.
    fun trust(host: String, pin: String) {
        settings.update { it.copy(trustedCertificates = it.trustedCertificates + (pinKey(host) to normalizePin(pin))) }
    }

    // The certificate a host last showed that was not trusted, taken once.
    fun takeRejected(host: String): X509Certificate? = rejected.remove(pinKey(host))

    // The same, left in place.
    fun peekRejected(host: String): X509Certificate? = rejected[pinKey(host)]

    // What the server said about the client certificate, from the latest
    // handshake with any of these addresses that asked for one: that it
    // asked and none was offered, or that one was and it was turned down.
    // Null when none of them asked, or when the call over it failed some
    // other way (an outage after a good handshake says nothing).
    fun certificateWords(urls: List<HttpUrl>): String? {
        for (url in urls) {
            if (!url.isHttps) continue
            val record = records[TlsOrigin.of(url)] ?: continue
            if (!record.nginx && record.failure != TransportFailure.Tls && record.failure != TransportFailure.Reset) continue
            return if (record.alias != null) SignInWords.CERT_REFUSED else SignInWords.CERT_ASKED
        }
        return null
    }

    // A security of its own for checking a server: this computer's id and
    // name and the trusted certificates, nothing configured yet, and no
    // cookies.
    fun forCheck(): ServerSecurity = ServerSecurity(settings, authorities) { device }

    // For a client that keeps files from the server (covers): a sign-in page
    // answering in the server's place is refused rather than kept.
    fun refuseHtml(): Interceptor = RefuseHtml { current?.scope?.origins.orEmpty() }

    // Sets up a client builder to use all of the above. Each request goes by
    // the configuration in force when it starts, on every hop, and the
    // cookies its answers set go to that configuration's jar. A call whose
    // connection was made for another client certificate is made once more.
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .addInterceptor(retryStale)
        .addInterceptor(watch)
        .serverHeaders { current?.scope }
        .addNetworkInterceptor(identityCheck)
        .sslSocketFactory(factory, trustManager)
        .hostnameVerifier(PinningHostnameVerifier(pins, remember))

    private val retryStale = Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: StaleClientIdentity) {
            staleRetries.incrementAndGet()
            chain.proceed(chain.request())
        }
    }

    private val watch = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (e !is StaleClientIdentity) {
                if (request.url.isHttps) records[TlsOrigin.of(request.url)]?.failure = transportFailureOf(e)
                val server = current?.scope?.covers(request.url) == true
                if (server && isConnectionError(e)) onConnectionError?.invoke()
            }
            throw e
        }
    }

    // The alias in use for an origin, and the leaf it offers there.
    private fun aliasFor(origin: TlsOrigin): String? = current?.takeIf { origin in it.identityOrigins }?.identity?.alias

    private fun leafFor(origin: TlsOrigin): String? = current?.takeIf { origin in it.identityOrigins }?.identity?.certificate?.fingerprint

    // A connection is stale when its handshake was asked for a certificate
    // and the alias it had then is not the one in use for that origin now,
    // or when it presented a leaf that is not the one in use there.
    private fun stale(mark: SocketMark?, origin: TlsOrigin, local: List<Certificate>?): Boolean {
        if (mark != null && mark.asked && mark.alias != aliasFor(origin)) return true
        val leaf = local?.firstOrNull() as? X509Certificate ?: return false
        return leaf.fingerprint() != leafFor(origin)
    }

    @OptIn(OkHttpInternalApi::class)
    private val identityCheck = Interceptor { chain ->
        val connection = chain.connection()
        val socket = connection?.socket() as? SSLSocket
        if (connection != null && socket != null) {
            val mark = marks[socket]
            val origin = mark?.origin ?: chain.request().url.takeIf { it.isHttps }?.let(TlsOrigin::of)
            if (origin != null && stale(mark, origin, connection.handshake()?.localCertificates)) {
                runCatching { socket.session.invalidate() }
                (connection as? RealConnection)?.noNewExchanges()
                throw StaleClientIdentity()
            }
        }
        val response = chain.proceed(chain.request())
        val url = chain.request().url
        if (url.isHttps) {
            val origin = TlsOrigin.of(url)
            val record = records[origin]
            if (record != null) {
                if (response.code == 400 && nginxCertificatePage(response)) record.nginx = true else records.remove(origin, record)
            }
        }
        response
    }
}
