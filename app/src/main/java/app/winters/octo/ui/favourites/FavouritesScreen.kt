package app.winters.octo.ui.favourites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.sort.sortFavourites
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.PlayRow
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.Segmented
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortButton
import app.winters.octo.ui.common.TitleWithSort
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    dao: FavouritesDao,
    private val sorting: SortSettings,
    private val library: SortedLibrary,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Liked songs, the same list in the same order as the Liked songs page.
    // Null until first read.
    val songs: StateFlow<Sorted<TrackEntity>?> =
        library.songs(SortList.Liked, SongScope.Liked).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Favourite albums and artists in the chosen order, one order for both.
    // Null until first read.
    val albums: StateFlow<Sorted<AlbumEntity>?> =
        combine(dao.likedAlbums(), sorting.order(SortList.Favourites)) { rows, order ->
            val sorted = sortFavourites(rows, order, { it.likedAt }, { it.album.sortKey }, { it.album.id })
            Sorted(sorted.map { it.album }, order, null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artists: StateFlow<Sorted<ArtistEntity>?> =
        combine(dao.likedArtists(), sorting.order(SortList.Favourites)) { rows, order ->
            val sorted = sortFavourites(rows, order, { it.likedAt }, { it.artist.sortKey }, { it.artist.id })
            Sorted(sorted.map { it.artist }, order, null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun songIds() = songs.value?.items.orEmpty().map { it.id }

    fun playSongs(index: Int) = playback.playTracks(songIds(), index)

    fun shuffleSongs() = playback.playTracks(songIds(), shuffle = true)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Favourites, order) }
    }

    // Songs share the Liked songs page's order, so the two always agree.
    fun setSongOrder(order: SortOrder) {
        viewModelScope.launch { library.setOrder(SortList.Liked, order) }
    }
}

private val Segments = FavouriteSegment.entries.map { it.label }

// Liked songs, favourite albums and favourite artists, each under its own
// segment. Songs keep the Liked songs order; albums and artists share one
// sort: when they became favourites, or by name. The page opens on liked
// songs when there are any (firstSegment), or on the albums when asked.
@Composable
fun FavouritesScreen(
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    openOnAlbums: Boolean = false,
    vm: FavouritesViewModel = hiltViewModel(),
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(if (openOnAlbums) FavouriteSegment.Albums else null) }
    // The segment to open on is picked once all three are read, then kept,
    // so the page never jumps while it is open.
    val ready = songs != null && albums != null && artists != null
    LaunchedEffect(ready) {
        if (ready && chosen == null) {
            chosen = firstSegment(songs?.items.orEmpty().size, albums?.items.orEmpty().size, artists?.items.orEmpty().size)
        }
    }
    val segment = chosen

    Refreshable {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            TitleWithSort("Favourites") {
                when (segment) {
                    null -> Unit
                    FavouriteSegment.Songs -> songs?.let { SortButton(SortList.Liked, it.order, vm::setSongOrder) }
                    else -> (albums ?: artists)?.let { SortButton(SortList.Favourites, it.order, vm::setOrder) }
                }
            }
            if (segment == null) {
                Loading()
            } else {
                Segmented(
                    Segments,
                    segment.ordinal,
                    { chosen = FavouriteSegment.entries[it] },
                    Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
                )
                when (segment) {
                    FavouriteSegment.Songs -> FavouriteSongs(songs, vm::playSongs, vm::shuffleSongs)
                    FavouriteSegment.Albums -> FavouriteAlbums(albums, onOpen)
                    FavouriteSegment.Artists -> FavouriteArtists(artists, onOpen)
                }
            }
        }
        BackButton(onBack)
    }
}

@Composable
private fun FavouriteSongs(list: Sorted<TrackEntity>?, onPlay: (Int) -> Unit, onShuffle: () -> Unit) {
    Shown(list, "Heart a song in the player, or long press it and choose Add to Liked songs, to keep it here.") { sorted ->
        val state = rememberLazyListState()
        TopOnNewOrder(sorted.order, state)
        val pickable = remember(sorted.items) { sorted.items.map { Pickable(it.id, it) } }
        SelectableSongs(pickable) {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
                item(key = "play") {
                    PlayRow(
                        details = songs(sorted.items.size),
                        onPlay = { onPlay(0) },
                        onShuffle = onShuffle,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }
                itemsIndexed(sorted.items, key = { _, track -> track.id }) { index, track ->
                    Box(Modifier.animateItem()) { SongRow(track) { onPlay(index) } }
                }
            }
        }
    }
}

@Composable
private fun FavouriteAlbums(albums: Sorted<AlbumEntity>?, onOpen: (NavKey) -> Unit) {
    Shown(albums, "Heart an album from its page or its menu to keep it here.") { sorted ->
        val grid = rememberLazyGridState()
        TopOnNewOrder(sorted.order, grid)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(sorted.items, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.animateItem(), width = null)
            }
        }
    }
}

@Composable
private fun FavouriteArtists(artists: Sorted<ArtistEntity>?, onOpen: (NavKey) -> Unit) {
    Shown(artists, "Heart an artist from their page or their menu to keep them here.") { sorted ->
        val state = rememberLazyListState()
        TopOnNewOrder(sorted.order, state)
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
            items(sorted.items, key = { it.id }) { artist ->
                Box(Modifier.animateItem()) { ArtistRow(artist) { onOpen(ArtistRoute(artist.id)) } }
            }
        }
    }
}

// A spinner until the first read, a note when there are none, the list otherwise.
@Composable
private fun <T> Shown(sorted: Sorted<T>?, empty: String, content: @Composable (Sorted<T>) -> Unit) {
    when {
        sorted == null -> Loading()
        sorted.items.isEmpty() -> Text(
            empty,
            style = OctoType.bodySmall,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        else -> content(sorted)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
    }
}
