package app.winters.octo.output

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.EditableQueue
import app.winters.octo.playback.LiveTimeline
import app.winters.octo.playback.OctoPlayer
import app.winters.octo.playback.QueueMirror
import app.winters.octo.playback.SleepTarget
import app.winters.octo.playback.commandsOf
import app.winters.octo.playback.entryId
import app.winters.octo.playback.liveCommands
import app.winters.octo.playback.liveLineupNow
import app.winters.octo.playback.liveLineupOf
import app.winters.octo.playback.liveSeekTarget
import app.winters.octo.playback.toIds
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

// The one player the media session holds: the phone's own player, or the
// one standing in while music plays on another device. Swapping between
// them goes unnoticed outside, so the notification, lock screen, widgets,
// the app and a car keep one player throughout. The sleep timer and the
// queue edits the app offers work on whichever is in use.
//
// The player holds a window of the queue, which Room keeps whole: every
// edit made through here is told to `mirror` once the player has made it,
// so Room follows. While the window is shorter than the queue, the player
// itself runs without repeat and this one says the listener's; Next at the
// window's last song (and Previous at its first) waits for the next page,
// and a song ending there moves on as if the player had.
@OptIn(UnstableApi::class)
class OutputSwitch(private val local: OctoPlayer, private val mirror: () -> QueueMirror?) :
    CountingForwardingPlayer(local), SleepTarget, EditableQueue {
    var remote: RemotePlayer? = null
        private set

    val isRemote: Boolean get() = remote != null

    // The player that plays now: the phone's or the device's. The queue
    // moves its window on it directly.
    val active: EditableQueue get() = remote ?: local

    // Music moves to another device.
    fun useRemote(player: RemotePlayer) {
        remote = player
        player.pauseAtEndOfSong = pauseAtEnd
        setPlayer(player)
    }

    // Music comes back to the phone.
    fun useLocal() {
        remote = null
        setPlayer(local)
    }

    // The crossfade length in use: none while casting.
    val crossfadeMs: Long get() = if (isRemote) 0 else local.crossfadeMs

    // The sleep timer's fade is the phone's alone; a device keeps its volume.
    override var sleepFade: Float
        get() = local.sleepFade
        set(value) {
            local.sleepFade = value
        }

    private var pauseAtEnd = false

    override var pauseAtEndOfSong: Boolean
        get() = pauseAtEnd
        set(value) {
            pauseAtEnd = value
            local.pauseAtEndOfSong = value
            remote?.pauseAtEndOfSong = value
        }

    override val currentKey: String? get() = currentMediaItem?.entryId

    override val isBlending: Boolean get() = !isRemote && local.isBlending

    // An entry is still queued unless an edit took it out or the queue was
    // replaced; a window that moved past it still holds it.
    override fun hasEntry(key: String): Boolean =
        mirror()?.holds(key) ?: (0 until mediaItemCount).any { getMediaItemAt(it).entryId == key }

    override fun addNext(items: List<MediaItem>) {
        val placed = stamp(items, set = false)
        active.addNext(placed)
        mirror()?.added(placed, playing)
    }

    override fun setPlayOrder(order: IntArray) = active.setPlayOrder(order)

    override fun upkeep(write: () -> Unit) = active.upkeep(write)

    override fun putBack(runs: List<Pair<Int, List<MediaItem>>>, order: IntArray) {
        active.putBack(runs, order)
        mirror()?.added(runs.flatMap { it.second }, playing)
    }

    override fun removeRuns(runs: List<IntRange>) {
        val gone = runs.flatMap { it.toList() }.filter { it in 0 until playing.mediaItemCount }.map { playing.getMediaItemAt(it) }
        mirror()?.removing(gone)
        active.removeRuns(runs)
        mirror()?.removed(playing)
    }

    private fun stamp(items: List<MediaItem>, set: Boolean): List<MediaItem> = mirror()?.stamp(items, set) ?: items

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val stamped = stamp(mediaItems, set = true)
        val result = super.handleSetMediaItems(stamped, startIndex, startPositionMs)
        mirror()?.setItems(stamped, playing)
        return result
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val stamped = stamp(mediaItems, set = false)
        val result = super.handleAddMediaItems(index, stamped)
        mirror()?.added(stamped, playing)
        return result
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val gone = (fromIndex until toIndex.coerceAtMost(playing.mediaItemCount)).map { playing.getMediaItemAt(it) }
        mirror()?.removing(gone)
        val result = super.handleRemoveMediaItems(fromIndex, toIndex)
        mirror()?.removed(playing)
        return result
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val moving = (fromIndex until toIndex.coerceAtMost(playing.mediaItemCount)).map { playing.getMediaItemAt(it) }
        val result = super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
        mirror()?.moved(moving, playing)
        return result
    }

    // New words for the same items (a station's song title) change nothing
    // the queue keeps; other items in their place are a remove and an add.
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val before = (fromIndex until toIndex.coerceAtMost(playing.mediaItemCount)).map { playing.getMediaItemAt(it) }
        val same = before.map { it.mediaId to it.entryId } == mediaItems.map { it.mediaId to it.entryId }
        if (same) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        val stamped = stamp(mediaItems, set = false)
        mirror()?.removing(before)
        val result = super.handleReplaceMediaItems(fromIndex, toIndex, stamped)
        mirror()?.removed(playing)
        mirror()?.added(stamped, playing)
        return result
    }

    // Shuffle and repeat set to what the session already shows change
    // nothing and go no further, so a controller sending them again (a car
    // head unit) never re-keys the queue's order or cuts a blend. Compared
    // with what this reports: the queue's repeat, never the player's own,
    // which runs none on a window of a queue on repeat all.
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled == this.shuffleModeEnabled) return Futures.immediateVoidFuture()
        val result = super.handleSetShuffleModeEnabled(shuffleModeEnabled)
        mirror()?.shuffleChanged(shuffleModeEnabled)
        return result
    }

    // The listener's repeat is kept by the queue; the player runs it only
    // while it holds the whole queue.
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (repeatMode == this.repeatMode) return Futures.immediateVoidFuture()
        val mirror = mirror() ?: return super.handleSetRepeatMode(repeatMode)
        mirror.repeatChanged(repeatMode)
        return super.handleSetRepeatMode(mirror.innerRepeat())
    }

    // While a station plays, the session hears it is live with no length,
    // offers no seeking, and offers Back and Next only toward stations of
    // its lineup that are there. While the player holds a window, the
    // session hears the listener's repeat, Next and Previous as the whole
    // queue offers them, and buffering where the window ran out though the
    // queue goes on.
    override fun getState(): State {
        val state = super.getState()
        val lineup = liveLineupOf(state.timeline, state.currentMediaItemIndex)
        if (lineup != null) {
            return state.buildUpon()
                .setAvailableCommands(commandsOf(liveCommands(state.availableCommands.toIds(), lineup)))
                .setPlaylist(LiveTimeline(state.timeline), state.currentTracks, state.currentMetadata)
                .build()
        }
        val mirror = mirror() ?: return state
        if (!mirror.windowed) return state.buildUpon().setRepeatMode(mirror.listenerRepeat).build()
        val commands = state.availableCommands.buildUpon()
        if (!mirror.atLogicalEnd || mirror.listenerRepeat == Player.REPEAT_MODE_ALL) {
            commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (!mirror.atLogicalStart || mirror.listenerRepeat == Player.REPEAT_MODE_ALL) {
            commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        val built = state.buildUpon().setRepeatMode(mirror.listenerRepeat).setAvailableCommands(commands.build())
        if (state.playbackState == Player.STATE_ENDED && !mirror.atLogicalEnd) built.setPlaybackState(Player.STATE_BUFFERING)
        // The window stepping on because a song ended at its edge is heard as
        // that song ending, so the sleep timer counts it.
        if (state.hasPositionDiscontinuity && mirror.takeAutoStep()) {
            built.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, state.discontinuityPositionMs)
        }
        return built.build()
    }

    // Back and Next step through a station's lineup in its order, whatever
    // the shuffle and repeat settings say for songs; nothing seeks within a
    // station. Next at the window's last song, and Previous at its first,
    // wait for the queue's next page and then move on.
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val lineup = playing.liveLineupNow()
        if (lineup != null) {
            if (seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            liveSeekTarget(seekCommand, mediaItemIndex, lineup)?.let { playing.seekToDefaultPosition(it) }
            return Futures.immediateVoidFuture()
        }
        val forward = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> false
            Player.COMMAND_SEEK_TO_PREVIOUS -> if (playing.currentPosition > playing.maxSeekToPreviousPosition) null else false
            else -> null
        }
        if (forward != null) {
            mirror()?.edge(forward)?.let { step ->
                edgeWait = true
                step.addListener({ edgeWait = false }, MoreExecutors.directExecutor())
                return step
            }
            // The session's view had no song there (or only the current one)
            // though the player has one now, as right after a top-up: the
            // player moves itself, by its own items.
            if (mirror() != null && (mediaItemIndex == androidx.media3.common.C.INDEX_UNSET || mediaItemIndex == currentMediaItemIndex)) {
                if (forward) playing.seekToNextMediaItem() else playing.seekToPreviousMediaItem()
                return Futures.immediateVoidFuture()
            }
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    // The queue's ends or window changed: the session reads its commands
    // and repeat again.
    fun mirrorChanged() = invalidateState()

    // While a Next or Previous waits at the window's edge, the session keeps
    // showing the current song rather than the window's own neighbor.
    private var edgeWait = false

    override fun getPlaceholderState(suggestedPlaceholderState: State): State =
        if (edgeWait) getState() else super.getPlaceholderState(suggestedPlaceholderState)

    // The player in use, the phone's or the device's.
    private val playing: Player get() = remote ?: local

    // Both go when the service does.
    override fun handleRelease(): ListenableFuture<*> {
        remote?.release()
        remote = null
        local.release()
        return Futures.immediateVoidFuture()
    }
}
