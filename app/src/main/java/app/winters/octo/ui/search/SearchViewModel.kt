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
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.Discovery
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

// How long typing has to pause before the server is asked.
private const val DISCOVER_AFTER_MS = 400L

class SearchResults(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val songs: List<TrackEntity>,
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

// What searching the server has come to.
sealed interface DiscoverState {
    // Nothing asked: no server, or too little typed.
    data object Idle : DiscoverState
    data object Loading : DiscoverState
    class Done(val found: Discovered) : DiscoverState
    data object Failed : DiscoverState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val dao: CatalogDao,
    private val discovery: Discovery,
    private val playback: PlaybackConnection,
) : ViewModel() {
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

    // Whether a server is signed in, so there is more to discover.
    val signedIn: StateFlow<Boolean> =
        discovery.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Songs, albums and artists the server has beyond the library. It says
    // it is looking as soon as the text changes, and asks once typing
    // pauses; new text cancels the question before it.
    @OptIn(ExperimentalCoroutinesApi::class)
    val discover: StateFlow<DiscoverState> =
        combine(snapshotFlow { text.trim() }, discovery.available) { q, on -> q.takeIf { on && searchKey(it).length >= 2 } }
            .distinctUntilChanged()
            .transformLatest { q ->
                if (q == null) {
                    emit(DiscoverState.Idle)
                    return@transformLatest
                }
                emit(DiscoverState.Loading)
                delay(DISCOVER_AFTER_MS)
                emit(
                    when (val state = load { discovery.search(q) }) {
                        is LoadState.Ready -> state.data?.let { DiscoverState.Done(it) } ?: DiscoverState.Idle
                        is LoadState.Failed -> DiscoverState.Failed
                        LoadState.Loading -> DiscoverState.Loading
                    },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverState.Idle)

    // Plays the found songs from the one tapped.
    fun playSong(track: TrackEntity) {
        val songs = results.value?.songs ?: return
        playback.playTracks(songs.map { it.id }, songs.indexOf(track).coerceAtLeast(0))
    }

    // Plays the songs found online from the one tapped.
    fun playFound(track: TrackEntity) {
        val songs = (discover.value as? DiscoverState.Done)?.found?.songs ?: return
        playback.playTracks(songs.map { it.id }, songs.indexOf(track).coerceAtLeast(0))
    }
}
