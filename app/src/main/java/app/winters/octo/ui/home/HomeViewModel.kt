package app.winters.octo.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.byLatestPlay
import app.winters.octo.catalog.byPlayCount
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.Station
import app.winters.octo.listening.PlayHistory
import app.winters.octo.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: CatalogDao,
    history: PlayHistory,
    private val playback: PlaybackConnection,
    private val discovery: Discovery,
    val library: DeviceLibrary,
) : ViewModel() {
    // Both follow the play history, here and on the server, so a song that
    // just counted shows up.
    val recentlyPlayed: StateFlow<List<AlbumEntity>> =
        history.albums.map { byLatestPlay(it, 20) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mostPlayed: StateFlow<List<TrackEntity>> =
        history.tracks.map { byPlayCount(it, 20) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<AlbumEntity>?> =
        dao.recentAlbums(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val songCount: StateFlow<Int?> =
        dao.trackCount(DEVICE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Picked once, then again on a pull or when the library itself changes.
    var surprise by mutableStateOf<List<AlbumEntity>>(emptyList())
        private set
    var artists by mutableStateOf<List<ArtistEntity>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set

    // The signed-in server's stations, kept through a failed load.
    var stations by mutableStateOf<List<Station>>(emptyList())
        private set

    // The station whose songs are on the way, if any.
    var startingStation by mutableStateOf<String?>(null)
        private set

    private var signedIn = false
    private var stationsJob: Job? = null
    private var stationsLoadedAt: Long? = null
    private var stationsFailed = false

    init {
        viewModelScope.launch { dao.trackCount(DEVICE).distinctUntilChanged().collect { reroll() } }
        // A new sign-in starts over; signing out drops the old server's list.
        viewModelScope.launch {
            discovery.available.distinctUntilChanged().collect { available ->
                signedIn = available
                stationsJob?.cancel()
                stationsLoadedAt = null
                stationsFailed = false
                if (available) loadStations() else stations = emptyList()
            }
        }
    }

    // Home is on screen again: ask for the stations if the last try failed
    // or the list is getting old.
    fun onShown() {
        val loading = stationsJob?.isActive == true
        if (signedIn && stationsDue(SystemClock.elapsedRealtime(), stationsLoadedAt, stationsFailed, loading)) {
            loadStations()
        }
    }

    // Plays what a station has lined up today. Taps while one is on the
    // way are ignored.
    fun playStation(station: Station) {
        if (startingStation != null) return
        startingStation = station.id
        viewModelScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { discovery.stationSongs(station.id) }
                playback.playTracks(songs.map { it.id }, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "station failed to start: ${e.javaClass.simpleName}")
            } finally {
                startingStation = null
            }
        }
    }

    // Plays a shelf of songs as shown, starting at the one tapped.
    fun play(tracks: List<TrackEntity>, index: Int) = playback.playTracks(tracks.map { it.id }, index)

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            library.rescan()
            reroll()
            refreshing = false
        }
        if (signedIn) loadStations()
    }

    // Runs in the background so no other shelf waits on the server.
    private fun loadStations() {
        if (stationsJob?.isActive == true) return
        stationsJob = viewModelScope.launch {
            try {
                stations = withContext(Dispatchers.IO) { discovery.stations() }
                stationsLoadedAt = SystemClock.elapsedRealtime()
                stationsFailed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stationsFailed = true
                Log.w("Octo", "stations failed to load: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun reroll() {
        surprise = dao.randomAlbums(20)
        artists = dao.randomArtists(16)
    }
}
