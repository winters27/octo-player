package app.winters.octo.desktop.server

import app.winters.octo.connection.PinningHostnameVerifier
import app.winters.octo.connection.PinningTrustManager
import app.winters.octo.connection.isConnectionError
import app.winters.octo.connection.normalizeFingerprint
import app.winters.octo.connection.pinKey
import app.winters.octo.connection.platformTrustManager
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.DeviceIdentity
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.gateway.RefuseHtml
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.origin
import app.winters.octo.subsonic.serverHeaders
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext

// A certificate the system does not trust, to ask the listener about: the
// host that showed it and its SHA-256 fingerprint.
data class CertificateQuestion(val host: String, val fingerprint: String)

// What the network needs to know about the signed-in server, as on the
// phone: its extra headers and the addresses they may go to (a sign-in
// page's headers only to the main address, and to the home one over
// https), the sign-in page's session cookies, the certificates the listener
// chose to trust, each for its own host, and this computer's id and name
// for the server's own addresses. The one shared HTTP client (the server's
// API, covers and lyrics) reads it on every request, so a change applies
// everywhere at once. A check of a server gets one of its own (forCheck).
class ServerSecurity(
    private val settings: SettingsStore,
    // This computer's id and name, told to the signed-in server only.
    device: () -> DeviceIdentity = { desktopDevice(settings) },
) {
    private val device by lazy(device)

    // One configuration: the server's addresses and headers and the
    // certificates trusted when it was made, the scope every request made
    // under it goes by, and the sign-in page cookies their answers set.
    private class Current(
        val main: HttpUrl,
        val home: HttpUrl?,
        val headers: Map<String, String>,
        val pins: Map<String, String>,
        val cookies: GatewayCookies,
        val scope: HeaderScope,
    )

    @Volatile private var current: Current? = null

    // Certificates that failed, by host, for asking the listener about.
    private val rejected = ConcurrentHashMap<String, X509Certificate>()

    // Called when a request to the server could not connect at all, so the
    // address in use can be checked again.
    @Volatile var onConnectionError: (() -> Unit)? = null

    // The trusted certificates, by host.
    val pins: () -> Map<String, String> = { settings.current.trustedCertificates }
    private val remember: (String, X509Certificate) -> Unit = { host, leaf -> rejected[pinKey(host)] = leaf }

    private val trustManager by lazy { PinningTrustManager(platformTrustManager(), pins, remember) }

    // This computer's id and name as headers, for the audio engine, which
    // fetches songs from the server by itself.
    fun deviceHeaders(): Map<String, String> = device.headers()

    // This computer's name, as the family sees it.
    fun deviceName(): String = device.name

    // Sends these headers to the server at its main and home addresses from
    // now on. The sign-in page cookies kept so far stay only when nothing
    // changed: the addresses, every header value and the trusted
    // certificates. Any change starts with none, so a cookie never speaks
    // for another sign-in.
    fun configure(main: HttpUrl, home: HttpUrl?, headers: Map<String, String>) {
        val pins = settings.current.trustedCertificates
        val kept = current?.takeIf { it.main == main && it.home == home && it.headers == headers && it.pins == pins }?.cookies
        val cookies = kept ?: GatewayCookies()
        val scope = HeaderScope(
            origins = listOfNotNull(main, home).mapTo(HashSet(), ::origin),
            headers = headers,
            device = device,
            gatewayOrigins = gatewayOrigins(main, home),
            cookies = cookies,
        )
        current = Current(main, home, headers, pins, cookies, scope)
    }

    fun clear() {
        current = null
        onConnectionError = null
        rejected.clear()
    }

    // Trusts this certificate for this host only, from now on.
    fun trust(host: String, fingerprint: String) {
        settings.update { it.copy(trustedCertificates = it.trustedCertificates + (pinKey(host) to normalizeFingerprint(fingerprint))) }
    }

    // The certificate a host last showed that was not trusted, taken once.
    fun takeRejected(host: String): X509Certificate? = rejected.remove(pinKey(host))

    // A security of its own for checking a server: this computer's id and
    // name and the trusted certificates, nothing configured yet, and no
    // cookies.
    fun forCheck(): ServerSecurity = ServerSecurity(settings) { device }

    // For a client that keeps files from the server (covers): a sign-in page
    // answering in the server's place is refused rather than kept.
    fun refuseHtml(): Interceptor = RefuseHtml { current?.scope?.origins.orEmpty() }

    // Sets up a client builder to use all of the above. Each request goes by
    // the configuration in force when it starts, on every hop, and the
    // cookies its answers set go to that configuration's jar.
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return builder
            .addInterceptor(watch)
            .serverHeaders { current?.scope }
            .sslSocketFactory(tls.socketFactory, trustManager)
            .hostnameVerifier(PinningHostnameVerifier(pins, remember))
    }

    private val watch = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val server = current?.scope?.covers(request.url) == true
            if (server && isConnectionError(e)) onConnectionError?.invoke()
            throw e
        }
    }
}
