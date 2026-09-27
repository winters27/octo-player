package app.winters.octo.output

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.OctoPlayer
import app.winters.octo.playback.isOpenedFile
import app.winters.octo.playback.shuffleOrderOf
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The phone is kept awake in stretches this long, renewed while casting
// plays, so a lock is never left held by mistake.
private const val WAKE_MS = 30 * 60_000L

// The queue a player holds right now, for moving it to another.
fun queueOf(player: Player): QueueState<MediaItem> = QueueState(
    items = List(player.mediaItemCount, player::getMediaItemAt),
    index = player.currentMediaItemIndex,
    positionMs = player.currentPosition,
    playing = player.playWhenReady,
    repeatMode = player.repeatMode,
    shuffle = player.shuffleModeEnabled,
    order = shuffleOrderOf(player).toList(),
)

// Moves the music between the phone and a device, for the playback
// service. Going out, the queue, the song, the place in it and whether it
// was playing go with it, and the phone's player goes quiet and empty (so
// headphones reconnecting do not start it). Coming back, the phone picks
// up where the device was. While casting, the phone stays awake and on the
// Wi-Fi, since it moves through the queue and may be serving the files.
@OptIn(UnstableApi::class)
class Casting(
    context: Context,
    private val switch: OutputSwitch,
    private val local: OctoPlayer,
    private val media: DeviceMedia,
    private val feedback: Feedback,
    private val scope: CoroutineScope,
) : OutputHost {
    private var remote: RemotePlayer? = null
    private var device: OutputDevice? = null

    // Whether this cast has said once that songs are being passed over.
    private var toldOfSkips = false
    private var renew: Job? = null

    private val wake: PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "octo:casting").apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifi: WifiManager.WifiLock? = runCatching {
        context.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "octo:casting").apply { setReferenceCounted(false) }
    }.getOrNull()

    // Keeps the phone's server to the queue, and the phone awake while playing.
    private val watcher = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            remote?.let { media.keepOnly(it.entryKeys()) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = holdLocks()

        override fun onPlaybackStateChanged(playbackState: Int) = holdLocks()
    }

    override fun castTo(output: RemoteOutput, playing: Boolean?) {
        val old = remote
        val from: Player = old ?: local
        val queue = queueOf(from).let { if (playing != null) it.copy(playing = playing) else it }
        if (old == null) local.pause()
        media.begin()
        toldOfSkips = false
        device = output.device
        val player = RemotePlayer(output, media, scope, ::skipped)
        player.addListener(watcher)
        player.start(queue)
        // The session moves to the new player before the old one goes.
        switch.useRemote(player)
        remote = player
        if (old != null) {
            letGo(old)
        } else {
            // The phone's player goes quiet and empty until the music comes back.
            local.stop()
            local.clearMediaItems()
        }
        media.keepOnly(player.entryKeys())
        holdLocks()
    }

    override fun backToPhone(playing: (wasPlaying: Boolean) -> Boolean): Boolean {
        val player = remote ?: return false
        val queue = player.capture()
        if (queue.items.isNotEmpty()) {
            local.setMediaItems(queue.items, queue.index, queue.positionMs)
            local.setPlayOrder(queue.order.toIntArray())
            local.repeatMode = repeatModeOf(queue.repeatMode)
            local.shuffleModeEnabled = queue.shuffle
            local.prepare()
            local.playWhenReady = playing(queue.playing)
        }
        switch.useLocal()
        remote = null
        letGo(player)
        media.end()
        device = null
        holdLocks()
        return queue.playing
    }

    // The service is going. The session's player lets go of the device's
    // player itself; the phone just stops serving and stays awake no more.
    fun release() {
        remote?.removeListener(watcher)
        remote = null
        media.end()
        holdLocks()
    }

    private fun letGo(player: RemotePlayer) {
        player.removeListener(watcher)
        player.release()
    }

    // One quiet line per cast when songs are passed over.
    private fun skipped(item: MediaItem) {
        if (toldOfSkips) return
        toldOfSkips = true
        val name = device?.name ?: "the device"
        feedback.show(
            if (isOpenedFile(item.mediaId)) "Files opened from other apps are skipped while casting" else "Songs $name can't play are skipped",
        )
    }

    private fun holdLocks() {
        val player = remote
        val on = player != null && player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        if (on) {
            wake.acquire(WAKE_MS)
            wifi?.takeIf { !it.isHeld }?.acquire()
            if (renew?.isActive != true) {
                renew = scope.launch {
                    while (isActive) {
                        delay(WAKE_MS / 2)
                        wake.acquire(WAKE_MS)
                    }
                }
            }
        } else {
            renew?.cancel()
            renew = null
            if (wake.isHeld) wake.release()
            wifi?.takeIf { it.isHeld }?.release()
        }
    }
}

// A repeat mode as Media3 names it.
private fun repeatModeOf(value: Int): @Player.RepeatMode Int = when (value) {
    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
    else -> Player.REPEAT_MODE_OFF
}
