package app.winters.octo.desktop.system

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The calls the system library (desktop/system-shim) offers. Strings go
// over as UTF-8.
@Suppress("FunctionName")
internal interface SystemLibrary : Library {
    fun octo_system_version(): Int

    fun octo_system_start(callback: EventCallback?): Int

    fun octo_system_set_track(title: String, artist: String, album: String, albumArtist: String, durationMs: Long, art: ByteArray?, artLength: Long): Int

    fun octo_system_set_playback(status: Int, positionMs: Long, canPrevious: Int, canNext: Int): Int

    // Version 2 on: the same, with how fast the place moves on.
    fun octo_system_set_playback_at_rate(status: Int, positionMs: Long, canPrevious: Int, canNext: Int, rate: Double): Int

    fun octo_system_clear(): Int

    fun octo_system_stop()

    fun octo_system_describe_sessions(buffer: ByteArray?, capacity: Long): Long
}

// What the library calls back with.
internal fun interface EventCallback : Callback {
    fun invoke(kind: Int, value: Long)
}

// The library's event numbers as events.
fun systemEventOf(kind: Int, value: Long): SystemEvent? = when (kind) {
    1 -> SystemEvent.Play
    2 -> SystemEvent.Pause
    3 -> SystemEvent.Toggle
    4 -> SystemEvent.Next
    5 -> SystemEvent.Previous
    6 -> SystemEvent.Stop
    7 -> SystemEvent.SeekTo(value.coerceAtLeast(0))
    8 -> SystemEvent.Sleep
    9 -> SystemEvent.Wake
    else -> null
}

// Loads the system library, or answers null when it is not there (Linux,
// or a build made without it).
internal fun loadSystemLibrary(): SystemLibrary? = try {
    Native.load("octo_system", SystemLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        .takeIf { it.octo_system_version() >= 1 }
} catch (e: UnsatisfiedLinkError) {
    null
} catch (e: RuntimeException) {
    null
}

// The media controls on Windows and macOS, through the system library. All
// its calls run in order on one thread of their own, so the player and the
// window never wait on the system.
class NativeMediaControls internal constructor(
    private val library: SystemLibrary,
    override val label: String,
) : SystemMediaControls {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "octo-system").apply { isDaemon = true }
    }

    // Held here so the JVM never frees it while the library can call it.
    private var callback: EventCallback? = null

    // Whether the library takes a rate, which an older one does not.
    private val takesRate = runCatching { library.octo_system_version() >= 2 }.getOrDefault(false)

    override fun start(events: (SystemEvent) -> Unit): Boolean {
        val listener = EventCallback { kind, value -> systemEventOf(kind, value)?.let(events) }
        callback = listener
        return runCatching { worker.submit<Int> { library.octo_system_start(listener) }.get(5, TimeUnit.SECONDS) == 0 }
            .getOrDefault(false)
    }

    override fun showTrack(now: NowPlaying, art: CoverArt?) {
        val track = nativeTrackOf(now)
        val bytes = art?.bytes
        later {
            library.octo_system_set_track(track.title, track.artist, track.album, track.albumArtist, track.durationMs, bytes, bytes?.size?.toLong() ?: 0)
        }
    }

    override fun showPlayback(now: NowPlaying, positionMs: Long, jumped: Boolean) {
        val playback = nativePlaybackOf(now, positionMs)
        val previous = if (playback.canPrevious) 1 else 0
        val next = if (playback.canNext) 1 else 0
        later {
            if (takesRate) {
                library.octo_system_set_playback_at_rate(playback.status.code, playback.positionMs, previous, next, playback.rate)
            } else {
                library.octo_system_set_playback(playback.status.code, playback.positionMs, previous, next)
            }
        }
    }

    override fun clear() {
        later { library.octo_system_clear() }
    }

    // What the system itself shows as playing, read back from it (Windows
    // only), for checking by hand. One line per app, fields split by tabs.
    fun describeSessions(): String? = runCatching {
        worker.submit<String?> {
            val size = library.octo_system_describe_sessions(null, 0)
            if (size < 0) return@submit null
            val buffer = ByteArray(size.toInt())
            library.octo_system_describe_sessions(buffer, buffer.size.toLong())
            buffer.decodeToString()
        }.get(10, TimeUnit.SECONDS)
    }.getOrNull()

    override fun close() {
        later { library.octo_system_stop() }
        worker.shutdown()
        // Octo is quitting, so the system gets a moment to let go, no more.
        runCatching { worker.awaitTermination(QUIT_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    private fun later(call: () -> Unit) {
        if (worker.isShutdown) return
        runCatching { worker.execute { runCatching(call) } }
    }

    companion object {
        // The controls for this system, when its library loads.
        fun load(label: String): NativeMediaControls? = loadSystemLibrary()?.let { NativeMediaControls(it, label) }
    }
}
