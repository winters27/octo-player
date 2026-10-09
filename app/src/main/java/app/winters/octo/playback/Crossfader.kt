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
        // In real time: at 1.5x speed the song's last 6 seconds pass in 4.
        val remaining = ((lengthOf(deck) - deck.currentPosition) / deck.playbackParameters.speed).toLong()
        val songs = songsFor(deck, next)
        val length = songs?.let { (current, nextSong) -> blendLength(deck, current, nextSong) } ?: 0L
        if (length == 0L) {
            if (songs != null && waitsOnLength(songs.first, songs.second, fadeMs, deck.repeatMode == Player.REPEAT_MODE_ONE, deck.pauseAtEndOfMediaItems)) {
                // The next song's length is only known once it has opened, so
                // open it early on the spare and decide once it says.
                val lead = loadLeadMs(0, fadeMs, lengthKnown = false)
                if (loadedFor != next && remaining <= lead) load(next)
                schedule(if (remaining > lead + FAR_CHECK_MS) FAR_CHECK_MS else NEAR_CHECK_MS)
                return
            }
            unloadSpare()
            schedule(FAR_CHECK_MS)
            return
        }
        val lead = loadLeadMs(length, fadeMs, lengthKnown = listedLengthOf(deck, next) > 0)
        if (loadedFor != next && remaining <= lead) load(next)
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
        schedule(if (remaining > lead + FAR_CHECK_MS) FAR_CHECK_MS else NEAR_CHECK_MS)
    }

    // The playing song and the next one as the crossfade sees them, the
    // next one's length taken from the spare once it has opened the song
    // when the list does not know it. Null with nothing next.
    private fun songsFor(deck: ExoPlayer, next: Int): Pair<FadeSong, FadeSong>? {
        if (next == C.INDEX_UNSET) return null
        val current = deck.currentMediaItem ?: return null
        val spareMs = if (loadedFor == next && spare.playbackState == Player.STATE_READY) {
            spare.duration.takeIf { it != C.TIME_UNSET }
        } else {
            null
        }
        val nextLength = nextSongLengthMs(listedLengthOf(deck, next), spareMs)
        return current.fadeSong(lengthOf(deck)) to deck.getMediaItemAt(next).fadeSong(nextLength)
    }

    private fun listedLengthOf(deck: ExoPlayer, index: Int): Long = deck.getMediaItemAt(index).mediaMetadata.durationMs ?: 0

    private fun blendLength(deck: ExoPlayer, current: FadeSong, next: FadeSong): Long =
        crossfadeLength(
            current = current,
            next = next,
            fadeMs = fadeMs,
            repeatOne = deck.repeatMode == Player.REPEAT_MODE_ONE,
            stopAtEndOfSong = deck.pauseAtEndOfMediaItems,
        )

    // How long the song is: what the deck measured, or the library's length
    // for a stream that has not said.
    private fun lengthOf(deck: ExoPlayer): Long =
        deck.duration.takeIf { it != C.TIME_UNSET } ?: deck.currentMediaItem?.mediaMetadata?.durationMs ?: 0

    // The spare gets the same songs in the same shuffle order, parked
    // silently at the start of the next song.
    private fun load(next: Int) {
        val deck = player.deck
        spare.setMediaItems(List(deck.mediaItemCount, deck::getMediaItemAt), next, 0)
        // The same kind of order too, so songs added after the blend still
        // land where they are asked for.
        spare.setShuffleOrder(QueueShuffleOrder(shuffleOrderOf(deck)))
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
