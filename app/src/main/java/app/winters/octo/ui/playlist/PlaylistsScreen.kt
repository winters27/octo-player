package app.winters.octo.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.LikedRoute
import app.winters.octo.ui.nav.PlaylistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(store: PlaylistStore, userDao: UserDao) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val likedCount: StateFlow<Int> =
        userDao.likedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

// Every playlist: a way to make a new one, Liked songs pinned first, then
// the listener's own, the one changed last at the top.
@Composable
fun PlaylistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val likedCount by vm.likedCount.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle("Playlists") }
            item(key = "new") { NewPlaylistLine { sheets.show(PlaylistSheet.Create()) } }
            item(key = "liked") {
                PlaylistLine("Liked songs", songs(likedCount), onClick = { onOpen(LikedRoute) }) { LikedCover(56.dp) }
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistLine(playlist.name, songs(playlist.songCount), onClick = { onOpen(PlaylistRoute(playlist.id)) }) {
                    PlaylistCover(playlist.covers, 56.dp)
                }
            }
        }
        BackButton(onBack)
    }
}
