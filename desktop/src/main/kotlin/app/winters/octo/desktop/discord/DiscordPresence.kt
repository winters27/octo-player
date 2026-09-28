package app.winters.octo.desktop.discord

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

// Octo's Discord application id, which the build carries
// (-Pocto.discordAppId, see desktop/build.gradle.kts), or null in a build
// without one; Discord presence is then not offered.
fun discordAppId(value: String? = System.getProperty("octo.discordAppId")): String? =
    value?.trim()?.takeIf { it.length in 15..21 && it.all(Char::isDigit) }

// Keeps the Discord status in step with what Octo wants shown, one step at
// a time: connects when there is something to show (and tries again later
// when Discord is not running), sends the status when it changes, clears it
// when there is nothing to show, and lets go when turned off. Sends at most
// one status every few seconds, as Discord asks. Nothing here waits on its
// own; the caller steps it.
class DiscordSync(
    private val appId: String,
    private val opener: PipeOpener,
    private val pid: Long = ProcessHandle.current().pid(),
    private val backoff: Backoff = Backoff(),
    private val connect: (IpcPipe) -> DiscordConnection = { DiscordConnection(it) },
) {
    private var connection: DiscordConnection? = null
    private var shown: DiscordActivity? = null
    private var nextTryAt = 0L
    private var lastSentAt: Long? = null

    val connected: Boolean get() = connection != null

    fun step(enabled: Boolean, wanted: DiscordActivity?, nowMs: Long) {
        if (!enabled) return letGo()
        if (wanted == null) {
            // Only a status that is showing needs clearing; there is no
            // reason to reach Discord just to show nothing.
            val open = connection ?: return
            if (shown == null || !maySend(nowMs)) return
            send(open, null, nowMs)
            return
        }
        val open = connection ?: reach(nowMs) ?: return
        if (wanted.looksLike(shown) || !maySend(nowMs)) return
        send(open, wanted, nowMs)
    }

    // Clears the status and closes the connection.
    fun letGo() {
        connection?.let { open ->
            if (shown != null) runCatching { open.setActivity(null, pid) }
            open.close()
        }
        connection = null
        shown = null
        nextTryAt = 0L
        backoff.reset()
    }

    private fun maySend(nowMs: Long): Boolean = lastSentAt.let { it == null || nowMs - it >= SEND_GAP_MS }

    private fun reach(nowMs: Long): DiscordConnection? {
        if (nowMs < nextTryAt) return null
        val pipe = runCatching { opener.open() }.getOrNull()
        val open = pipe?.let(connect)
        val ready = open != null && runCatching { open.handshake(appId) }.getOrDefault(false)
        if (!ready) {
            open?.close() ?: runCatching { pipe?.close() }
            nextTryAt = nowMs + backoff.next()
            return null
        }
        backoff.reset()
        connection = open
        shown = null
        // The pace Discord asks for is per connection; a new one may show
        // the song at once.
        lastSentAt = null
        return open
    }

    private fun send(open: DiscordConnection, activity: DiscordActivity?, nowMs: Long) {
        lastSentAt = nowMs
        try {
            open.setActivity(activity, pid)
            shown = activity
        } catch (e: IOException) {
            // Discord quit or closed on Octo: try again in a while.
            runCatching { open.close() }
            connection = null
            shown = null
            nextTryAt = nowMs + backoff.next()
        }
    }
}

// Discord takes about five status changes in twenty seconds.
const val SEND_GAP_MS = 4_000L

// How often the worker steps with nothing new, to retry and catch up.
private const val STEP_MS = 1_000L

// Runs a DiscordSync on a thread of its own, so the window never waits on
// Discord. `want` hands it what should show; it steps at once and then
// about every second while on.
class DiscordPresence(
    private val sync: DiscordSync,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val wanted = AtomicReference<Pair<Boolean, DiscordActivity?>>(false to null)
    private val wake = Object()
    @Volatile private var running = false
    private var thread: Thread? = null

    // Told when the connection comes and goes, on the worker's thread.
    var onConnected: (Boolean) -> Unit = {}

    fun want(enabled: Boolean, activity: DiscordActivity?) {
        wanted.set(enabled to activity)
        synchronized(wake) { wake.notifyAll() }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            var was = false
            while (running) {
                val (enabled, activity) = wanted.get()
                runCatching { sync.step(enabled, activity, clock()) }
                if (sync.connected != was) {
                    was = sync.connected
                    onConnected(was)
                }
                // Off, it sleeps until told otherwise.
                synchronized(wake) { if (running && wanted.get() == enabled to activity) wake.wait(if (enabled) STEP_MS else 0L) }
            }
            runCatching { sync.letGo() }
        }, "octo-discord").apply {
            isDaemon = true
            start()
        }
    }

    // Clears the status on the way out, waiting a moment for it.
    override fun close() {
        running = false
        synchronized(wake) { wake.notifyAll() }
        thread?.join(CLOSE_WAIT_MS)
    }
}

private const val CLOSE_WAIT_MS = 1_500L
