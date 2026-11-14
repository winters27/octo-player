package app.winters.octo.ui.artist

import app.winters.octo.ui.common.factsLine
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.ambient.PageArtwork
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.collectAsLazyPagingItems
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.groupReleases
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PLAY_TOTAL
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import app.winters.octo.catalog.splitLines
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.ArtistExtras
import app.winters.octo.discovery.ArtistExtrasSource
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.SimilarArtist
import app.winters.octo.discovery.inLibraryOnly
import app.winters.octo.player.PlayerSettings
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.playback.LibraryRadio
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.RadioAround
import app.winters.octo.playback.Shown
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.rowAlbum
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CHECK_SETTLE_MS
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.FavouriteHeart
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.HeaderButton
import app.winters.octo.ui.common.HeaderControlLine
import app.winters.octo.ui.common.PlayShuffleButtons
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.menu.SongMenuContext
import app.winters.octo.ui.menu.playRadio
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.OnlineArtistRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    dao: CatalogDao,
    private val extrasSource: ArtistExtrasSource,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
    private val libraryRadio: LibraryRadio,
    private val feedback: Feedback,
    downloads: Downloads,
    player: PlayerSettings,
    private val loaders: ListLoaders,
    private val reads: LibraryReads,
    db: CatalogDatabase,
    private val owned: OwnedFinds,
) : ViewModel() {
    val artist: StateFlow<ArtistEntity?> =
        dao.artist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Their albums in the chosen order, newest first to begin with. One
    // order for every artist's page.
    val albums: StateFlow<Sorted<AlbumEntity>?> =
        sorted.albums(SortList.ArtistAlbums, artistId = id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(SortList.ArtistAlbums, order) }
    }

    // Goes up by one each time the page is pulled, to ask the server again.
    private val reload = MutableStateFlow(0)

    // What the server adds: top songs, a biography and similar artists.
    // Nothing until it answers, and nothing at all without a server. With
    // "Library songs only" on, only what is in the library.
    val extras: StateFlow<ArtistExtras?> = dao.artist(id)
        .filterNotNull()
        .distinctUntilChanged { a, b -> a.id == b.id && a.name == b.name }
        .combine(reload) { artist, _ -> artist }
        .mapLatest { artist ->
            try {
                extrasSource.forArtist(artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "artist extras failed: ${e.javaClass.simpleName}")
                null
            }
        }
        .combine(player.prefs.map { it.libraryOnly }.distinctUntilChanged()) { extras, libraryOnly ->
            if (libraryOnly) extras?.inLibraryOnly(owned.ids.value) else extras
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Their songs in the library, the most played first once any have been
    // played, otherwise album by album: read in pages, never whole.
    private val spec = ListSpec.Artist(id, ArtistOrder.Page)
    private val loader = loaders.artist(id, ArtistOrder.Page)

    // The first songs, for the page's short list, and one more to say
    // whether there are more than it shows.
    val songs: StateFlow<List<Keyed<TrackEntity>>> = db.tableChanges(loader.tables).onStart { emit(Unit) }.conflate()
        .map { firstSongs(ARTIST_SONGS + 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Every song, in pages, once "See all" is on.
    val pages = ListHandle(viewModelScope, Unit, { loaders.artist(id, ArtistOrder.Page) }, reads, { _, l -> db.tableChanges(l.tables) })

    private suspend fun firstSongs(limit: Int): List<Keyed<TrackEntity>> =
        reads.read(PLAY_TOTAL in loader.tables, prepare = {}) { loader.after(null, limit) }

    // Whether there can be a radio: always, since the library alone can make one.
    val radio: StateFlow<Boolean> = MutableStateFlow(true)

    // Plays all their songs in the order shown, or shuffled.
    fun play(shuffle: Boolean) {
        playback.playList(spec, null, shuffle, artist.value?.name)
    }

    fun playSong(song: Keyed<TrackEntity>) {
        playback.playList(spec, ListStart.Row(song.row.id, song.key), source = artist.value?.name)
    }

    // Plays their most played song, then songs like theirs, theirs among them.
    fun startRadio() {
        viewModelScope.launch {
            val seed = firstSongs(1).firstOrNull()?.row ?: return@launch
            playRadio(seed, playback, RadioAround.Artist(id), Shown(artist.value?.name ?: seed.artist, null, artist.value?.artwork))
        }
    }

    init {
        // A top song downloaded from here turns into a library song once
        // its check has landed.
        viewModelScope.launch {
            downloads.arrived.drop(1).collectLatest {
                delay(CHECK_SETTLE_MS)
                val top = extras.value?.topSongs.orEmpty()
                if (top.any { isFind(it.id) && downloads.state(it.id) == DownloadState.Done }) reloadExtras()
            }
        }
    }

    // A pull on the page: what the server knows about the artist is asked for again.
    fun reloadExtras() {
        extrasSource.forget(id)
        reload.update { it + 1 }
    }

    override fun onCleared() {
        pages.close()
    }

    // Plays the top songs from one of them.
    fun playTop(index: Int) {
        val songs = extras.value?.topSongs ?: return
        playback.playTracks(songs.map { it.id }, index, source = artist.value?.name)
    }

    @AssistedFactory
    interface Factory {
        fun create(id: String): ArtistViewModel
    }
}

@Composable
fun ArtistScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: ArtistViewModel = hiltViewModel<ArtistViewModel, ArtistViewModel.Factory> { it.create(id) },
) {
    val artist by vm.artist.collectAsStateWithLifecycle()
    val sortedAlbums by vm.albums.collectAsStateWithLifecycle()
    val albumList = sortedAlbums?.items.orEmpty()
    // Their albums on shelves by kind when the server tagged them, as the
    // desktop shows them; else one shelf.
    val shelves = remember(albumList) { groupReleases(albumList, { splitLines(it.releaseTypes) }) }
    val extras by vm.extras.collectAsStateWithLifecycle()
    val top = extras?.topSongs.orEmpty()
    val about = extras?.about
    val similar = extras?.similar.orEmpty()
    val ownSongs by vm.songs.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    var allSongs by rememberSaveable { mutableStateOf(false) }
    PageArtwork(ArtistRoute(id), artist?.artwork)
    val grid = rememberLazyGridState()
    val everySong = vm.pages.pages.collectAsLazyPagingItems()
    // With "See all" on, every song is a line of its own after the songs'
    // title line.
    val pagedSongs = if (allSongs && ownSongs.isNotEmpty()) everySong.itemCount else 0
    val songsStart = listOf(artist != null, ownSongs.isNotEmpty()).count { it }
    // A new order scrolls back to the albums' own line, below the header,
    // the songs and any top songs.
    val above = songsStart + pagedSongs + (if (top.isNotEmpty()) 1 else 0)
    sortedAlbums?.let { TopOnNewOrder(it.order, grid, top = above) }
    // The first song line in view tells the list where to read again from.
    LaunchedEffect(grid, everySong, allSongs, songsStart) {
        snapshotFlow { grid.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            val at = index - songsStart
            vm.pages.visible(if (allSongs && at in 0 until everySong.itemCount) everySong.peek(at)?.key else null)
        }
    }

    Refreshable(onRefresh = vm::reloadExtras) {
        ArtistGrid(grid) {
            // The heart leads the control line; the radio, Play and Shuffle
            // sit at its end once there are songs. A radio needs the server,
            // to find songs like these.
            // Across the whole screen, so the control line keeps the album
            // page's insets.
            artist?.let { a ->
                wide("header") {
                    ArtistHeader(
                        a.artwork,
                        a.name,
                        factsLine(listOf(albums(a.albumCount), songs(a.songCount))),
                        control = { FavouriteHeart(FavouriteKind.Artist, id) },
                        actions = if (ownSongs.isEmpty()) null else ({
                            if (radio) HeaderButton(OctoIcons.Radio, "Radio", vm::startRadio)
                            PlayShuffleButtons({ vm.play(shuffle = false) }, { vm.play(shuffle = true) })
                        }),
                    )
                }
            }
            if (ownSongs.isNotEmpty()) {
                // With every song shown, the rows follow the title at once,
                // as the first few do.
                wide("songs", gap = !allSongs) {
                    Column {
                        val more = ownSongs.size > ARTIST_SONGS
                        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Songs", Modifier.weight(1f))
                            if (more) QuietButton(if (allSongs) "Show fewer" else "See all") { allSongs = !allSongs }
                        }
                        if (!allSongs) {
                            ownSongs.take(ARTIST_SONGS).forEach { song ->
                                SongRow(song.row, subtitle = { it.album }) { vm.playSong(song) }
                            }
                        }
                    }
                }
                if (allSongs) {
                    items(
                        count = everySong.itemCount,
                        key = { at -> everySong.peek(at)?.key?.token?.let { "song:$it" } ?: "song-at:$at" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { at ->
                        val song = everySong[at]
                        // Rows sit edge to edge, as the first few do; the
                        // last keeps the grid's gap below it.
                        val gap = if (at == everySong.itemCount - 1) ArtistRowGap else 0.dp
                        Box(Modifier.bleed(GridSideMargin).padding(bottom = gap)) {
                            if (song == null) {
                                RowPlaceholder()
                            } else {
                                SongRow(song.row, subtitle = { it.album }) { vm.playSong(Keyed(song.key, song.row)) }
                            }
                        }
                    }
                }
            }
            if (top.isNotEmpty()) {
                wide("top") {
                    Column {
                        SectionTitle("Top songs")
                        // Once offered here, kept while the page is open, so the
                        // lengths do not shift when the last find turns into a
                        // library song.
                        var offered by rememberSaveable(id) { mutableStateOf(false) }
                        val offerAdd = offered || top.any { isFind(it.id) }
                        SideEffect { offered = offerAdd }
                        top.forEachIndexed { index, track ->
                            // A song found online can be added from here; with none
                            // among them, library rows need no space kept for the button.
                            SongRow(track, subtitle = ::rowAlbum, menuContext = SongMenuContext(artistId = id), offerAdd = offerAdd) { vm.playTop(index) }
                        }
                    }
                }
            }
            // Once songs come first, the albums need a name of their own.
            // With more than one album, the first line carries their sort
            // button. Several shelves (Albums, Singles and EPs...) each
            // have their name.
            val albumsTitle = if (top.isNotEmpty() || ownSongs.isNotEmpty()) "Albums" else null
            val order = sortedAlbums?.order
            shelves.forEachIndexed { index, (shelf, albums) ->
                val title = if (shelves.size > 1) shelf.title else albumsTitle
                val key = if (index == 0) "albums:title" else "albums:${shelf.name}"
                if (index == 0 && order != null && albumList.size > 1) {
                    wide(key) {
                        SortBar(SortList.ArtistAlbums, order, vm::setOrder, Modifier.padding(top = 8.dp), title = title)
                    }
                } else if (title != null) {
                    wide(key) { SectionTitle(title, Modifier.padding(top = 8.dp)) }
                }
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.animateItem().padding(bottom = ArtistRowGap), width = null)
                }
            }
            about?.let { text ->
                wide("about") {
                    Column {
                        SectionTitle("About", Modifier.padding(top = 8.dp))
                        About(text)
                    }
                }
            }
            if (similar.isNotEmpty()) {
                wide("similar") {
                    Column {
                        SectionTitle("Similar artists", Modifier.padding(top = 8.dp))
                        SimilarRow(similar, onOpen)
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

// How many songs an artist page shows before "See all".
private const val ARTIST_SONGS = 10

// Artists like this one, in a row. A library artist opens in the library;
// any other opens as the server has them.
@Composable
private fun SimilarRow(similar: List<SimilarArtist>, onOpen: (NavKey) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(similar, key = { it.libraryId ?: "online:${it.serverId}" }) { other ->
            ArtistCircle(OnlineArtist(other.serverId, other.name, 0, other.artwork), outside = other.libraryId == null) {
                onOpen(other.libraryId?.let(::ArtistRoute) ?: OnlineArtistRoute(other.serverId))
            }
        }
    }
}

// How far the grid keeps its columns from the screen's sides.
private val GridSideMargin = 20.dp

// The room below each line of an artist page's grid. Lines keep it
// themselves rather than the grid, so the songs' rows, each a line of the
// grid, sit together as one list.
private val ArtistRowGap = 18.dp

// An artist page's grid: the header across the top, then albums in columns.
@Composable
fun ArtistGrid(state: LazyGridState = rememberLazyGridState(), content: LazyGridScope.() -> Unit) {
    val padding = screenPadding(extraTop = DetailTopGap)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = GridSideMargin,
            end = GridSideMargin,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

// A row across the whole grid, with the grid's gap below it unless `gap` is
// off. It reaches past the grid's side margins, since lists and section
// titles keep their own.
private fun LazyGridScope.wide(key: String, gap: Boolean = true, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.bleed(GridSideMargin).padding(bottom = if (gap) ArtistRowGap else 0.dp)) { content() }
    }
}

// Lays the content out wider than it was given, by `side` on each side.
private fun Modifier.bleed(side: Dp) = layout { measurable, constraints ->
    val extra = side.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

// The biography, four lines at first; "More" shows the rest.
@Composable
private fun About(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    var clipped by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text,
            style = OctoType.bodySmall,
            color = OctoColors.TextPrimary.copy(alpha = 0.8f),
            maxLines = if (open) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) clipped = it.hasVisualOverflow },
        )
        if (clipped && !open) {
            Text(
                "More",
                style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
                color = OctoColors.TextPrimary,
                modifier = Modifier
                    .clickable(role = Role.Button) { open = true }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// The top of an artist page: round picture, name, and what they have, then
// one control line with `control` first and `actions` at the end.
@Composable
fun ArtistHeader(
    artwork: String?,
    name: String,
    details: String,
    control: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(artwork, 160.dp, shape = CircleShape)
            Spacer(Modifier.height(16.dp))
            Text(name, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                details,
                style = OctoType.bodySmall,
                color = OctoColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 20.dp, end = 20.dp),
            )
        }
        if (control != null || actions != null) {
            HeaderControlLine(control, actions = actions, modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp))
        }
    }
}
