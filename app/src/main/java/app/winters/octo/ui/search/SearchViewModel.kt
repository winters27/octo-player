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
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.Discovery
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
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
import kotlinx.coroutines.launch
import javax.inject.Inject

// How long typing has to pause before the server is asked.
private const val DISCOVER_AFTER_MS = 400L

// Which kind of result a search shows: everything, or one kind in full.
enum class SearchFilter(val label: String) {
    All("All"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
}

// How many of each kind a search shows. Everything at once shows a few of
// each; one kind picked shows all of it, up to a limit that keeps the list
// quick to build.
class SearchCaps(val artists: Int, val albums: Int, val songs: Int, val playlists: Int)

fun searchCaps(filter: SearchFilter): SearchCaps = when (filter) {
    SearchFilter.All -> SearchCaps(artists = 10, albums = 20, songs = 50, playlists = 10)
    SearchFilter.Songs -> SearchCaps(0, 0, songs = 1_000, playlists = 0)
    SearchFilter.Albums -> SearchCaps(0, albums = 500, 0, 0)
    SearchFilter.Artists -> SearchCaps(artists = 500, 0, 0, 0)
    SearchFilter.Playlists -> SearchCaps(0, 0, 0, playlists = 500)
}

class SearchResults(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val songs: List<TrackEntity>,
    val playlists: List<PlaylistSummary> = emptyList(),
    // Whether there are more of a kind than shown, for its "See all".
    val moreArtists: Boolean = false,
    val moreAlbums: Boolean = false,
    val moreSongs: Boolean = false,
    val morePlaylists: Boolean = false,
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && playlists.isEmpty()
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
    private val recents: RecentSearches,
    playlists: PlaylistStore,
) : ViewModel() {
    var text by mutableStateOf("")
    var filter by mutableStateOf(SearchFilter.All)

    // Null until at least two characters are typed. One more than shown is
    // asked for, to know whether there are more.
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults?> = combine(
        snapshotFlow { text }.debounce(150).map { searchKey(it) }.distinctUntilChanged(),
        snapshotFlow { filter },
        playlists.playlists,
    ) { q, filter, lists -> Triple(q, filter, lists) }
        .mapLatest { (q, filter, lists) ->
            if (q.length < 2) {
                null
            } else {
                val caps = searchCaps(filter)
                val artists = if (caps.artists > 0) dao.searchArtists(q, caps.artists + 1) else emptyList()
                val albums = if (caps.albums > 0) dao.searchAlbums(q, caps.albums + 1) else emptyList()
                val songs = if (caps.songs > 0) dao.searchTracks(q, caps.songs + 1) else emptyList()
                val named = if (caps.playlists > 0) lists.filter { searchKey(it.name).contains(q) } else emptyList()
                SearchResults(
                    artists.take(caps.artists),
                    albums.take(caps.albums),
                    songs.take(caps.songs),
                    named.take(caps.playlists),
                    moreArtists = artists.size > caps.artists,
                    moreAlbums = albums.size > caps.albums,
                    moreSongs = songs.size > caps.songs,
                    morePlaylists = named.size > caps.playlists,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whether a server is signed in, so there is more to discover.
    val signedIn: StateFlow<Boolean> =
        discovery.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // The last searches that led somewhere, newest first.
    val recent: StateFlow<List<String>> =
        recents.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    // Keeps what was typed among the recent searches, once it led to a
    // result being opened or played.
    fun keepSearch() {
        val query = text
        viewModelScope.launch { recents.add(query) }
    }

    fun searchAgain(query: String) {
        text = query
    }

    fun forget(query: String) {
        viewModelScope.launch { recents.remove(query) }
    }

    fun forgetAll() {
        viewModelScope.launch { recents.clear() }
    }

    // Plays the found songs from the one tapped.
    fun playSong(track: TrackEntity) {
        val songs = results.value?.songs ?: return
        keepSearch()
        playback.playTracks(songs.map { it.id }, songs.indexOf(track).coerceAtLeast(0))
    }

    // Plays the songs found online from the one tapped.
    fun playFound(track: TrackEntity) {
        val songs = (discover.value as? DiscoverState.Done)?.found?.songs ?: return
        keepSearch()
        playback.playTracks(songs.map { it.id }, songs.indexOf(track).coerceAtLeast(0))
    }
}
