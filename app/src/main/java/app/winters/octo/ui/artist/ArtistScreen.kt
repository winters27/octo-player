package app.winters.octo.ui.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.AlbumRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted id: String,
    dao: CatalogDao,
) : ViewModel() {
    val artist: StateFlow<ArtistEntity?> =
        dao.artist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums: StateFlow<List<AlbumEntity>> =
        dao.artistAlbums(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    val albumList by vm.albums.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        ArtistGrid {
            artist?.let { a ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHeader(a.artwork, a.name, "${albums(a.albumCount)} • ${songs(a.songCount)}")
                }
            }
            items(albumList, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, width = null)
            }
        }
        BackButton(onBack)
    }
}

// An artist page's grid: the header across the top, then albums in columns.
@Composable
fun ArtistGrid(content: LazyGridScope.() -> Unit) {
    val padding = screenPadding(extraTop = DetailTopGap)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
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
