package app.winters.octo.ui.favourites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.sortFavourites
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.Segmented
import app.winters.octo.ui.common.SortButton
import app.winters.octo.ui.common.TopOnNewOrder
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
) : ViewModel() {
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

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Favourites, order) }
    }
}

private val Segments = listOf("Albums", "Artists")

// Favourite albums and artists, each under its own segment, with one sort
// for both: when they became favourites, or by name.
@Composable
fun FavouritesScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: FavouritesViewModel = hiltViewModel()) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val order = (albums ?: artists)?.order

    Refreshable {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Favourites", Modifier.weight(1f))
                order?.let { SortButton(SortList.Favourites, it, vm::setOrder, Modifier.padding(end = 10.dp, top = 8.dp, bottom = 16.dp)) }
            }
            Segmented(Segments, segment, { segment = it }, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
            if (segment == 0) FavouriteAlbums(albums, onOpen) else FavouriteArtists(artists, onOpen)
        }
        BackButton(onBack)
    }
}

@Composable
private fun FavouriteAlbums(albums: Sorted<AlbumEntity>?, onOpen: (NavKey) -> Unit) {
    Shown(albums, "Albums you add to favourites show here. Long press an album, or tap the heart on its page.") { sorted ->
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
    Shown(artists, "Artists you add to favourites show here. Long press an artist, or tap the heart on their page.") { sorted ->
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
        sorted == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
        }
        sorted.items.isEmpty() -> Text(
            empty,
            style = OctoType.bodySmall,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        else -> content(sorted)
    }
}
