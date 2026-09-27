package app.winters.octo.output

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.EditableQueue
import app.winters.octo.playback.entryId
import app.winters.octo.playback.isAutoplay
import app.winters.octo.playback.withEntry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// How a device's volume is shown to Android: in 20 steps, a press each.
const val VOLUME_STEPS = 20

// A device may take a moment to show what it was just told; until then
// its reports of playing or paused, and of where it is, are not believed.
private const val SETTLE_MS = 3_000L

// Songs a device could not play, one after another, before it stops trying.
private const val MAX_SKIPS = 8

// Turns queue songs into what a device fetches.
interface MediaForDevice {
    // Whether a song can play on the device at all, known at once.
    fun canPlay(item: MediaItem, output: RemoteOutput): Boolean

    // The song as the device gets it, or null when it cannot have it.
    suspend fun make(item: MediaItem, output: RemoteOutput): RemoteMedia?
}

// The player while music plays on another device. It looks like any
// player to the media session, so the notification, lock screen, widgets,
// the app and a car all keep working. The phone keeps the queue and moves
// through it; the device plays one song at a time (plus the next, when it
// takes it). Speed, the equalizer, crossfade and loudness levelling belong
// to the phone's own player and do not apply here.
@OptIn(UnstableApi::class)
class RemotePlayer(
    private val output: RemoteOutput,
    private val media: MediaForDevice,
    private val scope: CoroutineScope,
    // Told when a song is passed over because the device cannot play it.
    private val onSkipped: (MediaItem) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()), EditableQueue, RemoteOutput.Listener {
    private var queue = RemoteQueue(QueueState<MediaItem>(emptyList(), 0, 0, false)) { it.isAutoplay }
    private var wantsToPlay = false
    private var state = Player.STATE_IDLE
    private var error: PlaybackException? = null

    // Where in the song, as of `anchorAt`, moving on from there while playing.
    private var anchorMs = 0L
    private var anchorAt = 0L
    private var ticking = false
    private var deviceDurationMs: Long? = null

    private var volume = VOLUME_STEPS / 2
    private var muted = false

    // What the device holds: the song on (by queue entry) and its address,
    // and the one given to it ahead of time.
    private var loadedKey: String? = null
    private var loadedUrl: String? = null
    private var nextKey: String? = null
    private var nextUrl: String? = null
    private var loadJob: Job? = null
    private var loadingKey: String? = null
    private var nextJob: Job? = null

    // The song changed because the last one ended: told once, then cleared.
    private var endedIntoNext = false

    // When the phone last told the device to play, pause or seek.
    private var commandAt = 0L
    private var seekAt = 0L
    private var skipsInARow = 0

    // The next queue entry id to give out, for songs added while casting.
    private var nextEntry = 0L
    private var playingNext = false

    // Stops at the end of the song instead of going on, for the sleep timer.
    var pauseAtEndOfSong = false
        set(value) {
            if (field == value) return
            field = value
            refreshNext()
        }

    val isPlayingOnDevice: Boolean get() = wantsToPlay && state == Player.STATE_READY

    // Takes over the queue as the phone left it, and starts the device on
    // the current song at the same place, playing or paused as it was.
    fun start(from: QueueState<MediaItem>) {
        queue = RemoteQueue(from.copy(items = stamped(from.items)), MediaItem::isAutoplay)
        wantsToPlay = from.playing
        anchor(from.positionMs)
        output.listen(this)
        if (queue.size > 0) loadCurrent(from.positionMs)
        invalidateState()
    }

    // The queue as it stands, for handing back to the phone.
    fun capture(): QueueState<MediaItem> = queue.state(positionNow(), wantsToPlay)

    // The queue entries now in the queue, so the phone serves only those.
    fun entryKeys(): Set<String> = queue.items.mapTo(HashSet()) { keyOf(it) }

    override fun getState(): State {
        val items = queue.items
        val durations = items.mapIndexed { i, item ->
            if (i == queue.index) deviceDurationMs ?: item.mediaMetadata.durationMs else item.mediaMetadata.durationMs
        }
        val shown = when {
            items.isEmpty() && state != Player.STATE_IDLE -> Player.STATE_ENDED
            error != null -> Player.STATE_IDLE
            else -> state
        }
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(shown)
            .setPlayerError(error)
            .setRepeatMode(queue.repeatMode)
            .setShuffleModeEnabled(queue.shuffle)
            .setDeviceInfo(REMOTE_DEVICE)
            .setDeviceVolume(volume)
            .setIsDeviceMuted(muted)
            .setPlaylist(QueueTimeline(items, items.map(::keyOf), durations, queue.order), Tracks.EMPTY, null)
            .setContentPositionMs(positionNow())
        if (items.isNotEmpty()) builder.setCurrentMediaItemIndex(queue.index)
        if (endedIntoNext) {
            endedIntoNext = false
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, anchorMs)
        }
        return builder.build()
    }

    // ---- What the listener asks for ----

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        commandAt = SystemClock.elapsedRealtime()
        if (playWhenReady) {
            when {
                state == Player.STATE_IDLE || state == Player.STATE_ENDED -> Unit
                // On its way to the device: it goes as playing now.
                loadJob?.isActive == true && loadingKey == queue.current?.let(::keyOf) -> Unit
                loadedKey == null || loadedKey != queue.current?.let(::keyOf) -> loadCurrent(anchorMs)
                else -> {
                    output.play()
                    anchor(positionNow(), moving = state == Player.STATE_READY)
                }
            }
        } else {
            anchor(positionNow())
            if (loadedKey != null) output.pause()
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        error = null
        skipsInARow = 0
        if (queue.size > 0 && state == Player.STATE_IDLE) loadCurrent(anchorMs)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        cancelLoads()
        output.stop()
        forgetLoaded()
        state = Player.STATE_IDLE
        anchor(positionNow())
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        cancelLoads()
        output.listen(null)
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        queue.repeatMode = repeatMode
        refreshNext()
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        queue.shuffle = shuffleModeEnabled
        refreshNext()
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val level = deviceVolume.coerceIn(0, VOLUME_STEPS)
        // A slider sends the same step many times while it is dragged.
        if (level == volume) return done()
        volume = level
        output.setVolume(volume.toFloat() / VOLUME_STEPS)
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume + 1, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume - 1, flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        this.muted = muted
        output.setMuted(muted)
        return done()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        queue.replaceAll(stamped(mediaItems), startIndex.takeIf { it != C.INDEX_UNSET })
        forgetLoaded()
        val start = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0
        anchor(start)
        when {
            queue.size == 0 -> {
                cancelLoads()
                output.stop()
                if (state != Player.STATE_IDLE) state = Player.STATE_ENDED
            }
            // Not yet prepared, as a new queue usually is: it loads on prepare.
            state == Player.STATE_IDLE -> Unit
            else -> loadCurrent(start)
        }
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val wasEmpty = queue.size == 0
        queue.add(index, stamped(mediaItems), playNext = playingNext)
        if (wasEmpty && state == Player.STATE_ENDED) state = Player.STATE_IDLE
        refreshNext()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        when (queue.remove(fromIndex, toIndex)) {
            Removal.Kept -> refreshNext()
            Removal.MovedOn -> {
                forgetLoaded()
                anchor(0)
                if (state != Player.STATE_IDLE) loadCurrent(0)
            }
            Removal.Ended -> {
                cancelLoads()
                output.stop()
                forgetLoaded()
                anchor(0)
                if (state != Player.STATE_IDLE) state = Player.STATE_ENDED
            }
        }
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        queue.move(fromIndex, toIndex, newIndex)
        refreshNext()
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val current = queue.current
        val onIt = queue.index in fromIndex until toIndex
        queue.replace(fromIndex, toIndex, stamped(mediaItems))
        // A new copy of the song that is on (a download finished, say)
        // plays on from where it is; the same address needs nothing.
        val now = queue.current
        if (onIt && now != null && now.localConfiguration?.uri != current?.localConfiguration?.uri) {
            forgetLoaded()
            if (state != Player.STATE_IDLE) loadCurrent(positionNow())
        } else {
            refreshNext()
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (queue.size == 0) return done()
        val position = positionMs.takeIf { it != C.TIME_UNSET } ?: 0
        val index = if (mediaItemIndex == C.INDEX_UNSET) queue.index else mediaItemIndex
        skipsInARow = 0
        if (index != queue.index || loadedKey == null || state == Player.STATE_ENDED) {
            queue.moveTo(index)
            forgetLoaded()
            anchor(position)
            if (state != Player.STATE_IDLE) loadCurrent(position)
        } else {
            seekAt = SystemClock.elapsedRealtime()
            output.seek(position)
            anchor(position, moving = ticking)
        }
        return done()
    }

    // ---- Queue edits the app offers (see QueueEditor) ----

    override fun addNext(items: List<MediaItem>) {
        if (items.isEmpty()) return
        playingNext = true
        try {
            addMediaItems((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), items)
        } finally {
            playingNext = false
        }
    }

    override fun setPlayOrder(order: IntArray) {
        queue.setOrder(order.toList())
        refreshNext()
        invalidateState()
    }

    // Exactly where they were, entry ids and all, for an undo.
    override fun putBack(runs: List<Pair<Int, List<MediaItem>>>, order: IntArray) {
        runs.forEach { (at, items) -> queue.insertAt(at, stamped(items)) }
        setPlayOrder(order)
    }

    override fun removeRuns(runs: List<IntRange>) {
        runs.forEach { removeMediaItems(it.first, it.last + 1) }
    }

    // ---- What the device says ----

    override fun onStatus(status: RemoteStatus) {
        val now = SystemClock.elapsedRealtime()
        val settling = now - commandAt < SETTLE_MS
        status.durationMs?.takeIf { it > 0 }?.let { deviceDurationMs = it }
        // The device moved on to the song it was given ahead of time.
        val next = nextUrl
        if (next != null && status.url == next && next != loadedUrl) {
            movedOnByItself()
        }
        when (status.state) {
            RemoteState.Loading -> if (loadedKey != null && state != Player.STATE_ENDED) state = Player.STATE_BUFFERING
            RemoteState.Playing -> {
                state = Player.STATE_READY
                error = null
                skipsInARow = 0
                // Played from the device's own remote control.
                if (!wantsToPlay && !settling) wantsToPlay = true
            }
            RemoteState.Paused -> {
                if (loadedKey != null) state = Player.STATE_READY
                if (wantsToPlay && !settling) wantsToPlay = false
            }
            RemoteState.Ended -> if (loadedKey != null && !settling) songEnded()
            RemoteState.Idle -> if (loadedKey != null && !settling) {
                // Stopped from somewhere else: it can be played again from here.
                wantsToPlay = false
                forgetLoaded()
                state = Player.STATE_READY
            }
        }
        val moving = status.state == RemoteState.Playing && wantsToPlay
        val reported = status.positionMs?.takeIf { now - seekAt >= SETTLE_MS && status.state != RemoteState.Loading }
        anchor(reported ?: positionNow(), moving)
        invalidateState()
    }

    override fun onVolume(level: Float, muted: Boolean) {
        volume = Math.round(level.coerceIn(0f, 1f) * VOLUME_STEPS)
        this.muted = muted
        invalidateState()
    }

    override fun onFailed(message: String) {
        Log.i("Octo", "cast: the device could not play a song: $message")
        forgetLoaded()
        error = PlaybackException(message, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        state = Player.STATE_IDLE
        ticking = false
        invalidateState()
    }

    // ---- Moving through the queue ----

    // The song ended on the device: the next one plays, or the music stops
    // at the end of the queue, or pauses there for the sleep timer.
    private fun songEnded() {
        forgetLoaded()
        val next = queue.next()
        if (next == null) {
            state = Player.STATE_ENDED
            anchor(deviceDurationMs ?: positionNow())
            return
        }
        goTo(next)
        if (pauseAtEndOfSong) {
            wantsToPlay = false
            state = Player.STATE_READY
            return
        }
        loadCurrent(0)
    }

    // Given the next song ahead of time, the device went on to it itself.
    private fun movedOnByItself() {
        val index = queue.items.indexOfFirst { keyOf(it) == nextKey }
        loadedUrl = nextUrl
        loadedKey = nextKey
        nextUrl = null
        nextKey = null
        if (index < 0) {
            // That song has since left the queue: play the current one instead.
            loadCurrent(0)
            return
        }
        goTo(index)
        refreshNext()
    }

    private fun goTo(index: Int) {
        queue.moveTo(index)
        deviceDurationMs = null
        anchor(0)
        endedIntoNext = true
    }

    // Sends the current song to the device, starting at `startMs`. A song
    // the device cannot have is passed over for the next one that it can.
    private fun loadCurrent(startMs: Long) {
        val item = queue.current ?: return
        cancelLoads()
        forgetLoaded()
        state = Player.STATE_BUFFERING
        anchor(startMs)
        if (!media.canPlay(item, output)) {
            skip(item)
            return
        }
        val key = keyOf(item)
        loadingKey = key
        loadJob = scope.launch {
            val song = media.make(item, output)
            if (queue.current?.let(::keyOf) != key) return@launch
            if (song == null) {
                skip(item)
                return@launch
            }
            val (following, followingKey) = upNextFor(key)
            commandAt = SystemClock.elapsedRealtime()
            // Until the device has the new song, where it says it is belongs to the old one.
            seekAt = commandAt
            // Playing or paused as the listener wants it by now.
            output.load(song, startMs, wantsToPlay, following)
            loadedKey = key
            loadedUrl = song.url
            nextKey = following?.let { followingKey }
            nextUrl = following?.url
            invalidateState()
        }
        invalidateState()
    }

    private fun skip(item: MediaItem) {
        onSkipped(item)
        skipsInARow++
        // Past REPEAT_ONE, since the same song would only fail again.
        val repeat = if (queue.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else queue.repeatMode
        val next = queue.next(repeat = repeat)
        if (next == null || next == queue.index || skipsInARow >= MAX_SKIPS) {
            state = Player.STATE_ENDED
            wantsToPlay = false
            invalidateState()
            return
        }
        goTo(next)
        loadCurrent(0)
    }

    // The song to give the device ahead of time after the one with `key`,
    // with its key, or nothing when it should not have one.
    private suspend fun upNextFor(key: String): Pair<RemoteMedia?, String?> {
        if (!output.takesNext || pauseAtEndOfSong || queue.repeatMode == Player.REPEAT_MODE_ONE) return null to null
        val at = queue.items.indexOfFirst { keyOf(it) == key }.takeIf { it >= 0 } ?: return null to null
        val next = queue.next(from = at)?.takeIf { it != at } ?: return null to null
        val item = queue.items[next]
        if (!media.canPlay(item, output)) return null to null
        return media.make(item, output) to keyOf(item)
    }

    // After the queue changed, tells the device the new song to go on to.
    private fun refreshNext() {
        val key = loadedKey ?: return
        if (!output.takesNext) return
        nextJob?.cancel()
        nextJob = scope.launch {
            val (following, followingKey) = upNextFor(key)
            if (loadedKey != key || followingKey == nextKey) return@launch
            output.setNext(following)
            nextKey = followingKey
            nextUrl = following?.url
        }
    }

    // ---- Small helpers ----

    private fun positionNow(): Long {
        if (!ticking) return anchorMs
        val moved = anchorMs + (SystemClock.elapsedRealtime() - anchorAt)
        return deviceDurationMs?.let { moved.coerceAtMost(it) } ?: moved
    }

    private fun anchor(positionMs: Long, moving: Boolean = false) {
        anchorMs = positionMs.coerceAtLeast(0)
        anchorAt = SystemClock.elapsedRealtime()
        ticking = moving
    }

    private fun forgetLoaded() {
        loadedKey = null
        loadedUrl = null
        nextKey = null
        nextUrl = null
        ticking = false
    }

    private fun cancelLoads() {
        loadJob?.cancel()
        nextJob?.cancel()
        loadJob = null
        nextJob = null
    }

    // Songs added while casting get entry ids of their own kind, so they
    // never clash with the phone's.
    private fun stamped(items: List<MediaItem>): List<MediaItem> =
        items.map { if (it.entryId != null) it else it.withEntry("r:${nextEntry++}") }

    private fun keyOf(item: MediaItem): String = item.entryId ?: item.mediaId

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        val REMOTE_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(VOLUME_STEPS)
            .build()

        // Everything but speed and the player's own volume, which belong to
        // the phone.
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
