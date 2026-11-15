package app.winters.octo.ui.playlist

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.design.OctoIcons
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.sortPlaylists
import app.winters.octo.ui.common.HeaderMore
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.sortItem
import app.winters.octo.ui.common.sortedRows
import app.winters.octo.ui.menu.CollectionTarget
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.nav.PlaylistFolderRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = PlaylistFolderViewModel.Factory::class)
class PlaylistFolderViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    store: PlaylistStore,
    private val sorting: SortSettings,
    folders: PlaylistFolderStore,
) : ViewModel() {
    // The playlists in the chosen order, the Playlists page's own.
    val playlists: StateFlow<Sorted<PlaylistSummary>?> =
        combine(store.playlists, sorting.order(SortList.Playlists)) { list, order -> Sorted(sortPlaylists(list, order), order, null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The folders once read; null before.
    val tree: StateFlow<PlaylistFolderTree?> =
        folders.tree.map<PlaylistFolderTree, PlaylistFolderTree?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Playlists, order) }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: String): PlaylistFolderViewModel
    }
}

// A playlist folder's page: back and its name alone on the first line,
// then Sort and More (New playlist here, New folder here, Add playlists,
// Rename, Move to folder, Delete folder); its folders A to Z, then its
// playlists in the listener's order; an empty one says so. The page leaves
// once the folder is gone (deleted here or on another device).
@Composable
fun PlaylistFolderScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: PlaylistFolderViewModel = hiltViewModel<PlaylistFolderViewModel, PlaylistFolderViewModel.Factory> { it.create(id) },
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val loaded by vm.tree.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    val menus = LocalSongMenu.current.collections
    val tree = loaded
    val folder = tree?.folder(id)
    val gone = tree != null && folder == null
    LaunchedEffect(gone) { if (gone) onBack() }
    if (tree == null || folder == null) return
    val rows = remember(playlists, tree, id) { folderPageRows(playlists?.items.orEmpty(), tree, id) }
    val state = rememberLazyListState()
    playlists?.let { TopOnNewOrder(it.order, state) }
    val holds = tree.canHoldFolder(id)
    val more = listOf(
        MoreItem(PlaylistFolderWords.NEW_PLAYLIST_HERE, OctoIcons.AddToPlaylist, opensPage = true) { sheets.show(PlaylistSheet.Create(folderId = id)) },
        MoreItem(PlaylistFolderWords.NEW_FOLDER_HERE, OctoIcons.PlaylistFolder, detail = if (holds) null else PlaylistFolderWords.TOO_DEEP, enabled = holds, opensPage = true) {
            sheets.show(PlaylistSheet.NewFolder(parent = id))
        },
        MoreItem(PlaylistFolderWords.ADD_PLAYLISTS, OctoIcons.AddToLibrary, opensPage = true) { sheets.show(PlaylistSheet.AddPlaylists(id, folder.name)) },
        MoreItem(PlaylistFolderWords.RENAME, OctoIcons.Rename, opensPage = true) { sheets.show(PlaylistSheet.RenameFolder(id, folder.name)) },
        MoreItem(PlaylistFolderWords.MOVE_TO_FOLDER, OctoIcons.PlaylistFolder, opensPage = true) {
            sheets.show(PlaylistSheet.MoveToFolder(FolderMoveTarget.Folder(id, folder.name)))
        },
        MoreItem(PlaylistFolderWords.DELETE_FOLDER, OctoIcons.Delete, destructive = true, opensPage = true) { sheets.show(PlaylistSheet.DeleteFolder(id, folder.name)) },
    )

    Refreshable {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
            item(key = "title") {
                PageHeader(
                    folder.name,
                    onBack,
                    lead = listOfNotNull(playlists?.let { sorted -> sortItem(SortList.Playlists, sorted.order, vm::setOrder) }),
                    actions = { HeaderMore(more) },
                )
            }
            items(rows.folders, key = { "folder:${it.folder.id}" }) { line ->
                FolderRow(
                    line,
                    onClick = { onOpen(PlaylistFolderRoute(line.folder.id)) },
                    onLongClick = { sheets.show(PlaylistSheet.FolderOptions(line.folder.id, line.folder.name)) },
                )
            }
            if (rows.folders.isNotEmpty() && rows.playlists.isNotEmpty()) item(key = "gap") { TopicGap() }
            sortedRows(rows.playlists, key = { it.id }) { playlist ->
                OwnPlaylistLine(playlist, onOpen) { menus.open(CollectionTarget.Playlist(playlist.id)) }
            }
            if (rows.empty) item(key = "empty") { EmptyFolderNote() }
        }
    }
}
