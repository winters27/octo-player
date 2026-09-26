package app.winters.octo.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

// Start loading the next song this long before the blend, so it is ready.
private const val LOAD_AHEAD_MS = 5_000L

// How often to check on the song: rarely while the end is far off, and
// every frame or so near it and during the blend.
private const val FAR_CHECK_MS = 1_000L
private const val NEAR_CHECK_MS = 40L

// Runs the crossfade between two decks. While a song plays it watches for
// the end. A few seconds before the blend it loads the spare deck with the
// same queue, the same shuffle order and the next song, silent and paused.
// At the blend the player hands over to the spare, which starts playing,
// while the old deck plays out its last seconds and their volumes cross.
// Anything the listener does mid-blend (skip, seek, pause, editing the
// queue) ends the blend at once.
@OptIn(UnstableApi::class)
internal class Crossfader(private val player: OctoPlayer, private var spare: ExoPlayer) : Player.Listener {
    // How long a blend is, or 0 when crossfade is off.
    var fadeMs = 0L
        set(value) {
            field = value
            interrupt()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { step() }

    // The queue position the spare is loaded with, if it is loaded.
    private var loadedFor = C.INDEX_UNSET
    private var outgoing: ExoPlayer? = null
    private var fadeStartedAt = 0L
    private var fadeLength = 0L

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            schedule(0)
        } else {
            // A pause, a call or unplugged headphones: no half-finished blend.
            finishFade()
            handler.removeCallbacks(check)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The song changed some other way, so what the spare holds is stale.
        if (outgoing == null) unloadSpare()
    }

    // Called before anything that changes what plays or where.
    fun interrupt() {
        finishFade()
        unloadSpare()
        schedule(0)
    }

    fun release() {
        handler.removeCallbacks(check)
        finishFade()
        spare.release()
    }

    private fun step() {
        if (outgoing != null) {
            val progress = (SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / fadeLength
            player.setFade(fadeInVolume(progress), fadeOutVolume(progress))
            if (progress >= 1f) finishFade() else schedule(NEAR_CHECK_MS)
            return
        }
        val deck = player.deck
        if (!deck.isPlaying) {
            // Briefly not playing while it loads after a seek: keep watching.
            // Truly paused: stop until playing starts again.
            if (deck.playWhenReady) schedule(NEAR_CHECK_MS)
            return
        }
        val next = deck.nextMediaItemIndex
        val length = blendLength(deck, next)
        if (length == 0L) {
            unloadSpare()
            schedule(FAR_CHECK_MS)
            return
        }
        // In real time: at 1.5x speed the song's last 6 seconds pass in 4.
        val remaining = ((lengthOf(deck) - deck.currentPosition) / deck.playbackParameters.speed).toLong()
        if (loadedFor != next && remaining <= length + LOAD_AHEAD_MS) load(next)
        if (loadedFor == next && remaining <= length && spare.playbackState == Player.STATE_READY) {
            // Late (after a seek near the end, say): blend over what is left.
            val blend = remaining.coerceAtMost(length)
            if (blend >= SHORTEST_FADE_MS) {
                start(blend)
                schedule(NEAR_CHECK_MS)
                return
            }
        }
        // If the spare is not ready in time, the song simply ends as usual.
        schedule(if (remaining > length + LOAD_AHEAD_MS + FAR_CHECK_MS) FAR_CHECK_MS else NEAR_CHECK_MS)
    }

    private fun blendLength(deck: ExoPlayer, next: Int): Long {
        if (next == C.INDEX_UNSET) return 0
        val current = deck.currentMediaItem ?: return 0
        return crossfadeLength(
            current = current.fadeSong(lengthOf(deck)),
            next = deck.getMediaItemAt(next).let { it.fadeSong(it.mediaMetadata.durationMs ?: 0) },
            fadeMs = fadeMs,
            repeatOne = deck.repeatMode == Player.REPEAT_MODE_ONE,
            stopAtEndOfSong = deck.pauseAtEndOfMediaItems,
        )
    }

    // How long the song is: what the deck measured, or the library's length
    // for a stream that has not said.
    private fun lengthOf(deck: ExoPlayer): Long =
        deck.duration.takeIf { it != C.TIME_UNSET } ?: deck.currentMediaItem?.mediaMetadata?.durationMs ?: 0

    // The spare gets the same songs in the same shuffle order, parked
    // silently at the start of the next song.
    private fun load(next: Int) {
        val deck = player.deck
        spare.setMediaItems(List(deck.mediaItemCount, deck::getMediaItemAt), next, 0)
        spare.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffleOrderOf(deck), System.nanoTime()))
        spare.shuffleModeEnabled = deck.shuffleModeEnabled
        spare.repeatMode = deck.repeatMode
        // The same pace, so the blend does not jump in speed or pitch.
        spare.playbackParameters = deck.playbackParameters
        spare.skipSilenceEnabled = deck.skipSilenceEnabled
        spare.volume = 0f
        spare.playWhenReady = false
        spare.prepare()
        loadedFor = next
    }

    private fun start(length: Long) {
        val from = player.deck
        val into = spare
        // The old deck plays this song out, then stops instead of going on.
        from.pauseAtEndOfMediaItems = true
        outgoing = from
        fadeStartedAt = SystemClock.elapsedRealtime()
        fadeLength = length
        // Once the blend is over, the old deck is the spare for next time.
        spare = from
        loadedFor = C.INDEX_UNSET
        Log.i("Octo", "crossfade: $length ms into ${into.currentMediaItem?.mediaId}")
        player.handOver(into, from)
    }

    private fun finishFade() {
        val from = outgoing ?: return
        outgoing = null
        val progress = (SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / fadeLength
        if (progress < 1f) Log.i("Octo", "crossfade: cut short at ${(progress * 100).toInt()}%")
        from.stop()
        from.clearMediaItems()
        from.pauseAtEndOfMediaItems = false
        player.endFade()
    }

    private fun unloadSpare() {
        if (loadedFor == C.INDEX_UNSET) return
        loadedFor = C.INDEX_UNSET
        spare.stop()
        spare.clearMediaItems()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(check)
        handler.postDelayed(check, delayMs)
    }
}

// The play order the deck is using right now, shuffle included.
internal fun shuffleOrderOf(player: Player): IntArray {
    val timeline = player.currentTimeline
    return buildList {
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            add(i)
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
    }.toIntArray()
}

private fun MediaItem.fadeSong(durationMs: Long): FadeSong {
    val extras = mediaMetadata.extras
    return FadeSong(
        albumId = extras?.getString(EXTRA_ALBUM_ID),
        albumOrder = extras?.getInt(EXTRA_ALBUM_ORDER, -1)?.takeIf { it >= 0 },
        durationMs = durationMs,
    )
}
