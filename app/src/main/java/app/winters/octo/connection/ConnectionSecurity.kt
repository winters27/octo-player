package app.winters.octo.connection

import android.content.Context
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.ServerHeaders
import app.winters.octo.subsonic.origin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

// What the network needs to know about the signed-in server: its
// addresses, extra headers, trusted certificates and client certificate.
// The one shared HTTP client (API, covers and streams) reads it on every
// request, so a change applies everywhere at once.
@Singleton
class ConnectionSecurity @Inject constructor(@ApplicationContext context: Context) {
    private class Current(
        val origins: Set<String>,
        val hosts: Set<String>,
        val headers: Map<String, String>,
        val pins: Map<String, String>,
        val clientCert: String?,
    )

    @Volatile private var current: Current? = null

    // Certificates that failed, by host, for asking the user about.
    private val rejected = ConcurrentHashMap<String, X509Certificate>()

    private val _connectionErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // A request to the server could not connect at all.
    val connectionErrors: SharedFlow<Unit> = _connectionErrors

    private val pins: () -> Map<String, String> = { current?.pins.orEmpty() }
    private val remember: (String, X509Certificate) -> Unit = { host, leaf -> rejected[pinKey(host)] = leaf }

    private val trustManager = PinningTrustManager(platformTrustManager(), pins, remember)
    private val keyManager = ClientCertKeyManager(context) {
        current?.let { now -> now.clientCert?.let { ClientCertChoice(it, now.hosts) } }
    }

    // Uses the settings for the server at these addresses from now on.
    fun configure(main: HttpUrl, settings: ConnectionSettings) {
        val addresses = listOfNotNull(main, settings.home)
        val before = current?.clientCert
        current = Current(
            origins = addresses.mapTo(HashSet(), ::origin),
            hosts = addresses.mapTo(HashSet()) { pinKey(it.host) },
            headers = settings.headers.associate { it.name to it.value },
            pins = settings.pins.mapKeys { pinKey(it.key) },
            clientCert = settings.clientCertAlias,
        )
        if (before != settings.clientCertAlias) keyManager.forget()
    }

    fun clear() {
        current = null
        keyManager.forget()
        rejected.clear()
    }

    // The certificate a host last showed that was not trusted, taken once.
    fun takeRejected(host: String): X509Certificate? = rejected.remove(pinKey(host))

    // Sets up a client builder to use all of the above.
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
        return builder
            .addInterceptor(watch)
            .addNetworkInterceptor(ServerHeaders { current?.let { HeaderScope(it.origins, it.headers) } })
            .sslSocketFactory(tls.socketFactory, trustManager)
            .hostnameVerifier(PinningHostnameVerifier(pins, remember))
    }

    // Notices a request to the server that could not connect, so the
    // address in use can be checked again.
    private val watch = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val server = current?.origins?.contains(origin(request.url)) == true
            if (server && isConnectionError(e)) _connectionErrors.tryEmit(Unit)
            throw e
        }
    }
}

// A failure to reach the address at all, as opposed to a cancelled call
// or a server that answered badly.
fun isConnectionError(e: IOException): Boolean = when (e) {
    is ConnectException, is NoRouteToHostException, is UnknownHostException, is SocketTimeoutException -> true
    is InterruptedIOException -> e.message == "timeout"
    else -> false
}
