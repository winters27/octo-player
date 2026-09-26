package app.winters.octo.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.OctoColors
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.RemoveBackground
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
    private val likes: LikeStore,
    private val feedback: Feedback,
) : ViewModel() {
    // Null until first read, so an empty list is never shown by mistake.
    val tracks: StateFlow<List<TrackEntity>?> =
        userDao.likedTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun ids() = tracks.value.orEmpty().map { it.id }

    fun play(index: Int) = playback.playTracks(ids(), index)

    fun shuffle() = playback.playTracks(ids(), shuffle = true)

    // Unlikes a song, with an Undo that likes it again in the same place.
    fun unlike(trackId: String) = likes.unlike(trackId) { row ->
        feedback.undoable("Removed from Liked songs") { likes.restore(row) }
    }
}

private val RowShape = RoundedCornerShape(12.dp)

// Liked songs, the newest like first. Swipe a song left to unlike it.
@Composable
fun LikedScreen(onBack: () -> Unit, vm: LikedViewModel = hiltViewModel()) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        tracks?.let { list ->
            // The rows as drawn: a swiped one goes at once, the list catches up.
            var rows by remember(list) { mutableStateOf(list) }
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
                item(key = "keep") { KeepLikedDownloaded(list) }
                if (rows.isEmpty()) {
                    item(key = "empty") { EmptyNote("Songs you like show up here. Tap the heart in the player, or long press any song.") }
                }
                items(rows, key = { it.id }) { track ->
                    val swipe = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = swipe,
                        enableDismissFromStartToEnd = false,
                        onDismiss = {
                            rows = rows - track
                            vm.unlike(track.id)
                        },
                        backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                    ) {
                        // Solid under the row, so it hides what it passes over.
                        Box(Modifier.background(OctoColors.Background, RowShape)) {
                            SongRow(track) { vm.play(list.indexOf(track)) }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}
