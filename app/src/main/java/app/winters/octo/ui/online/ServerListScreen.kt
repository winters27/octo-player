package app.winters.octo.ui.online

import app.winters.octo.ui.common.factsLine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.ambient.PageArtwork
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.OnlineKind
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.playback.QueueSource
import app.winters.octo.playback.Shown
import app.winters.octo.playback.encoded
import app.winters.octo.ui.album.AlbumHeader
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CHECK_SETTLE_MS
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LoadStateContent
import app.winters.octo.ui.common.SongLead
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.home.stationGoneLine
import app.winters.octo.ui.nav.ServerListRoute
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = ServerListViewModel.Factory::class)
class ServerListViewModel @AssistedInject constructor(
    @Assisted private val route: ServerListRoute,
    private val discovery: Discovery,
    private val downloads: Downloads,
    private val playback: PlaybackConnection,
    lists: ServerReads,
    owners: WorkOwners,
    reads: LibraryReads,
) : ViewModel() {
    // The songs the list has lined up, read into the page's own rows. A
    // list the server has made anew since it was shown is gone, and Home is
    // told so it can take it off.
    val page = ServerPage(
        viewModelScope, lists, owners, reads,
        if (route.radio) ServerListKind.Station else ServerListKind.List,
        route.id,
        gone = {
            discovery.listGone(route.id)
            stationGoneLine(route.name)
        },
    )

    init {
        page.reload()
        // A song from here that joins the library turns into a library song
        // in the list, once its check has landed.
        viewModelScope.launch {
            downloads.arrived.drop(1).collectLatest {
                delay(CHECK_SETTLE_MS)
                if (page.anyFind { downloads.state(it) == DownloadState.Done }) page.refresh()
            }
        }
    }

    fun reload() = page.reload()

    private fun spec() = ListSpec.Online(if (route.radio) OnlineKind.Station else OnlineKind.List, route.id)

    // A station keeps going past its songs under its own name; a list is
    // named after itself.
    private fun word() = if (route.radio) QueueSource.Station(route.id, route.name).encoded() else null

    private fun overWindow() = (page.state.value as? app.winters.octo.ui.common.LoadState.Ready)?.data?.facts?.count?.let { it > QUEUE_WINDOW } == true

    // Plays the songs shown, from one of them or the top, the queue named
    // after the list.
    fun play(item: ListItem<TrackEntity>?) = playback.playList(
        spec(),
        item?.let { ListStart.Row(it.row.id, it.key) },
        source = route.name,
        shownOverWindow = overWindow(),
        shown = item?.let { Shown(it.row.title, it.row.artist, it.row.artwork) } ?: Shown(route.name, null, route.artwork),
        word = word(),
    )

    fun shuffle() = playback.playList(
        spec(),
        null,
        shuffle = true,
        source = route.name,
        shownOverWindow = overWindow(),
        shown = Shown(route.name, null, route.artwork),
        word = word(),
    )

    override fun onCleared() = page.close()

    @AssistedFactory
    interface Factory {
        fun create(route: ServerListRoute): ServerListViewModel
    }
}

// A list the server makes, such as a station or a Made for you list: its
// songs, which play from any of them. A station shows the songs it has lined
// up and keeps going once played. Nothing in it can be edited here.
@Composable
fun ServerListScreen(
    route: ServerListRoute,
    onBack: () -> Unit,
    vm: ServerListViewModel = hiltViewModel<ServerListViewModel, ServerListViewModel.Factory> { it.create(route) },
) {
    val state by vm.page.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val items = rememberListRows(vm.page.rows, listState, above = 1)

    Box(Modifier.fillMaxSize()) {
        PageArtwork(route, route.artwork)
        LoadStateContent(state, onRetry = vm::reload) { shown ->
            val facts = shown.facts
            // The length only adds up when every song's length is known.
            val length = facts.lengthMs?.takeIf { facts.count > 0 }?.let { (it / 1000).toInt().asLength() }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "header") {
                    AlbumHeader(
                        artwork = route.artwork,
                        title = route.name,
                        artist = "Octo",
                        details = factsLine(listOfNotNull(songs(facts.count), length)),
                        onArtist = null,
                        onPlay = { vm.play(null) },
                        onShuffle = vm::shuffle,
                    )
                }
                val mixed = facts.finds > 0
                listRows(items, null, heading = {}) { item ->
                    if (item == null) RowPlaceholder() else SongRow(item.row, SongLead.Artwork, ownership = mixed) { vm.play(item) }
                }
            }
        }
        BackButton(onBack)
    }
}
