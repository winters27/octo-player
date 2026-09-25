package app.winters.octo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.winters.octo.MainActivity
import app.winters.octo.catalog.CatalogDao
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import javax.inject.Inject

// The like toggle offered in the notification and on the lock screen.
private val LIKE = SessionCommand("app.winters.octo.LIKE", Bundle.EMPTY)

private const val ROOT_ID = "root"

// Plays music with the screen off, and answers the lock screen, the
// notification, headphone buttons and the app.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class OctoPlaybackService : MediaLibraryService() {
    @Inject lateinit var catalog: CatalogDao
    @Inject lateinit var likes: LikeStore
    @Inject lateinit var queue: QueueStore
    @Inject lateinit var plays: PlayStore

    private val scope = MainScope()
    private lateinit var player: OctoPlayer
    private lateinit var tracker: PlayTracker
    private var session: MediaLibrarySession? = null
    private val currentId = MutableStateFlow<String?>(null)
    private val queueChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var positionSaver: Job? = null
    // Done once last session's queue is back, or there was none.
    private val restored = CompletableDeferred<Unit>()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        player = OctoPlayer(this, buildDeck(this))
        tracker = PlayTracker(plays) { player.isPlaying }
        player.addListener(tracker)
        player.addListener(Watcher())

        session = MediaLibrarySession.Builder(this, player, Callback())
            .setBitmapLoader(CacheBitmapLoader(OctoArtLoader(this, DataSourceBitmapLoader(this))))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        scope.launch {
            try {
                restoreQueue()
            } finally {
                restored.complete(Unit)
            }
        }
        // The heart follows both the song and the likes list.
        scope.launch { combine(currentId, likes.liked) { id, liked -> id != null && id in liked }.collect(::showLike) }
        // Editing the queue saves it once things settle.
        scope.launch { queueChanged.debounce(500).collect { saveQueue() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveQueue()
        tracker.flush()
        session?.release()
        player.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showLike(liked: Boolean) {
        val button = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setSessionCommand(LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session?.setMediaButtonPreferences(ImmutableList.of(button))
    }

    // Brings back the queue from last time, paused where it was left.
    private suspend fun restoreQueue() {
        val saved = queue.load() ?: return
        val tracks = catalog.tracksByIds(saved.trackIds)
        if (tracks.isEmpty() || player.mediaItemCount > 0) return
        val complete = tracks.size == saved.trackIds.size
        val index = tracks.indexOfFirst { it.id == saved.trackIds[saved.index] }.takeIf { it >= 0 } ?: 0
        player.setMediaItems(tracks.map { it.toMediaItem() }, index, if (index >= 0) saved.positionMs else 0)
        player.repeatMode = saved.repeatMode
        // The saved shuffle order only fits if every song is still here.
        if (complete && saved.shuffleOrder.size == tracks.size) {
            player.deck.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(saved.shuffleOrder.toIntArray(), System.nanoTime()))
        }
        player.shuffleModeEnabled = saved.shuffle
        player.prepare()
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        queue.save(snapshot())
    }

    private fun snapshot(): QueueSnapshot {
        val timeline = player.currentTimeline
        val order = buildList {
            var i = timeline.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET) {
                add(i)
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
        }
        return QueueSnapshot(
            trackIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
            shuffleOrder = order,
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
        )
    }

    // Turns song ids into playable songs, in order, skipping any that are gone.
    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> =
        catalog.tracksByIds(items.map { it.mediaId }).map { it.toMediaItem() }

    private inner class Watcher : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentId.value = mediaItem?.mediaId
            saveQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            queueChanged.tryEmit(Unit)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionSaver?.cancel()
            if (isPlaying) {
                // Where in the song we are, every 15 seconds, in case the phone kills the app.
                positionSaver = scope.launch {
                    while (isActive) {
                        delay(15_000)
                        saveQueue()
                    }
                }
            } else {
                saveQueue()
            }
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<ConnectionResult> {
            // Apps we don't trust keep the read-only access Media3 gives them.
            if (!controller.isTrusted) return super.onConnectAsync(session, controller)
            // The app connects only once the saved queue is back, so it never
            // mistakes a player still restoring for an empty one.
            return scope.future {
                restored.await()
                ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(
                        ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(LIKE).build(),
                    )
                    .build()
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand == LIKE) {
                currentId.value?.let(likes::toggle)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val resolved = resolve(mediaItems)
            // Keep starting on the chosen song even if some before it are gone.
            val wanted = mediaItems.getOrNull(startIndex)?.mediaId
            val index = if (startIndex == C.INDEX_UNSET) C.INDEX_UNSET else resolved.indexOfFirst { it.mediaId == wanted }.coerceAtLeast(0)
            MediaItemsWithStartPosition(resolved, index, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val saved = queue.load() ?: throw UnsupportedOperationException("Nothing to resume")
            val items = catalog.tracksByIds(saved.trackIds).map { it.toMediaItem() }
            if (items.isEmpty()) throw UnsupportedOperationException("Nothing to resume")
            val index = items.indexOfFirst { it.mediaId == saved.trackIds.getOrNull(saved.index) }.coerceAtLeast(0)
            if (isForPlayback) {
                MediaItemsWithStartPosition(items, index, saved.positionMs)
            } else {
                // The media controls only need the song to show.
                MediaItemsWithStartPosition(listOf(items[index]), 0, saved.positionMs)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("Octo").build())
                    .build(),
                params,
            ),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            // Browsing from a car or watch arrives with Android Auto support.
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }
}
