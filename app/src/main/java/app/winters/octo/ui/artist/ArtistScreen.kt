package app.winters.octo.ui.artist

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.artistSongOrder
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.ArtistExtras
import app.winters.octo.discovery.ArtistExtrasSource
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.SimilarArtist
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.PlayHistory
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.rowAlbum
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CHECK_SETTLE_MS
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.FavouriteHeart
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.PlayShuffle
import app.winters.octo.ui.common.QuietAction
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
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.OnlineArtistRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    dao: CatalogDao,
    private val extrasSource: ArtistExtrasSource,
    history: PlayHistory,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
    private val discovery: Discovery,
    private val feedback: Feedback,
    downloads: Downloads,
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
    // Nothing until it answers, and nothing at all without a server.
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Their songs in the library, the most played first once any have been
    // played, otherwise album by album.
    val songs: StateFlow<List<TrackEntity>> =
        combine(dao.artistTracks(id), history.tracks) { tracks, played -> artistSongOrder(tracks, played) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Whether a server is signed in, so there can be a radio.
    val radio: StateFlow<Boolean> =
        discovery.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Plays all their songs in the order shown, or shuffled.
    fun play(shuffle: Boolean) {
        playback.playTracks(songs.value.map { it.id }, 0, shuffle)
    }

    fun playSong(index: Int) {
        playback.playTracks(songs.value.map { it.id }, index)
    }

    // Plays their most played song, then songs like it from the server.
    // When the server cannot answer, nothing changes.
    fun startRadio() {
        val seed = songs.value.firstOrNull() ?: return
        viewModelScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { discovery.radio(seed) }
            } catch (e: SubsonicException) {
                Log.w("Octo", "artist radio failed: ${e.javaClass.simpleName}")
                feedback.show("Could not start a radio for this artist")
                return@launch
            }
            // Only the song itself back means the server found nothing like it.
            if (found.size > 1) {
                playback.playTracks(found.map { it.id }, 0)
            } else {
                feedback.show("No similar songs found")
            }
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

    // Plays the top songs from one of them.
    fun playTop(index: Int) {
        val songs = extras.value?.topSongs ?: return
        playback.playTracks(songs.map { it.id }, index)
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
    val extras by vm.extras.collectAsStateWithLifecycle()
    val top = extras?.topSongs.orEmpty()
    val about = extras?.about
    val similar = extras?.similar.orEmpty()
    val ownSongs by vm.songs.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    var allSongs by rememberSaveable { mutableStateOf(false) }
    PageArtwork(ArtistRoute(id), artist?.artwork)
    val grid = rememberLazyGridState()
    // A new order scrolls back to the albums' own line, below the header,
    // the buttons, the songs and any top songs.
    val above = listOf(artist != null, artist != null, ownSongs.isNotEmpty(), top.isNotEmpty()).count { it }
    sortedAlbums?.let { TopOnNewOrder(it.order, grid, top = above) }

    Refreshable(onRefresh = vm::reloadExtras) {
        ArtistGrid(grid) {
            artist?.let { a ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHeader(a.artwork, a.name, "${albums(a.albumCount)} • ${songs(a.songCount)}")
                }
            }
            // The heart always; Shuffle and Play once there are songs, with
            // the radio as a quiet action on the left.
            if (artist != null) {
                wide("buttons") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 20.dp).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            // A radio needs the server, to find songs like these.
                            if (radio && ownSongs.isNotEmpty()) QuietAction(OctoIcons.Radio, "Radio", onClick = vm::startRadio)
                        }
                        FavouriteHeart(FavouriteKind.Artist, id)
                        if (ownSongs.isNotEmpty()) {
                            PlayShuffle(onPlay = { vm.play(shuffle = false) }, onShuffle = { vm.play(shuffle = true) })
                        }
                    }
                }
            }
            if (ownSongs.isNotEmpty()) {
                wide("songs") {
                    Column {
                        val more = ownSongs.size > ARTIST_SONGS
                        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Songs", Modifier.weight(1f))
                            if (more) QuietButton(if (allSongs) "Show fewer" else "See all") { allSongs = !allSongs }
                        }
                        val shown = if (allSongs) ownSongs else ownSongs.take(ARTIST_SONGS)
                        shown.forEachIndexed { index, track ->
                            SongRow(track, subtitle = { it.album }) { vm.playSong(index) }
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
            // With more than one album, the line carries their sort button.
            val albumsTitle = if (top.isNotEmpty() || ownSongs.isNotEmpty()) "Albums" else null
            val order = sortedAlbums?.order
            if (order != null && albumList.size > 1) {
                wide("albums:title") {
                    SortBar(SortList.ArtistAlbums, order, vm::setOrder, Modifier.padding(top = 8.dp), title = albumsTitle)
                }
            } else if (albumsTitle != null && albumList.isNotEmpty()) {
                wide("albums:title") { SectionTitle(albumsTitle, Modifier.padding(top = 8.dp)) }
            }
            items(albumList, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.animateItem(), width = null)
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
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

// A row across the whole grid. It reaches past the grid's side margins,
// since lists and section titles keep their own.
private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.bleed(GridSideMargin)) { content() }
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

// The top of an artist page: round picture, name, and what they have.
@Composable
fun ArtistHeader(artwork: String?, name: String, details: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, 160.dp, shape = CircleShape)
        Spacer(Modifier.height(16.dp))
        Text(name, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
        Text(
            details,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
