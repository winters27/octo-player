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
) : ViewModel() {
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
enum class PlaylistsAdd(val label: String, val detail: String?) {
    New("New playlist", null),
    NewLive("New live list", "Songs picked by rules, always up to date"),
    Import("Import a playlist file", "M3U or M3U8"),
}

private fun PlaylistsAdd.icon(): Int = when (this) {
    PlaylistsAdd.New -> OctoIcons.AddToPlaylist
    PlaylistsAdd.NewLive -> OctoIcons.Filter
    PlaylistsAdd.Import -> OctoIcons.Folder
}

// Every playlist: a plus on the header to make or import one, how the last
// import went while there is one, then the listener's own and the server's
// in the chosen order, the one changed last at the top to
// begin with, then the lists Octo made, by kind.
@Composable
fun PlaylistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val liveLists by vm.liveLists.collectAsStateWithLifecycle()
    val liveCovers by vm.liveCovers.collectAsStateWithLifecycle()
    val octoLists by vm.octoLists.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    val menus = LocalSongMenu.current.collections
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }

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
                            PlaylistsAdd.entries.map { add ->
                                MoreItem(add.label, add.icon(), detail = add.detail, opensPage = add != PlaylistsAdd.Import) {
                                    when (add) {
                                        PlaylistsAdd.New -> sheets.show(PlaylistSheet.Create())
                                        PlaylistsAdd.NewLive -> onOpen(LiveListEditRoute())
                                        PlaylistsAdd.Import -> pickFile.launch(PlaylistFileTypes)
                                    }
                                }
                            },
                            title = "New playlist, live list or import",
                            icon = OctoIcons.AddToLibrary,
                        )
                    },
                )
            }
            importState?.let { state -> item(key = "import-note") { ImportNote(state) } }
            items(liveLists, key = { "live:${it.id}" }) { list ->
                LiveListLine(list, liveCovers[list.id].orEmpty(), onClick = { onOpen(LiveListRoute(list.id)) }, onLongClick = { sheets.show(PlaylistSheet.LiveOptions(list.id, list.name)) })
            }
            sortedRows(playlists?.items.orEmpty(), key = { it.id }) { playlist ->
                PlaylistLine(
                    playlist.name,
                    if (playlist.family) "By Family · ${songs(playlist.songCount)}" else songs(playlist.songCount),
                    onClick = { onOpen(PlaylistRoute(playlist.id)) },
                    onServer = playlist.onServer,
                    onLongClick = { menus.open(CollectionTarget.Playlist(playlist.id)) },
                ) {
                    PlaylistArtwork(playlist.id, playlist.name, playlist.covers, 56.dp, footer = phonePlaylistFooter(playlist.songCount), glyph = playlistGlyph(playlist.octoNotice, null)) {
                        PlaylistCover(playlist.covers, 56.dp)
                    }
                }
            }
            octoListRows(octoLists, onOpen)
        }
    }
}
