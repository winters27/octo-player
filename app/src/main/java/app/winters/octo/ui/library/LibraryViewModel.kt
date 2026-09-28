package app.winters.octo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.TrackPlace
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.songsInGroupOrder
import app.winters.octo.listening.PlayHistory
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.decadesIn
import app.winters.octo.query.genresIn
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.sort.TrackFields
import app.winters.octo.sort.filteredSongs
import app.winters.octo.sort.songListening
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val dao: CatalogDao,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
    user: UserDao,
    history: PlayHistory,
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

    // The Songs list's filters, for this visit only: the words typed and
    // the chips on.
    val songFilter = MutableStateFlow(LibraryQuery())

    // The songs as the filters leave them, in the chosen order. Null until
    // the first read, like the whole list.
    val shownSongs: StateFlow<Sorted<TrackEntity>?> =
        combine(songs.filterNotNull(), songFilter, user.likedIds(), history.tracks) { all, query, liked, played ->
            filteredSongs(all, query, TrackFields(liked.toHashSet(), songListening(played)), System.currentTimeMillis())
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The genres and decades the Songs list's chips offer.
    val songChoices: StateFlow<SongChoices> =
        songs.filterNotNull().map { all -> SongChoices(genresIn(all.items, TrackFields()), decadesIn(all.items, TrackFields())) }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongChoices())

    fun filterSongs(query: LibraryQuery) {
        songFilter.value = query
    }

    fun setOrder(list: SortList, order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(list, order) }
    }

    // Plays every song shown, in the order shown, or shuffled.
    fun playSongs(shuffle: Boolean) {
        val all = shownSongs.value?.items ?: return
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

    // Plays the song list as shown, from the one tapped.
    fun playSong(track: TrackEntity) {
        val all = shownSongs.value?.items ?: return
        playback.playTracks(all.map { it.id }, all.indexOf(track).coerceAtLeast(0), source = "Songs")
    }
}

// What the Songs list's chips can pick from: its genres, A to Z, and the
// decades its songs come from, as the year each starts.
data class SongChoices(val genres: List<String> = emptyList(), val decades: List<Int> = emptyList())
