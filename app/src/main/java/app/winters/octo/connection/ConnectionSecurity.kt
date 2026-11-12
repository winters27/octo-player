package app.winters.octo.connection

import android.content.Context
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.gateway.GatewayNames
import app.winters.octo.subsonic.gateway.RefuseHtml
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.origin
import app.winters.octo.subsonic.serverHeaders
import app.winters.octo.subsonic.serverOrigins
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

// Whether a server's gateway sign-in can't be used where it is: gateway
// headers with a plain http main address outside the home network, where
// they are never sent, so the address has to move to https first.
fun needsHttps(main: HttpUrl, headers: List<ServerHeader>): Boolean =
    headers.any { GatewayNames.isGatewayName(it.name) } && gatewayOrigins(main, null).isEmpty()

// What the network needs to know about the signed-in server: its
// addresses, extra headers, trusted certificates and client certificate,
// the sign-in page's session cookies kept for it, and this phone's id and
// name, which go to those addresses only. The one shared HTTP client (API,
// covers and streams) reads it on every request, so a change applies
// everywhere at once.
@Singleton
class ConnectionSecurity @Inject constructor(
    @ApplicationContext context: Context,
    private val devices: DeviceIds,
) {
    // What decides who is signed in where: the addresses, the ones gateway
    // headers may go to, every header, the trusted certificates and the
    // client certificate. Header values are secrets, so only names print.
    private data class Identity(
        val origins: Set<String>,
        val gatewayOrigins: Set<String>,
        val headers: Map<String, String>,
        val pins: Map<String, String>,
        val clientCert: String?,
    ) {
        override fun toString() =
            "Identity(origins=$origins, gatewayOrigins=$gatewayOrigins, headers=${headers.keys}, " +
                "pins=${pins.keys}, clientCert=${clientCert?.let(::mask)})"
    }

    // One configuration, and the sign-in page's session cookies kept under
    // it. An answer always writes into the cookies of the configuration its
    // request started under.
    private class Current(val identity: Identity, val hosts: Set<String>, val cookies: GatewayCookies)

    @Volatile private var current: Current? = null

    // Certificates that failed, by host, for asking the user about.
    private val rejected = ConcurrentHashMap<String, X509Certificate>()

    private val _connectionErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // A request to the server could not connect at all.
    val connectionErrors: SharedFlow<Unit> = _connectionErrors

    private val pins: () -> Map<String, String> = { current?.identity?.pins.orEmpty() }
    private val remember: (String, X509Certificate) -> Unit = { host, leaf -> rejected[pinKey(host)] = leaf }

    // Distrusted authorities are turned down before a pin is looked at.
    private val platform = platformTrustManager()
    private val trustManager = DistrustingTrustManager(PinningTrustManager(platform, pins, remember), phoneBuiltChain(platform))
    private val keyManager = ClientCertKeyManager(context) {
        current?.let { now -> now.identity.clientCert?.let { ClientCertChoice(it, now.hosts) } }
    }

    // Uses the settings for the server at these addresses from now on. The
    // sign-in page's session cookies carry over only when nothing about the
    // sign-in changed; any other change starts with none.
    fun configure(main: HttpUrl, settings: ConnectionSettings) {
        val addresses = listOfNotNull(main, settings.home)
        val identity = Identity(
            origins = serverOrigins(main, settings.home),
            gatewayOrigins = gatewayOrigins(main, settings.home),
            headers = settings.headers.associate { it.name to it.value },
            pins = settings.pins.mapKeys { pinKey(it.key) },
            clientCert = settings.clientCertAlias,
        )
        val before = current
        val cookies = before?.takeIf { it.identity == identity }?.cookies ?: GatewayCookies()
        current = Current(identity, addresses.mapTo(HashSet()) { pinKey(it.host) }, cookies)
        if (before?.identity?.clientCert != settings.clientCertAlias) keyManager.forget()
    }

    fun clear() {
        current = null
        keyManager.forget()
        rejected.clear()
    }

    // Whether another device on the network could fetch from the server by
    // itself: not when the server needs extra headers, a client certificate,
    // or a certificate only this phone was told to trust.
    fun reachableByOtherDevices(): Boolean =
        current?.identity?.let { it.headers.isEmpty() && it.clientCert == null && it.pins.isEmpty() } ?: true

    // The certificate a host last showed that was not trusted, taken once.
    fun takeRejected(host: String): X509Certificate? = rejected.remove(pinKey(host))

    // Sets up a client builder to use all of the above. The configuration in
    // force as a request starts goes with it (serverHeaders), so every hop of
    // it, and the cookies its answers set, belong to that one even when
    // another comes in meanwhile.
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
        return builder
            .addInterceptor(watch)
            .serverHeaders { current?.let(::scopeOf) }
            .sslSocketFactory(tls.socketFactory, trustManager)
            .hostnameVerifier(PinningHostnameVerifier(pins, remember))
    }

    // The same client for songs, covers, loops and kept files: an HTML page
    // from one of the server's addresses in place of one is refused, never
    // played or kept (see RefuseHtml).
    fun byteClient(http: OkHttpClient): OkHttpClient =
        http.newBuilder().addInterceptor(RefuseHtml { current?.identity?.origins.orEmpty() }).build()

    // Notices a request to the server that could not connect, so the
    // address in use can be checked again.
    private val watch = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val server = current?.identity?.origins?.contains(origin(request.url)) == true
            if (server && isConnectionError(e)) _connectionErrors.tryEmit(Unit)
            throw e
        }
    }

    private fun scopeOf(now: Current) = HeaderScope(
        origins = now.identity.origins,
        headers = now.identity.headers,
        gatewayOrigins = now.identity.gatewayOrigins,
        cookies = now.cookies,
        device = devices.current(),
    )
}
