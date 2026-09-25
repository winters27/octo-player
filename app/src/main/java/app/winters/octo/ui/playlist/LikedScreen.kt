package app.winters.octo.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LikedViewModel @Inject constructor(
    userDao: UserDao,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Null until first read, so an empty list is never shown by mistake.
    val tracks: StateFlow<List<TrackEntity>?> =
        userDao.likedTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun ids() = tracks.value.orEmpty().map { it.id }

    fun play(index: Int) = playback.playTracks(ids(), index)

    fun shuffle() = playback.playTracks(ids(), shuffle = true)
}

// Liked songs, the newest like first.
@Composable
fun LikedScreen(onBack: () -> Unit, vm: LikedViewModel = hiltViewModel()) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        tracks?.let { list ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "header") {
                    ListHeader(
                        title = "Liked songs",
                        songCount = list.size,
                        durationMs = list.sumOf { it.durationMs },
                        onPlay = { vm.play(0) },
                        onShuffle = vm::shuffle,
                    ) { modifier, shape -> LikedCover(240.dp, modifier, shape) }
                }
                if (list.isEmpty()) {
                    item(key = "empty") { EmptyNote("Songs you like show up here. Tap the heart in the player, or long press any song.") }
                }
                itemsIndexed(list, key = { _, track -> track.id }) { index, track ->
                    SongRow(track) { vm.play(index) }
                }
            }
        }
        BackButton(onBack)
    }
}
