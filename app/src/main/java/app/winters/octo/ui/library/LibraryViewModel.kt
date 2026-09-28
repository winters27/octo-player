package app.winters.octo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.TrackPlace
import app.winters.octo.catalog.songsInGroupOrder
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val dao: CatalogDao,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // The newest albums, for the front page.
    val recent: StateFlow<List<AlbumEntity>> =
        dao.recentAlbums(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Each list in its chosen order. Null until the first read, so a loading
    // moment is not shown as empty.
    val albums: StateFlow<Sorted<AlbumEntity>?> =
        sorted.albums(SortList.Albums).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val artists: StateFlow<Sorted<ArtistEntity>?> =
        sorted.artists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val songs: StateFlow<Sorted<TrackEntity>?> =
        sorted.songs(SortList.Songs, SongScope.All).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setOrder(list: SortList, order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(list, order) }
    }

    // Plays every song, in the order shown, or shuffled.
    fun playSongs(shuffle: Boolean) {
        val all = songs.value?.items ?: return
        playback.playTracks(all.map { it.id }, 0, shuffle, "Songs")
    }

    // Plays every album in the order shown, each from its first song, or
    // every song shuffled.
    fun playAlbums(shuffle: Boolean) {
        val order = albums.value?.items?.map { it.id } ?: return
        playGroups(order, shuffle) { it.albumId }
    }

    // Plays every artist in the order shown, album by album, or every song
    // shuffled.
    fun playArtists(shuffle: Boolean) {
        val order = artists.value?.items?.map { it.id } ?: return
        playGroups(order, shuffle) { it.artistId }
    }

    private fun playGroups(order: List<String>, shuffle: Boolean, groupOf: (TrackPlace) -> String) {
        viewModelScope.launch {
            val ids = if (shuffle) dao.allTrackIds() else songsInGroupOrder(dao.trackPlaces(), groupOf, order)
            playback.playTracks(ids, 0, shuffle)
        }
    }

    // Any one album, for "Random album".
    fun randomAlbum(): AlbumEntity? = albums.value?.items?.randomOrNull()

    // Plays the whole song list, in the order shown, from the one tapped.
    fun playSong(track: TrackEntity) {
        val all = songs.value?.items ?: return
        playback.playTracks(all.map { it.id }, all.indexOf(track).coerceAtLeast(0), source = "Songs")
    }
}
