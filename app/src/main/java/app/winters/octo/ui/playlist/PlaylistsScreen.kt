package app.winters.octo.ui.playlist

import app.winters.octo.ui.common.sortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import app.winters.octo.covers.playlistGlyph
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.design.OctoIcons
import app.winters.octo.ui.common.phonePlaylistFooter
import app.winters.octo.ui.common.PlaylistArtwork
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.playlists.PlaylistsPagePrefs
import app.winters.octo.ui.nav.PlaylistFolderRoute
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.data.userMessage
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistFiles
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.sortPlaylists
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.HeaderMore
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.common.sortedRows
import app.winters.octo.ui.livelists.LiveListLine
import app.winters.octo.ui.menu.CollectionTarget
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.nav.LiveListEditRoute
import app.winters.octo.ui.nav.LiveListRoute
import app.winters.octo.ui.nav.PlaylistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.Station
import app.winters.octo.home.answered
import app.winters.octo.lists.OctoListGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    store: PlaylistStore,
    private val files: PlaylistFiles,
    private val sorting: SortSettings,
    liveListStore: LiveListStore,
    liveSongs: LiveListSongs,
    private val discovery: Discovery,
    private val keptOcto: KeptOctoListsFiles,
    folderStore: PlaylistFolderStore,
    private val pagePrefs: PlaylistsPagePrefs,
) : ViewModel() {
    // Whether folders are offered, and the folders.
    val folders: StateFlow<FolderView> =
        combine(folderStore.offered, folderStore.tree) { on, tree -> FolderView(on, tree) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderView.Off)

    // Whether the library upkeep group is open, as this phone remembers it.
    val upkeepOpen: StateFlow<Boolean> = pagePrefs.upkeepOpen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUpkeepOpen(open: Boolean) = pagePrefs.setUpkeepOpen(open)
    // The live lists, in the order they were made, above the playlists.
    val liveLists: StateFlow<List<LiveList>> =
        liveListStore.lists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The covers of each live list's first albums, by its id, for its
    // picture, each found by a walk that stops once it has them.
    val liveCovers: StateFlow<Map<String, List<String>>> =
        liveListStore.lists.flatMapLatest { lists ->
            if (lists.isEmpty()) flowOf(emptyMap()) else combine(lists.map { list -> liveSongs.coversOf(list).map { list.id to it } }) { it.toMap() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // The playlists in the chosen order. There are few, so they sort here.
    val playlists: StateFlow<Sorted<PlaylistSummary>?> =
        combine(store.playlists, sorting.order(SortList.Playlists)) { list, order -> Sorted(sortPlaylists(list, order), order, null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The lists an Octo server made for the listener, by group, as it last
    // gave them and then as it gives them now; none on any other server.
    private val _octoLists = MutableStateFlow<List<Pair<OctoListGroup, List<Station>>>>(emptyList())
    val octoLists: StateFlow<List<Pair<OctoListGroup, List<Station>>>> = _octoLists

    // How the last playlist file import went, shown until the page closes.
    private val _importState = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _importState

    init {
        // A playlist deleted on another device leaves the list, and one
        // made there comes in, without waiting for the next library copy.
        store.freshen()
        // A list found gone on its own page leaves this one too.
        viewModelScope.launch {
            discovery.gone.collect { id ->
                _octoLists.value = _octoLists.value.map { (group, lists) -> group to lists.filterNot { it.id == id } }.filter { it.second.isNotEmpty() }
            }
        }
        viewModelScope.launch {
            discovery.stationsFrom.collectLatest { server ->
                if (server == null) {
                    _octoLists.value = emptyList()
                    return@collectLatest
                }
                withContext(Dispatchers.IO) { keptOcto.store.read(server) }?.let { _octoLists.value = answered(_octoLists.value, it.read()) }
                try {
                    val got = withContext(Dispatchers.IO) { discovery.octoLists() }
                    _octoLists.value = answered(_octoLists.value, got)
                    withContext(Dispatchers.IO) { keptOcto.store.write(server, KeptOctoLists.of(got)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Octo", "Octo lists failed to load: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Playlists, order) }
    }

    fun importFile(uri: Uri) {
        _importState.value = ImportState.Working
        viewModelScope.launch {
            _importState.value = try {
                files.import(uri)?.let(ImportState::Done) ?: ImportState.Failed
            } catch (e: NotEnoughSpace) {
                ImportState.Problem(e.userMessage())
            }
        }
    }
}

// What the plus on the header makes, in order: a playlist, a live list, or
// a playlist from a file. Liked songs is not here; hearted songs live on
// the Favorites page.
// A folder only while folders are offered.
enum class PlaylistsAdd(val label: String, val detail: String?) {
    New("New playlist", null),
    NewFolder(PlaylistFolderWords.NEW_FOLDER, null),
    NewLive("New live list", "Songs picked by rules, always up to date"),
    Import("Import a playlist file", "M3U or M3U8"),
}

private fun PlaylistsAdd.icon(): Int = when (this) {
    PlaylistsAdd.New -> OctoIcons.AddToPlaylist
    PlaylistsAdd.NewFolder -> OctoIcons.PlaylistFolder
    PlaylistsAdd.NewLive -> OctoIcons.Filter
    PlaylistsAdd.Import -> OctoIcons.Folder
}

// Every playlist: the title alone, then Sort and the plus (New playlist,
// New folder while folders are offered, New live list, Import), how the
// last import went while there is one, then the live lists, the folders A
// to Z, a gap, the playlists in no folder in the chosen order (the one
// changed last at the top to begin with), the library upkeep group, and
// the lists Octo made, by kind.
@Composable
fun PlaylistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val liveLists by vm.liveLists.collectAsStateWithLifecycle()
    val liveCovers by vm.liveCovers.collectAsStateWithLifecycle()
    val octoLists by vm.octoLists.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val upkeepOpen by vm.upkeepOpen.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    val menus = LocalSongMenu.current.collections
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }
    val rows = remember(playlists, folders) { folderPageRows(playlists?.items.orEmpty(), folders.shown, null) }
    val adds = PlaylistsAdd.entries.filter { it != PlaylistsAdd.NewFolder || folders.on }

    val state = rememberLazyListState()
    playlists?.let { TopOnNewOrder(it.order, state) }

    Refreshable {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
            item(key = "title") {
                PageHeader(
                    "Playlists",
                    onBack,
                    lead = listOfNotNull(playlists?.let { sorted -> sortItem(SortList.Playlists, sorted.order, vm::setOrder) }),
                    actions = {
                        HeaderMore(
                            adds.map { add ->
                                MoreItem(add.label, add.icon(), detail = add.detail, opensPage = add != PlaylistsAdd.Import) {
                                    when (add) {
                                        PlaylistsAdd.New -> sheets.show(PlaylistSheet.Create())
                                        PlaylistsAdd.NewFolder -> sheets.show(PlaylistSheet.NewFolder(parent = null))
                                        PlaylistsAdd.NewLive -> onOpen(LiveListEditRoute())
                                        PlaylistsAdd.Import -> pickFile.launch(PlaylistFileTypes)
                                    }
                                }
                            },
                            title = if (folders.on) "New playlist, folder, live list or import" else "New playlist, live list or import",
                            icon = OctoIcons.AddToLibrary,
                        )
                    },
                )
            }
            importState?.let { state -> item(key = "import-note") { ImportNote(state) } }
            items(liveLists, key = { "live:${it.id}" }) { list ->
                LiveListLine(list, liveCovers[list.id].orEmpty(), onClick = { onOpen(LiveListRoute(list.id)) }, onLongClick = { sheets.show(PlaylistSheet.LiveOptions(list.id, list.name)) })
            }
            items(rows.folders, key = { "folder:${it.folder.id}" }) { line ->
                FolderRow(
                    line,
                    onClick = { onOpen(PlaylistFolderRoute(line.folder.id)) },
                    onLongClick = { sheets.show(PlaylistSheet.FolderOptions(line.folder.id, line.folder.name)) },
                )
            }
            if (rows.folders.isNotEmpty() && rows.playlists.isNotEmpty()) item(key = "folders-gap") { TopicGap() }
            sortedRows(rows.playlists, key = { it.id }) { playlist ->
                OwnPlaylistLine(playlist, onOpen) { menus.open(CollectionTarget.Playlist(playlist.id)) }
            }
            upkeepRows(rows.upkeep, rows.upkeepNote, upkeepOpen, vm::setUpkeepOpen) { playlist ->
                OwnPlaylistLine(playlist, onOpen) { menus.open(CollectionTarget.Playlist(playlist.id)) }
            }
            octoListRows(octoLists, onOpen)
        }
    }
}
