package app.winters.octo.ui.online

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.OnlineArtistPage
import app.winters.octo.ui.artist.ArtistGrid
import app.winters.octo.ui.artist.ArtistHeader
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.LoadStateContent
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.nav.OnlineAlbumRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = OnlineArtistViewModel.Factory::class)
class OnlineArtistViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val discovery: Discovery,
) : ViewModel() {
    private val _page = MutableStateFlow<LoadState<OnlineArtistPage>>(LoadState.Loading)
    val page: StateFlow<LoadState<OnlineArtistPage>> = _page.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _page.value = LoadState.Loading
        viewModelScope.launch { _page.value = loadOnline { discovery.artist(id) } }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: String): OnlineArtistViewModel
    }
}

// An artist on the server that is not in the library, with their albums.
@Composable
fun OnlineArtistScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: OnlineArtistViewModel = hiltViewModel<OnlineArtistViewModel, OnlineArtistViewModel.Factory> { it.create(id) },
) {
    val page by vm.page.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        LoadStateContent(page, onRetry = vm::reload) { found ->
            ArtistGrid {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHeader(found.artist.artwork, found.artist.name, albums(found.albums.size))
                }
                items(found.albums, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { onOpen(OnlineAlbumRoute(album.id)) }, width = null)
                }
            }
        }
        BackButton(onBack)
    }
}
