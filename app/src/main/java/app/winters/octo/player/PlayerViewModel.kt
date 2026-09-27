package app.winters.octo.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsRepository
import app.winters.octo.lyrics.LyricsSong
import app.winters.octo.playback.DeviceVolume
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.QueueEditor
import app.winters.octo.playback.QueueEntry
import app.winters.octo.playback.SleepState
import app.winters.octo.playback.SleepTimer
import app.winters.octo.playback.isRadio
import app.winters.octo.player.immersive.over
import app.winters.octo.sound.SoundEngine
import app.winters.octo.ui.common.Feedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// Everything the full player shows, and what its buttons do.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    palette: ArtworkPalette,
    private val likes: LikeStore,
    catalog: CatalogDao,
    online: OnlineDao,
    settings: PlayerSettings,
    private val deviceVolume: DeviceVolume,
    private val sleepTimer: SleepTimer,
    sound: SoundEngine,
    private val lyricsRepository: LyricsRepository,
    private val editor: QueueEditor,
    private val playlists: PlaylistStore,
    private val feedback: Feedback,
) : ViewModel() {
    val now: StateFlow<NowPlaying> = playback.now
    val upNext: StateFlow<List<QueueEntry>> = playback.upNext
    val played: StateFlow<List<QueueEntry>> = playback.played

    // The album name for the top of the player, or nothing for a single. A
    // song found online has no album in the library to count, so it shows none.
    val albumLabel: StateFlow<String?> = playback.now
        .map { Triple(it.albumId, it.album, it.title) }
        .distinctUntilChanged()
        .flatMapLatest { (albumId, album, title) ->
            if (albumId.isNullOrEmpty()) {
                flowOf(null)
            } else {
                catalog.album(albumId).map { albumLabel(album, title, it?.songCount ?: 0) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whether the song on now is in Liked songs.
    val liked: StateFlow<Boolean> = combine(playback.now, likes.liked) { now, liked -> now.trackId in liked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleLike() {
        playback.now.value.trackId?.takeUnless(::isFind)?.let(likes::toggle)
    }

    // The song on now when it was found online, for the download button
    // that stands where the heart would be.
    val find: StateFlow<TrackEntity?> = playback.now
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id != null && isFind(id)) online.songFlow(id).map { it?.asTrack() } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val prefs: StateFlow<PlayerPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    // Colours follow the artwork, not the song, so an album plays through
    // without the background flickering. `content` is the colour for the
    // player's words and icons over the chosen background.
    val colors: StateFlow<PlayerColors> = playback.now
        .map { it.artwork }
        .distinctUntilChanged()
        .mapLatest(palette::colorsFor)
        .combine(settings.prefs.map { it.background }.distinctUntilChanged()) { colors, background -> colors.over(background) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerColors.Quiet)

    fun positionMs() = playback.positionMs()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    val volume: StateFlow<Float> = deviceVolume.level
    fun setVolume(fraction: Float) = deviceVolume.set(fraction)
    fun moveInQueue(from: Int, to: Int) = playback.moveInQueue(from, to)
    fun playAt(index: Int) = playback.playAt(index)

    // Queue edits go by each song's key, so a quick second swipe never
    // takes out the wrong song. Those that take songs out can be undone.
    fun removeFromQueue(entry: QueueEntry) {
        val undo = editor.remove(entry.key) ?: return
        feedback.undoable("Removed from the queue") { editor.undo(undo) }
    }

    // Everything but the song that is on.
    fun clearQueue() {
        val undo = editor.clear() ?: return
        feedback.undoable("Queue cleared") { editor.undo(undo) }
    }

    fun removePlayed() {
        val undo = editor.removePlayed() ?: return
        feedback.undoable("Played songs removed") { editor.undo(undo) }
    }

    fun playNextInQueue(entry: QueueEntry) = editor.playNext(entry.key)

    // The listener's queue as a playlist: what played, the song on now and
    // what is still to come, without the songs Autoplay added.
    fun saveQueueAsPlaylist(name: String) {
        val ids = (played.value + upNext.value).filterNot { it.autoplay || isRadio(it.trackId) }.map { it.trackId }
        playlists.create(name, ids)
        feedback.show("Saved as ${name.trim()}")
    }

    val sleep: StateFlow<SleepState> = sleepTimer.state

    // Whether the equalizer is on, for the Sound button's glow.
    val equalizerOn: StateFlow<Boolean> = sound.current
        .map { it.eqEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun sleepIn(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfSong() = sleepTimer.startEndOfSong()
    fun sleepAfterSongs(count: Int) = sleepTimer.startAfterSongs(count)
    fun extendSleep(minutes: Int) = sleepTimer.extend(minutes)
    fun cancelSleep() = sleepTimer.cancel()

    // From a queue row's menu: the music stops once that song has played.
    fun sleepAfter(entry: QueueEntry) {
        sleepTimer.startAfter(entry.key, entry.title)
        feedback.undoable("Stops after ${entry.title}") { sleepTimer.cancel() }
    }

    // The rating of the song on now, 0 when it has none or was found online.
    val rating: StateFlow<Int> = playback.now
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null || isFind(id)) flowOf(0) else catalog.trackFlow(id).map { it?.rating ?: 0 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether the lyrics show in place of the artwork. They are only looked
    // up while they show, and follow the song while they do.
    private val lyricsShown = MutableStateFlow(false)
    val lyricsOpen: StateFlow<Boolean> = lyricsShown
    fun toggleLyrics() {
        lyricsShown.value = !lyricsShown.value
    }

    val lyrics: StateFlow<LyricsState> = combine(playback.now.map { it.trackId }.distinctUntilChanged(), lyricsShown) { id, open ->
        id.takeIf { open }
    }
        .distinctUntilChanged()
        .transformLatest { id ->
            if (id == null) {
                emit(LyricsState.Hidden)
                return@transformLatest
            }
            emit(LyricsState.Loading)
            val now = playback.now.value
            val song = LyricsSong(id, now.title.orEmpty(), now.artist.orEmpty(), now.album.orEmpty(), now.durationMs)
            emit(lyricsRepository.lyricsFor(song)?.let { LyricsState.Found(id, it) } ?: LyricsState.None)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsState.Hidden)

    // While lyrics show, the songs either side of this one have theirs
    // looked up once the song has settled, so skipping shows them at once.
    // A skip before then starts the wait again, so skipping quickly through
    // the queue asks for nothing.
    init {
        viewModelScope.launch {
            combine(playback.now.map { it.trackId }.distinctUntilChanged(), lyricsShown) { id, open -> id.takeIf { open } }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id == null) return@collectLatest
                    delay(LYRICS_PREFETCH_SETTLE_MS)
                    listOfNotNull(upNext.value.getOrNull(1), played.value.lastOrNull())
                        .filterNot { isRadio(it.trackId) || it.trackId == id }
                        .forEach { entry ->
                            try {
                                lyricsRepository.lyricsFor(LyricsSong(entry.trackId, entry.title, entry.artist, "", entry.durationMs))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // A neighbour's lyrics are only a head start; it is asked again when it plays.
                            }
                        }
                }
        }
    }
}

// How long a song must stay on before its neighbours' lyrics are looked up.
private const val LYRICS_PREFETCH_SETTLE_MS = 1_200L

// What the lyrics view shows.
sealed interface LyricsState {
    data object Hidden : LyricsState
    data object Loading : LyricsState
    data object None : LyricsState
    data class Found(val trackId: String, val lyrics: Lyrics) : LyricsState
}
