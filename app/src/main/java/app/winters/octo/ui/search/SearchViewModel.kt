package app.winters.octo.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class SearchResults(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val songs: List<TrackEntity>,
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

@HiltViewModel
class SearchViewModel @Inject constructor(private val dao: CatalogDao) : ViewModel() {
    var text by mutableStateOf("")

    // Null until at least two characters are typed.
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults?> = snapshotFlow { text }
        .debounce(150)
        .map { searchKey(it) }
        .distinctUntilChanged()
        .mapLatest { q ->
            if (q.length < 2) {
                null
            } else {
                SearchResults(dao.searchArtists(q, 10), dao.searchAlbums(q, 20), dao.searchTracks(q, 50))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
