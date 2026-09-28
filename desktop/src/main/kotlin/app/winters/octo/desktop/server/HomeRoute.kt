package app.winters.octo.desktop.server

import app.winters.octo.connection.Place
import app.winters.octo.connection.choosePlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.net.NetworkInterface

// How often the computer's own addresses are looked at, to notice it
// joining another network.
private const val NETWORK_WATCH_MS = 10_000L

// A connection error asks for a new check at most this often, so a burst
// of failed cover loads does not become a burst of pings.
private const val ERROR_RECHECK_MS = 5_000L

// Picks between the server's home and main addresses, as the phone does.
// A computer is always on some local network, so the home address is used
// whenever it answers, and the main one otherwise. It checks when made,
// when the computer joins another network, and after a request to the
// server fails to connect. The client asks it where each call goes.
class HomeRoute(
    val main: HttpUrl,
    val home: HttpUrl,
    private val answers: suspend (HttpUrl) -> Boolean,
    private val network: () -> Set<String> = ::localAddresses,
    private val watchEveryMs: Long = NETWORK_WATCH_MS,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = Channel<Unit>(Channel.CONFLATED)

    @Volatile var place: Place = Place.Away
        private set

    @Volatile private var lastErrorCheck = 0L

    init {
        // One check at a time; asks that come in meanwhile fold into one more.
        scope.launch {
            while (true) {
                checks.receive()
                place = choosePlace(hasHome = true, onLocalNetwork = true, homeAnswered = answers(home))
            }
        }
        scope.launch {
            var last = network()
            recheck()
            while (true) {
                delay(watchEveryMs)
                val now = network()
                if (now != last) {
                    last = now
                    recheck()
                }
            }
        }
    }

    // Where calls go right now.
    fun activeUrl(): HttpUrl = if (place == Place.Home) home else main

    fun recheck() {
        checks.trySend(Unit)
    }

    // A request could not connect: the address in use may be gone.
    fun connectionFailed() {
        val now = System.currentTimeMillis()
        if (now - lastErrorCheck > ERROR_RECHECK_MS) {
            lastErrorCheck = now
            recheck()
        }
    }

    override fun close() {
        scope.cancel()
    }
}

// The computer's own addresses on its networks, which change when it
// joins another one.
fun localAddresses(): Set<String> = runCatching {
    NetworkInterface.networkInterfaces().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses() }
        .map { it.hostAddress }
        .toList()
        .toSet()
}.getOrDefault(emptySet())
