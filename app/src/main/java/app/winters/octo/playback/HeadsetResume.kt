package app.winters.octo.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import app.winters.octo.player.PlayerPrefs

// How long after headphones went away the music still comes back when they
// return. Past this, connecting them is taken as something new.
const val RESUME_WINDOW_MS = 30 * 60_000L

// Headphones that can bring the music back: over a cable (a USB-C headset
// counts), or over Bluetooth.
enum class HeadsetKind { Wired, Bluetooth }

fun headsetKind(type: Int): HeadsetKind? = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> HeadsetKind.Wired
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> HeadsetKind.Bluetooth
    else -> null
}

// Whether headphones that just connected should start the music: their kind
// is switched on, nothing is playing, there is a queue, and either the music
// was paused by the output going away (a cable pulled, Bluetooth dropped) in
// the last 30 minutes, or "always play on connect" is on.
fun shouldResumeOnConnect(
    kind: HeadsetKind,
    prefs: PlayerPrefs,
    playing: Boolean,
    hasQueue: Boolean,
    lostOutputAt: Long?,
    now: Long,
): Boolean {
    val wanted = when (kind) {
        HeadsetKind.Wired -> prefs.resumeWired
        HeadsetKind.Bluetooth -> prefs.resumeBluetooth
    }
    if (!wanted || playing || !hasQueue) return false
    if (prefs.resumeAlways) return true
    return lostOutputAt != null && now - lostOutputAt in 0..RESUME_WINDOW_MS
}

// Android needs a moment to move the sound to newly connected headphones;
// Bluetooth takes longer than a cable. Playing sooner would start on the
// speaker.
private const val WIRED_SETTLE_MS = 500L
private const val BLUETOOTH_SETTLE_MS = 1_500L

// Starts the music again when headphones connect, as the settings say. It
// lives inside the playback service, so it works with the app in the
// background for as long as the service is alive. Nothing starts the service
// for it: once the phone has stopped the service (long paused, or the app
// swiped away and closed), connecting headphones does nothing.
class HeadsetResume(
    context: Context,
    private val player: Player,
    private val prefs: () -> PlayerPrefs,
) : Player.Listener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // When the music was paused by the output going away, in elapsed time.
    private var lostOutputAt: Long? = null

    // Outputs connected now, by id. Registering the callback reports every
    // device already there, so only ids not seen before count as new.
    private val known = HashSet<Int>()

    // The check waiting for the sound to move to headphones just connected.
    private var pending: Runnable? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val fresh = addedDevices.filter { it.isSink && known.add(it.id) }
            val device = fresh.firstOrNull { headsetKind(it.type) != null } ?: return
            val kind = headsetKind(device.type) ?: return
            pending?.let(handler::removeCallbacks)
            val check = Runnable { if (device.id in known) connected(kind) }
            pending = check
            handler.postDelayed(check, if (kind == HeadsetKind.Bluetooth) BLUETOOTH_SETTLE_MS else WIRED_SETTLE_MS)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { known.remove(it.id) }
        }
    }

    fun start() {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(known) { it.id }
        player.addListener(this)
        audio.registerAudioDeviceCallback(callback, handler)
    }

    fun stop() {
        audio.unregisterAudioDeviceCallback(callback)
        player.removeListener(this)
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    // Notes a pause caused by the output going away. Any other pause, or
    // playing again, forgets it.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        lostOutputAt = if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
    }

    private fun connected(kind: HeadsetKind) {
        val resume = shouldResumeOnConnect(
            kind = kind,
            prefs = prefs(),
            playing = player.playWhenReady,
            hasQueue = player.mediaItemCount > 0,
            lostOutputAt = lostOutputAt,
            now = SystemClock.elapsedRealtime(),
        )
        if (!resume) return
        Log.i("Octo", "headphones: ${kind.name.lowercase()} connected, playing again")
        lostOutputAt = null
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
}
