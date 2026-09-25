package app.winters.octo.playback

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// One engine that plays audio. Crossfade will run two of these; audio focus
// is handled above them, so each deck ignores it.
fun buildDeck(context: Context): ExoPlayer =
    ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

// Asks the phone for the right to play, and hears when a call, a
// navigation prompt or another app wants it back.
class AudioFocus(context: Context, private val onChange: (Int) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            PlatformAudioAttributes.Builder()
                .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
        .build()
    private var held = false

    fun request(): Boolean {
        if (!held) held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (held) audio.abandonAudioFocusRequest(request)
        held = false
    }
}

private const val DUCKED_VOLUME = 0.2f

// The one player the rest of the phone sees: the lock screen, the
// notification, headphones and the app all talk to this. It forwards to the
// deck that is playing, so the deck can be swapped (for crossfade) without
// anything outside noticing. It also owns audio focus.
@OptIn(UnstableApi::class)
class OctoPlayer(context: Context, initial: ExoPlayer) : ForwardingSimpleBasePlayer(initial) {
    var deck: ExoPlayer = initial
        private set

    private var resumeWhenFocusReturns = false
    private val focus: AudioFocus = AudioFocus(context) { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app is playing now; stay paused.
                resumeWhenFocusReturns = false
                pause()
                releaseFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call or a short interruption: pause, and come back after.
                resumeWhenFocusReturns = isPlaying || resumeWhenFocusReturns
                deck.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck(false)
                if (resumeWhenFocusReturns) deck.play()
                resumeWhenFocusReturns = false
            }
        }
    }

    // Playing needs focus; if the phone says no (a call is on), stay paused.
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !focus.request()) return Futures.immediateVoidFuture()
        if (!playWhenReady) resumeWhenFocusReturns = false
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        releaseFocus()
        return super.handleStop()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseFocus()
        return super.handleRelease()
    }

    private fun duck(on: Boolean) {
        deck.volume = if (on) DUCKED_VOLUME else 1f
    }

    private fun releaseFocus(): Unit = focus.abandon()
}
