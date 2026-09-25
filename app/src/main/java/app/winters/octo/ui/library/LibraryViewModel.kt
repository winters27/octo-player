package app.winters.octo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    dao: CatalogDao,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Null until the first read, so a loading moment is not shown as empty.
    val albums: StateFlow<List<AlbumEntity>?> =
        dao.albums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val artists: StateFlow<List<ArtistEntity>?> =
        dao.artists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val songs: StateFlow<List<TrackEntity>?> =
        dao.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Plays the whole song list from the one tapped.
    fun playSong(track: TrackEntity) {
        val all = songs.value ?: return
        playback.playTracks(all.map { it.id }, all.indexOf(track).coerceAtLeast(0))
    }
}
