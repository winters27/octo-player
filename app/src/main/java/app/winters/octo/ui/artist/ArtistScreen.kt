package app.winters.octo.ui.artist

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.ArtistExtras
import app.winters.octo.discovery.ArtistExtrasSource
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.SimilarArtist
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.DownloadButton
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.OnlineArtistRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted id: String,
    dao: CatalogDao,
    extrasSource: ArtistExtrasSource,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
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

    // What the server adds: top songs, a biography and similar artists.
    // Nothing until it answers, and nothing at all without a server.
    val extras: StateFlow<ArtistExtras?> = dao.artist(id)
        .filterNotNull()
        .distinctUntilChanged { a, b -> a.id == b.id && a.name == b.name }
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
    PageArtwork(ArtistRoute(id), artist?.artwork)
    val grid = rememberLazyGridState()
    // A new order scrolls back to the albums' own line, below the header and
    // any top songs.
    sortedAlbums?.let { TopOnNewOrder(it.order, grid, top = (if (artist != null) 1 else 0) + (if (top.isNotEmpty()) 1 else 0)) }

    Box(Modifier.fillMaxSize()) {
        ArtistGrid(grid) {
            artist?.let { a ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHeader(a.artwork, a.name, "${albums(a.albumCount)} • ${songs(a.songCount)}")
                }
            }
            if (top.isNotEmpty()) {
                wide("top") {
                    Column {
                        SectionTitle("Top songs")
                        top.forEachIndexed { index, track ->
                            // A song found online can be downloaded from here.
                            val download: (@Composable () -> Unit)? =
                                if (isFind(track.id)) ({ DownloadButton(track, size = 40.dp, iconSize = 22.dp) }) else null
                            SongRow(track, subtitle = track.album, trailing = download) { vm.playTop(index) }
                        }
                    }
                }
            }
            // Once songs come first, the albums need a name of their own.
            // With more than one album, the line carries their sort button.
            val albumsTitle = if (top.isNotEmpty()) "Albums" else null
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

// Artists like this one, in a row. A library artist opens in the library;
// any other opens as the server has them.
@Composable
private fun SimilarRow(similar: List<SimilarArtist>, onOpen: (NavKey) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(similar, key = { it.libraryId ?: "online:${it.serverId}" }) { other ->
            ArtistCircle(OnlineArtist(other.serverId, other.name, 0, other.artwork)) {
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
