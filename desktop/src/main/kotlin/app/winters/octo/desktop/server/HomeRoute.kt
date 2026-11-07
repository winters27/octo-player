package app.winters.octo.desktop.server

import app.winters.octo.connection.Place
import app.winters.octo.connection.choosePlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.net.NetworkInterface

// How often the computer's own addresses are looked at as a safety net when
// Windows tells of every change (a missed notice is caught by the next look).
private const val NETWORK_FALLBACK_MS = 5 * 60_000L

// How often they are looked at when the system cannot tell of changes.
// Listing the network interfaces costs tens of milliseconds, so it is not
// done often.
private const val NETWORK_POLL_MS = 60_000L

// A connection error asks for a new check at most this often, so a burst
// of failed cover loads does not become a burst of pings.
private const val ERROR_RECHECK_MS = 5_000L

// Picks between the server's home and main addresses, as the phone does.
// A computer is always on some local network, so the home address is used
// whenever it answers, and the main one otherwise. It checks when made,
// when the computer joins another network, and after a request to the
// server fails to connect. A join is heard from the system's own address
// change notice when it has one, with a rare look as a safety net, and found
// by a look every minute otherwise. The client asks it where each call goes;
// `moved` hears each move between the two, and `closing` (the client its
// checks go through) is closed with it.
class HomeRoute(
    val main: HttpUrl,
    val home: HttpUrl,
    private val answers: suspend (HttpUrl) -> Boolean,
    private val network: () -> Set<String> = ::localAddresses,
    private val changes: AddressChanges = systemAddressChanges(),
    private val watchEveryMs: Long? = null,
    private val moved: () -> Unit = {},
    private val closing: AutoCloseable? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = Channel<Unit>(Channel.CONFLATED)
    private val checksNetwork = Channel<Unit>(Channel.CONFLATED)
    private val watcher: AutoCloseable? = runCatching { changes.watch { checksNetwork.trySend(Unit) } }.getOrNull()

    @Volatile var place: Place = Place.Away
        private set

    @Volatile private var lastErrorCheck = 0L

    init {
        // One check at a time; asks that come in meanwhile fold into one more.
        scope.launch {
            while (true) {
                checks.receive()
                val next = choosePlace(hasHome = true, onLocalNetwork = true, homeAnswered = answers(home))
                if (next != place) {
                    place = next
                    moved()
                }
            }
        }
        val interval = watchEveryMs ?: if (watcher != null) NETWORK_FALLBACK_MS else NETWORK_POLL_MS
        scope.launch {
            var last = network()
            recheck()
            while (true) {
                withTimeoutOrNull(interval) { checksNetwork.receive() }
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
        runCatching { watcher?.close() }
        scope.cancel()
        runCatching { closing?.close() }
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
