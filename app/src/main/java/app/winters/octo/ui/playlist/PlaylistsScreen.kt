package app.winters.octo.ui.playlist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.winters.octo.playlists.PlaylistFiles
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.LikedRoute
import app.winters.octo.ui.nav.PlaylistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    store: PlaylistStore,
    userDao: UserDao,
    private val files: PlaylistFiles,
) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> =
        store.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val likedCount: StateFlow<Int> =
        userDao.likedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // How the last playlist file import went, shown until the page closes.
    private val _importState = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _importState

    fun importFile(uri: Uri) {
        _importState.value = ImportState.Working
        viewModelScope.launch {
            _importState.value = files.import(uri)?.let(ImportState::Done) ?: ImportState.Failed
        }
    }
}

// Every playlist: a way to make a new one, Liked songs pinned first, then
// the listener's own and the server's, the one changed last at the top.
@Composable
fun PlaylistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val likedCount by vm.likedCount.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle("Playlists") }
            item(key = "new") { NewPlaylistLine { sheets.show(PlaylistSheet.Create()) } }
            item(key = "import") { ImportPlaylistLine { pickFile.launch(PlaylistFileTypes) } }
            importState?.let { state -> item(key = "import-note") { ImportNote(state) } }
            item(key = "liked") {
                PlaylistLine("Liked songs", songs(likedCount), onClick = { onOpen(LikedRoute) }) { LikedCover(56.dp) }
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistLine(playlist.name, songs(playlist.songCount), onClick = { onOpen(PlaylistRoute(playlist.id)) }, onServer = playlist.onServer) {
                    PlaylistCover(playlist.covers, 56.dp)
                }
            }
        }
        BackButton(onBack)
    }
}
