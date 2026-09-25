package app.winters.octo.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: CatalogDao,
    val library: DeviceLibrary,
) : ViewModel() {
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

    init {
        viewModelScope.launch { dao.trackCount(DEVICE).distinctUntilChanged().collect { reroll() } }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            library.rescan()
            reroll()
            refreshing = false
        }
    }

    private suspend fun reroll() {
        surprise = dao.randomAlbums(20)
        artists = dao.randomArtists(16)
    }
}
