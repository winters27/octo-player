package app.winters.octo.ui.genre

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.common.picked
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import app.winters.octo.design.OctoIcons
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.PlayShuffleButtons
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.nav.AlbumRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = GenreViewModel.Factory::class)
class GenreViewModel @AssistedInject constructor(
    @Assisted private val name: String,
    dao: CatalogDao,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
    loaders: ListLoaders,
    reads: LibraryReads,
    db: CatalogDatabase,
) : ViewModel() {
    val albums: StateFlow<List<AlbumEntity>> =
        dao.genreAlbums(name).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The genre's chosen order, one order for every genre; null until read.
    val order: StateFlow<SortOrder?> = sorted.settings.order(SortList.GenreSongs).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var handle: ListHandle<SortOrder, TrackEntity>? = null

    // The genre's songs in pages, in the chosen order.
    val tracks: StateFlow<ListHandle<SortOrder, TrackEntity>?> = order.filterNotNull().map { order ->
        handle?.also { it.ask(order) } ?: ListHandle(
            viewModelScope,
            order,
            { o -> loaders.songs(SongScope.Genre(name), o) },
            reads,
            { _, loader -> db.tableChanges(loader.tables) },
        ).also { handle = it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun spec() = order.value?.let { ListSpec.Songs(SongScope.Genre(name), it) }

    // Plays the genre's songs from the one tapped, or from the top.
    fun play(item: ListItem<TrackEntity>?) {
        val spec = spec() ?: return
        playback.playList(spec, item?.let { ListStart.Row(it.row.id, it.key) }, source = name)
    }

    fun shuffle() {
        spec()?.let { playback.playList(it, null, shuffle = true, source = name) }
    }

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(SortList.GenreSongs, order) }
    }

    @AssistedFactory
    interface Factory {
        fun create(name: String): GenreViewModel
    }
}

// Album cards are at least this wide, as in the Library's grid.
private val CardWidth = 150.dp
private val CardGap = 14.dp

// One genre: play it all, its albums as a grid, then its songs.
@Composable
fun GenreScreen(
    name: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: GenreViewModel = hiltViewModel<GenreViewModel, GenreViewModel.Factory> { it.create(name) },
) {
    val albumList by vm.albums.collectAsStateWithLifecycle()
    val handle by vm.tracks.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val list = handle ?: return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The grid sits in the song list, so its rows are laid out here: as
        // many cards as fit, all the same width.
        val columns = ((maxWidth - 40.dp + CardGap) / (CardWidth + CardGap)).toInt().coerceAtLeast(1)
        val albumRows = albumList.chunked(columns)
        val above = 1 + albumRows.size + 1
        // A new order scrolls back to the songs' own line, below the header
        // and the albums.
        order?.let { TopOnNewOrder(it, state, top = 1 + albumRows.size) }
        val meta by list.shown.collectAsStateWithLifecycle()
        val count = meta?.count
        val tracks = rememberListRows(list, state, above = above)
        PagedPicks(list, tracks, pickOf = { key, item -> if (item.row.id == key) Pickable(key, item.row, item.key).picked() else null }, base = order?.let { ListSpec.Songs(SongScope.Genre(name), it) }) {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
                item(key = "header") {
                    PageHeader(
                        name,
                        onBack,
                        // What the genre holds, when it fits after its name.
                        count = if (count == null) null else listOfNotNull(albumList.size.takeIf { it > 0 }?.let(::albums), songs(count)).joinToString(" • "),
                        actions = { PlayShuffleButtons({ vm.play(null) }, vm::shuffle, enabled = (count ?: 0) > 0) },
                    )
                }
                items(albumRows, key = { "albums:${it.first().id}" }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(CardGap),
                    ) {
                        row.forEach { album ->
                            AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.weight(1f), width = null)
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                val sorted = order
                item(key = "songs") { if (sorted != null && (count ?: 0) > 0) SortBar(SortList.GenreSongs, sorted, vm::setOrder, title = "Songs") }
                listRows(tracks, null, heading = {}) { item ->
                    if (item == null) RowPlaceholder() else SongRow(item.row) { vm.play(item) }
                }
            }
        }
    }
}

