package app.winters.octo.output.dlna

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.winters.octo.output.OutputDevice
import app.winters.octo.output.RemoteMedia
import app.winters.octo.output.RemoteOutput
import app.winters.octo.output.RemoteState
import app.winters.octo.output.RemoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// How often a renderer is asked what it is doing: every second while it
// plays, less often while it waits.
private const val POLL_PLAYING_MS = 1_000L
private const val POLL_WAITING_MS = 2_500L

// Unanswered questions in a row before the renderer counts as gone.
private const val LOST_AFTER = 6

// UPnP's numbers for "no such action".
private val NO_ACTION = setOf(401, 602)

private const val INSTANCE = "InstanceID"

// A media renderer music is playing on. Requests go one at a time, in
// order; it is asked what it is doing about once a second while playing.
// It has no queue of its own beyond the next song, so the phone moves it
// through the queue.
class RendererOutput(
    override val device: OutputDevice,
    private val renderer: RendererDescription,
    private val client: UpnpClient,
    // What it plays, from its own list; empty when it did not say.
    private val sinks: List<String>,
    takesNext: Boolean,
    private val maxVolume: Int,
    // Told when the renderer stops answering.
    private val onLost: (RendererOutput) -> Unit,
) : RemoteOutput {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // One request at a time, in the order they were made.
    private val serial = Dispatchers.IO.limitedParallelism(1)

    @Volatile override var takesNext: Boolean = takesNext
        private set

    @Volatile private var listener: RemoteOutput.Listener? = null
    @Volatile private var closed = false

    // What the phone asked for, read on the request thread.
    private var wantPlaying = false
    private var stopAsked = false
    private var sawPlaying = false
    private var pendingSeek: Long? = null
    private var lastState = ""
    private var failures = 0

    init {
        scope.launch {
            var round = 0
            while (isActive && !closed) {
                val busy = withContext(serial) { pollOnce(round++) }
                delay(if (busy) POLL_PLAYING_MS else POLL_WAITING_MS)
            }
        }
    }

    override fun accepts(mimeType: String): Boolean = sinkAccepts(sinks, mimeType)

    override fun load(media: RemoteMedia, startMs: Long, play: Boolean, next: RemoteMedia?) = send {
        wantPlaying = play
        stopAsked = false
        sawPlaying = false
        val metadata = didlLite(media.toDidl())
        val args = listOf(INSTANCE to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to metadata)
        try {
            transport("SetAVTransportURI", args)
        } catch (e: UpnpError) {
            // Some renderers take a new song only once stopped.
            runCatching { transport("Stop", listOf(INSTANCE to "0")) }
            transport("SetAVTransportURI", args)
        }
        if (next != null) giveNext(next)
        // Renderers seek only once playing; until then the place waits.
        pendingSeek = startMs.takeIf { it >= 1_000 && media.seekable }
        if (play) transport("Play", listOf(INSTANCE to "0", "Speed" to "1"))
    }

    override fun setNext(next: RemoteMedia?) = send { giveNext(next) }

    override fun play() = send {
        wantPlaying = true
        stopAsked = false
        transport("Play", listOf(INSTANCE to "0", "Speed" to "1"))
    }

    override fun pause() = send {
        wantPlaying = false
        transport("Pause", listOf(INSTANCE to "0"))
    }

    override fun seek(positionMs: Long) = send {
        if (lastState == "PLAYING" || lastState == "PAUSED_PLAYBACK") {
            seekTo(positionMs)
        } else {
            pendingSeek = positionMs
        }
    }

    override fun stop() = send {
        wantPlaying = false
        stopAsked = true
        pendingSeek = null
        transport("Stop", listOf(INSTANCE to "0"))
    }

    override fun setVolume(level: Float) = send {
        val rendering = renderer.rendering ?: return@send
        val value = Math.round(level.coerceIn(0f, 1f) * maxVolume)
        client.call(rendering, "SetVolume", listOf(INSTANCE to "0", "Channel" to "Master", "DesiredVolume" to "$value"))
    }

    override fun setMuted(muted: Boolean) = send {
        val rendering = renderer.rendering ?: return@send
        client.call(rendering, "SetMute", listOf(INSTANCE to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"))
    }

    override fun listen(listener: RemoteOutput.Listener?) {
        this.listener = listener
    }

    override fun close(stopPlaying: Boolean) {
        if (closed) return
        closed = true
        listener = null
        scope.cancel()
        if (stopPlaying) {
            // Its own short-lived job, since this one's scope is gone.
            CoroutineScope(serial).launch { runCatching { transport("Stop", listOf(INSTANCE to "0")) } }
        }
    }

    // Sends one request in order. A song the renderer refuses is reported;
    // anything else that goes wrong shows up in the next poll.
    private fun send(block: () -> Unit) {
        if (closed) return
        scope.launch(serial) {
            try {
                block()
            } catch (e: UpnpError) {
                Log.i("Octo", "renderer: ${e.message}")
                if (e.message.orEmpty().startsWith("SetAVTransportURI")) tell { it.onFailed(e.message.orEmpty()) }
            } catch (e: IOException) {
                Log.i("Octo", "renderer: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun giveNext(next: RemoteMedia?) {
        if (!takesNext) return
        try {
            transport(
                "SetNextAVTransportURI",
                listOf(INSTANCE to "0", "NextURI" to next?.url.orEmpty(), "NextURIMetaData" to (next?.let { didlLite(it.toDidl()) } ?: "")),
            )
        } catch (e: UpnpError) {
            // Without it, the phone starts each song itself.
            if (e.code in NO_ACTION) takesNext = false
        }
    }

    private fun seekTo(positionMs: Long) {
        try {
            transport("Seek", listOf(INSTANCE to "0", "Unit" to "REL_TIME", "Target" to formatUpnpTime(positionMs)))
        } catch (e: UpnpError) {
            // A renderer that cannot seek in this song plays on from where it is.
        }
    }

    // Asks what it is doing and passes it on. Answers whether it is busy
    // (playing or loading), which sets how soon to ask again.
    private fun pollOnce(round: Int): Boolean {
        if (closed) return false
        return try {
            val state = transport("GetTransportInfo", listOf(INSTANCE to "0"))["CurrentTransportState"].orEmpty()
            val position = transport("GetPositionInfo", listOf(INSTANCE to "0"))
            failures = 0
            lastState = state
            if (state == "PLAYING") {
                sawPlaying = true
                pendingSeek?.let { at ->
                    pendingSeek = null
                    seekTo(at)
                }
            }
            val now = when (state) {
                "PLAYING" -> RemoteState.Playing
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteState.Paused
                "STOPPED", "NO_MEDIA_PRESENT" -> when {
                    sawPlaying && !stopAsked -> RemoteState.Ended
                    wantPlaying && !stopAsked -> RemoteState.Loading
                    else -> RemoteState.Idle
                }
                else -> RemoteState.Loading
            }
            // While a place waits to be sought to, or the renderer is between
            // songs, what it says of the place is not the song's.
            val trusted = pendingSeek == null && state != "TRANSITIONING"
            val status = RemoteStatus(
                state = now,
                positionMs = if (trusted) parseUpnpTime(position["RelTime"]) else null,
                durationMs = parseUpnpTime(position["TrackDuration"]),
                url = position["TrackURI"]?.takeIf { it.isNotBlank() && it != "NOT_IMPLEMENTED" },
            )
            tell { it.onStatus(status) }
            if (round % 5 == 0) readVolume()
            now == RemoteState.Playing || now == RemoteState.Loading
        } catch (e: IOException) {
            if (++failures == LOST_AFTER) main.post { if (!closed) onLost(this) }
            false
        }
    }

    private fun readVolume() {
        val rendering = renderer.rendering ?: return
        runCatching {
            val volume = client.call(rendering, "GetVolume", listOf(INSTANCE to "0", "Channel" to "Master"))["CurrentVolume"]?.toIntOrNull() ?: return
            val muted = runCatching {
                client.call(rendering, "GetMute", listOf(INSTANCE to "0", "Channel" to "Master"))["CurrentMute"]
            }.getOrNull().let { it == "1" || it.equals("true", ignoreCase = true) }
            tell { it.onVolume(volume.toFloat() / maxVolume, muted) }
        }
    }

    private fun transport(action: String, args: List<Pair<String, String>>): Map<String, String> =
        client.call(renderer.transport, action, args)

    private fun tell(what: (RemoteOutput.Listener) -> Unit) {
        main.post { listener?.let(what) }
    }
}

private fun RemoteMedia.toDidl() = DidlTrack(
    url = url,
    mimeType = mimeType,
    title = title,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    durationMs = durationMs,
    seekable = seekable,
    live = live,
)
