package app.winters.octo.ui.livelists

import app.winters.octo.covers.LIVE_LIST_COVER_LINE
import app.winters.octo.ui.common.PlaylistArtwork
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.livelists.LiveShown
import app.winters.octo.livelists.liveListSummary
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.Shown
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.picked
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import app.winters.octo.ui.playlist.EmptyNote
import app.winters.octo.ui.playlist.ListHeader
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistCover
import app.winters.octo.ui.playlist.PlaylistSheet
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// A live list as its screen knows it: the list, null once deleted.
data class LiveListPage(val list: LiveList?)

// What a live list shows of itself: its songs in pages, worked out in the
// database and kept up to date there while the page is open, its count and
// length, and its covers.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = LiveListViewModel.Factory::class)
class LiveListViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    store: LiveListStore,
    songs: LiveListSongs,
    private val playback: PlaybackConnection,
    private val totals: PlayTotals,
    reads: LibraryReads,
    db: CatalogDatabase,
) : ViewModel() {
    // Whether play counts could not be brought up to date, which a list
    // that reads them waits on; Try again asks once more.
    val totalsFailed: StateFlow<Boolean> = totals.failed

    fun tryAgain() = totals.tryAgain()

    // Null until first read.
    val page: StateFlow<LiveListPage?> = store.lists.map { lists -> LiveListPage(lists.firstOrNull { it.id == id }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val owner = songs.owner(id)

    // The songs the list picks now, kept up to date with the library,
    // likes and plays while the page is open: loading before the first
    // walk, then its count and length, or why it could not be worked out.
    val shown: StateFlow<LiveShown> = songs.watch(owner, id, page.map { it?.list?.query }.filterNotNull())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveShown.Loading)

    // The covers of its first albums.
    val covers: StateFlow<List<String>> = page.map { it?.list }.distinctUntilChanged()
        .flatMapLatest { list -> if (list == null) emptyFlow() else songs.coversOf(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The songs in pages, in the list's order.
    val rows: ListHandle<Unit, TrackEntity> = ListHandle(viewModelScope, Unit, { songs.pages(owner) }, reads, { _, loader -> db.tableChanges(loader.tables) })

    fun spec(): ListSpec.Live? = page.value?.list?.let { ListSpec.Live(it.id, it.query, it.name) }

    // Plays the list from the song tapped, or from the top.
    fun play(item: ListItem<TrackEntity>?) {
        val spec = spec() ?: return
        playback.playList(
            spec,
            item?.let { ListStart.Row(it.row.id, it.key) },
            source = spec.name,
            shown = item?.let { Shown(it.row.title, it.row.artist, it.row.artwork) },
        )
    }

    fun shuffle() {
        val spec = spec() ?: return
        playback.playList(spec, null, shuffle = true, source = spec.name, shown = Shown(spec.name))
    }

    override fun onCleared() = rows.close()

    @AssistedFactory
    interface Factory {
        fun create(id: String): LiveListViewModel
    }
}

// One live list: its picture, name and size, one quiet line saying what
// it picks, Play and Shuffle, and its songs. More opens its options, where
// its rules are edited.
@Composable
fun LiveListScreen(
    id: String,
    onBack: () -> Unit,
    vm: LiveListViewModel = hiltViewModel<LiveListViewModel, LiveListViewModel.Factory> { it.create(id) },
) {
    val page by vm.page.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val totalsFailed by vm.totalsFailed.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    // Leaves the screen once the list is deleted.
    val gone = page?.let { it.list == null } ?: false
    LaunchedEffect(gone) { if (gone) onBack() }
    val state = rememberLazyListState()
    val items = rememberListRows(vm.rows, state, above = 1)

    Box(Modifier.fillMaxSize()) {
        val list = page?.list
        // The list as it shows now, or as it showed before a walk that
        // could not finish; nothing yet before its first walk.
        val now = when (val s = shown) {
            is LiveShown.Shown -> s
            is LiveShown.Problem -> s.before
            LiveShown.Loading -> null
        }
        val problem = (shown as? LiveShown.Problem)?.words
        // The header shows as soon as the list is read, its size and length
        // joining once the first walk has them; the rows fill in under it.
        if (list != null) {
            PagedPicks(vm.rows, items, pickOf = { key, item -> if (item.row.id == key) Pickable(key, item.row, item.key).picked() else null }, base = vm.spec()) {
                LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                    item(key = "header") {
                        Column {
                            ListHeader(
                                title = list.name,
                                songCount = now?.count,
                                durationMs = now?.durationMs ?: 0,
                                onPlay = { vm.play(null) },
                                onShuffle = vm::shuffle,
                                onMore = { sheets.show(PlaylistSheet.LiveOptions(list.id, list.name)) },
                                // The rules that pick the songs join the size and length.
                                facts = listOf(liveListSummary(list.query, null)),
                            ) { modifier, shape ->
                                PlaylistArtwork(list.id, list.name, covers, 240.dp, modifier, shape, line = LIVE_LIST_COVER_LINE) {
                                    if (covers.isEmpty()) LiveMarkTile(240.dp, modifier, shape) else PlaylistCover(covers, 240.dp, modifier, shape)
                                }
                            }
                            if (problem != null) ProblemLine(problem, if (totalsFailed) vm::tryAgain else null)
                        }
                    }
                    if (now != null && now.count == 0) item(key = "empty") { EmptyNote(NO_MATCHES_NOTE) }
                    listRows(items, null, heading = {}) { item ->
                        if (item == null) RowPlaceholder() else SongRow(item.row) { vm.play(item) }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

// Why a live list could not be worked out again, above what it showed,
// with Try again when play counts are what failed.
@Composable
internal fun ProblemLine(words: String, onTryAgain: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(words, style = OctoType.caption, color = OctoColors.TextMuted)
        if (onTryAgain != null) AccentButton("Try again", onClick = onTryAgain, size = ButtonSize.Small, modifier = Modifier.padding(top = 8.dp))
    }
}

const val NO_MATCHES_NOTE = "No songs match right now. As songs are added, played and liked, the ones that match show up here."
