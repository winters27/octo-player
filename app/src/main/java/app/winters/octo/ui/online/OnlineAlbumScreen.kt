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
import androidx.navigation3.runtime.NavKey
import app.winters.octo.ambient.PageArtwork
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.discovery.TopSongsSource
import app.winters.octo.discovery.highlightWalk
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.OnlineKind
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.playback.Shown
import app.winters.octo.ui.album.AlbumHeader
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CHECK_SETTLE_MS
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.LoadStateContent
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.SongLead
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.albumLibraryNote
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.OnlineAlbumRoute
import app.winters.octo.ui.nav.OnlineArtistRoute
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.rememberListRows
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import app.winters.octo.animated.AnimatedArts
import app.winters.octo.subsonic.AnimatedClip
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// How many of an album's rows one walk of them reads at a time.
private const val WALK_PAGE = 500

@HiltViewModel(assistedFactory = OnlineAlbumViewModel.Factory::class)
class OnlineAlbumViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val downloads: Downloads,
    private val playback: PlaybackConnection,
    private val topSongs: TopSongsSource,
    lists: ServerReads,
    owners: WorkOwners,
    reads: LibraryReads,
    animatedArts: AnimatedArts,
) : ViewModel() {
    // The album's square loop, in place of its cover.
    val loop: StateFlow<AnimatedClip?> = animatedArts.pageLoop(flowOf(id))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The album as the server lists it, in the page's own rows.
    val page = ServerPage(viewModelScope, lists, owners, reads, ServerListKind.Album, id)

    // Set once the album is asked for here, cleared if the server says no.
    private val asked = MutableStateFlow(false)

    // Whether the album is on its way: asked for here, or every song from it
    // that is not in the library already asked for, its finds walked a page
    // at a time.
    val downloading: StateFlow<Boolean> = combine(page.state, asked, downloads.states) { state, asked, _ -> state to asked }
        .map { (state, asked) ->
            val finds = (state as? LoadState.Ready)?.data?.facts?.finds ?: 0
            asked || (finds > 0 && !page.anyFind { downloads.state(it) == DownloadState.None })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        page.reload()
        // A song from here that joins the library turns into a library song
        // on the page, once its check has landed.
        viewModelScope.launch {
            downloads.arrived.drop(1).collectLatest {
                delay(CHECK_SETTLE_MS)
                if (page.anyFind { downloads.state(it) == DownloadState.Done }) page.refresh()
            }
        }
    }

    // The album's main song, starred as Apple Music does: the one first in
    // the artist's top songs on the server, found by one walk of the rows.
    // None until known, or when no song here is among them.
    val highlight: StateFlow<String?> = page.state
        .map { (it as? LoadState.Ready)?.data }
        .distinctUntilChanged()
        .map { shown ->
            val artist = shown?.head?.artist ?: return@map null
            val ranked = topSongs.forArtist(artist)
            highlightWalk(ranked) { after -> page.loader.after(after, WALK_PAGE) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reload() = page.reload()

    private fun overWindow() = (page.state.value as? LoadState.Ready)?.data?.facts?.count?.let { it > QUEUE_WINDOW } == true

    // Plays the album from one of its songs, or from the top.
    fun play(item: ListItem<TrackEntity>?) {
        val head = (page.state.value as? LoadState.Ready)?.data?.head
        playback.playList(
            ListSpec.Online(OnlineKind.Album, id),
            item?.let { ListStart.Row(it.row.id, it.key) },
            shownOverWindow = overWindow(),
            shown = item?.let { Shown(it.row.title, it.row.artist, it.row.artwork) } ?: Shown(head?.title, head?.artist, head?.cover),
        )
    }

    fun shuffle() {
        val head = (page.state.value as? LoadState.Ready)?.data?.head
        playback.playList(ListSpec.Online(OnlineKind.Album, id), null, shuffle = true, shownOverWindow = overWindow(), shown = Shown(head?.title, head?.artist, head?.cover))
    }

    // Has the server download the whole album into the library, its songs
    // asked for a page at a time.
    fun download() {
        val head = (page.state.value as? LoadState.Ready)?.data?.head ?: return
        asked.value = true
        viewModelScope.launch {
            if (!downloads.requestAlbumPaged(id, head.title) { emit -> page.findPages(emit) }) asked.value = false
        }
    }

    override fun onCleared() = page.close()

    @AssistedFactory
    interface Factory {
        fun create(id: String): OnlineAlbumViewModel
    }
}

// An album on the server that is not in the library. Songs the library has
// play as usual; the rest can be downloaded one by one or all at once.
@Composable
fun OnlineAlbumScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: OnlineAlbumViewModel = hiltViewModel<OnlineAlbumViewModel, OnlineAlbumViewModel.Factory> { it.create(id) },
) {
    val state by vm.page.state.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val items = rememberListRows(vm.page.rows, listState, above = 1)

    Box(Modifier.fillMaxSize()) {
        LoadStateContent(state, onRetry = vm::reload) { shown ->
            val album = shown.head
            val facts = shown.facts
            PageArtwork(OnlineAlbumRoute(id), album?.cover)
            // The length only adds up when every song's length is known.
            val length = facts.lengthMs?.takeIf { facts.count > 0 }?.let { (it / 1000).toInt().asLength() }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "header") {
                    val loop by vm.loop.collectAsStateWithLifecycle()
                    // The cover stays clean. More adds the album to the
                    // library; for an album partly there, the facts say how
                    // much of it is.
                    val outside = facts.finds > 0
                    val note = if (outside) albumLibraryNote(facts.count, facts.finds) else null
                    AlbumHeader(
                        artwork = album?.cover,
                        loop = loop,
                        title = album?.title.orEmpty(),
                        artist = album?.artist.orEmpty(),
                        details = factsLine(listOfNotNull(album?.year?.toString(), songs(album?.songCount ?: facts.count), length, note)),
                        onArtist = album?.artistId?.takeIf { it.isNotEmpty() }?.let { artistId -> { onOpen(OnlineArtistRoute(artistId)) } },
                        onPlay = { vm.play(null) },
                        onShuffle = vm::shuffle,
                        more = if (outside) {
                            listOf(MoreItem(if (downloading) "Adding" else "Add to your library", OctoIcons.AddToLibrary, enabled = !downloading, onClick = vm::download))
                        } else {
                            emptyList()
                        },
                    )
                }
                // Songs found online have no track numbers, so every song is
                // numbered by where it sits on the album. As on a library
                // album, whether each song is in the library is said beside
                // its number, so the rows run to the edge of the screen and
                // no space is kept empty at their end.
                val mixed = facts.finds > 0
                items(items.itemCount, key = { at -> items.peek(at)?.key?.token ?: "row:$at" }) { index ->
                    val item = items[index]
                    if (item == null) {
                        RowPlaceholder(numbered = true)
                    } else {
                        // Only say who is singing when it is not the album's artist.
                        SongRow(
                            item.row,
                            SongLead.Number(index + 1),
                            subtitle = { it.artist.takeIf { artist -> artist != album?.artist } },
                            ownership = mixed,
                            highlight = item.row.id == highlight,
                        ) { vm.play(item) }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}
