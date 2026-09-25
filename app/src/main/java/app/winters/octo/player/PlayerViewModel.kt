package app.winters.octo.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.playback.DeviceVolume
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QueueEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    settings: PlayerSettings,
    private val deviceVolume: DeviceVolume,
) : ViewModel() {
    val now: StateFlow<NowPlaying> = playback.now
    val upNext: StateFlow<List<QueueEntry>> = playback.upNext

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
}
