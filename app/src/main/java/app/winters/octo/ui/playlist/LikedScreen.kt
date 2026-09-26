package app.winters.octo.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LikedViewModel @Inject constructor(
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // In the chosen order, the newest like first to begin with. Null until
    // first read, so an empty list is never shown by mistake.
    val tracks: StateFlow<Sorted<TrackEntity>?> =
        sorted.songs(SortList.Liked, SongScope.Liked).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun ids() = tracks.value?.items.orEmpty().map { it.id }

    fun play(index: Int) = playback.playTracks(ids(), index)

    fun shuffle() = playback.playTracks(ids(), shuffle = true)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(SortList.Liked, order) }
    }
}

// Where the sort line sits, after the header and the keep-downloaded line;
// a new order scrolls back to it.
private const val SORT_ROW = 2

// Liked songs, in the chosen order.
@Composable
fun LikedScreen(onBack: () -> Unit, vm: LikedViewModel = hiltViewModel()) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val state = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        tracks?.let { sorted ->
            val list = sorted.items
            TopOnNewOrder(sorted.order, state, top = SORT_ROW)
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "header") {
                    ListHeader(
                        title = "Liked songs",
                        songCount = list.size,
                        durationMs = list.sumOf { it.durationMs },
                        onPlay = { vm.play(0) },
                        onShuffle = vm::shuffle,
                    ) { modifier, shape -> LikedCover(240.dp, modifier, shape) }
                }
                item(key = "keep") { KeepLikedDownloaded(list) }
                if (list.isEmpty()) {
                    item(key = "empty") { EmptyNote("Songs you like show up here. Tap the heart in the player, or long press any song.") }
                } else {
                    item(key = "sort") { SortBar(SortList.Liked, sorted.order, vm::setOrder) }
                }
                itemsIndexed(list, key = { _, track -> track.id }) { index, track ->
                    Box(Modifier.animateItem()) { SongRow(track) { vm.play(index) } }
                }
            }
        }
        BackButton(onBack)
    }
}
