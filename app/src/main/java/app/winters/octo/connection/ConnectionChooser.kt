package app.winters.octo.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

// Which of the server's addresses is in use.
enum class Place { Home, Away }

// Home only when a home address is set, the phone is on a local network
// (Wi-Fi or a cable), and the home address answered just now. Anything
// else uses the main address, which works from anywhere.
fun choosePlace(hasHome: Boolean, onLocalNetwork: Boolean, homeAnswered: Boolean): Place =
    if (hasHome && onLocalNetwork && homeAnswered) Place.Home else Place.Away

// A connection error asks for a new check at most this often, so a burst
// of failed cover loads does not become a burst of pings.
private const val ERROR_RECHECK_MS = 5_000L

// Picks between the server's home and main addresses. It checks when the
// server is set, whenever the phone's network changes, and after a request
// to the server fails to connect. The client asks it where to send each
// call, so a change applies to the next one.
@Singleton
class ConnectionChooser @Inject constructor(
    @ApplicationContext context: Context,
    security: ConnectionSecurity,
) {
    // The server's two addresses, and how to ask the home one if it answers.
    private class Target(val main: HttpUrl, val home: HttpUrl, val answers: suspend (HttpUrl) -> Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val checks = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var target: Target? = null
    @Volatile private var lastErrorCheck = 0L

    private val _place = MutableStateFlow(Place.Away)
    val place: StateFlow<Place> = _place

    init {
        // One check at a time; asks that come in meanwhile fold into one more.
        scope.launch {
            while (true) {
                checks.receive()
                check()
            }
        }
        scope.launch {
            security.connectionErrors.collect {
                val now = SystemClock.elapsedRealtime()
                if (target != null && now - lastErrorCheck > ERROR_RECHECK_MS) {
                    lastErrorCheck = now
                    checks.trySend(Unit)
                }
            }
        }
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var last: Pair<Network, Boolean>? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Signal changes also land here; only a new network or kind counts.
                val now = network to isLocal(caps)
                if (now != last) {
                    last = now
                    recheck()
                }
            }

            override fun onLost(network: Network) {
                last = null
                recheck()
            }
        })
    }

    // Where calls to the server with this main address go right now.
    fun activeUrl(main: HttpUrl): HttpUrl {
        val now = target
        return if (now != null && now.main == main && _place.value == Place.Home) now.home else main
    }

    // Starts choosing for a server. With no home address the main one is
    // always used.
    fun use(main: HttpUrl, home: HttpUrl?, answers: suspend (HttpUrl) -> Boolean) {
        _place.value = Place.Away
        target = home?.takeIf { it != main }?.let { Target(main, it, answers) }
        recheck()
    }

    fun clear() {
        target = null
        _place.value = Place.Away
    }

    fun recheck() {
        if (target != null) checks.trySend(Unit)
    }

    private suspend fun check() {
        val now = target ?: return
        val local = onLocalNetwork()
        val answered = local && now.answers(now.home)
        // The server may have changed while the home address was asked.
        if (target === now) _place.value = choosePlace(hasHome = true, onLocalNetwork = local, homeAnswered = answered)
    }

    private fun onLocalNetwork(): Boolean =
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.let(::isLocal) == true

    private fun isLocal(caps: NetworkCapabilities) =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
