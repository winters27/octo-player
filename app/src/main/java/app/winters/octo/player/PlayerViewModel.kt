package app.winters.octo.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.playback.DeviceVolume
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QueueEntry
import app.winters.octo.playback.SleepState
import app.winters.octo.playback.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {
    val now: StateFlow<NowPlaying> = playback.now
    val upNext: StateFlow<List<QueueEntry>> = playback.upNext

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
    // without the background flickering.
    val colors: StateFlow<PlayerColors> = playback.now
        .map { it.artwork }
        .distinctUntilChanged()
        .mapLatest(palette::colorsFor)
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
    fun removeFromQueue(index: Int) = playback.removeFromQueue(index)
    fun playAt(index: Int) = playback.playAt(index)

    val sleep: StateFlow<SleepState> = sleepTimer.state
    fun sleepIn(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfSong() = sleepTimer.startEndOfSong()
    fun cancelSleep() = sleepTimer.cancel()
}
