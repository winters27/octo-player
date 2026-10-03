package app.winters.octo.ui.favourites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.sort.sortFavourites
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.LocalSongSelection
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.PlayRow
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.Segmented
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortButton
import app.winters.octo.ui.common.TitleWithSort
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.playlist.KeepLikedDownloaded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    dao: FavouritesDao,
    private val sorting: SortSettings,
    private val library: SortedLibrary,
    private val playback: PlaybackConnection,
    private val likes: LikeStore,
    private val feedback: Feedback,
) : ViewModel() {
    // Liked songs, in the Liked songs order. Null until first read.
    val songs: StateFlow<Sorted<TrackEntity>?> =
        library.songs(SortList.Liked, SongScope.Liked).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Favourite albums and artists in the chosen order, one order for both.
    // Null until first read.
    val albums: StateFlow<Sorted<AlbumEntity>?> =
        combine(dao.likedAlbums(), sorting.order(SortList.Favourites)) { rows, order ->
            val sorted = sortFavourites(rows, order, { it.likedAt }, { it.album.sortKey }, { it.album.id })
            Sorted(sorted.map { it.album }, order, null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artists: StateFlow<Sorted<ArtistEntity>?> =
        combine(dao.likedArtists(), sorting.order(SortList.Favourites)) { rows, order ->
            val sorted = sortFavourites(rows, order, { it.likedAt }, { it.artist.sortKey }, { it.artist.id })
            Sorted(sorted.map { it.artist }, order, null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun songIds() = songs.value?.items.orEmpty().map { it.id }

    fun playSongs(index: Int) = playback.playTracks(songIds(), index, source = "Favorites")

    fun shuffleSongs() = playback.playTracks(songIds(), shuffle = true, source = "Favorites")

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Favourites, order) }
    }

    // Songs keep their own order, the one saved for Liked songs.
    fun setSongOrder(order: SortOrder) {
        viewModelScope.launch { library.setOrder(SortList.Liked, order) }
    }

    // Unlikes a song, with an Undo that likes it again in the same place.
    fun unlike(trackId: String) = likes.unlike(trackId) { row ->
        feedback.undoable("Removed from Liked songs") { likes.restore(row) }
    }
}

private val Segments = FavouriteSegment.entries.map { it.label }

private val RowShape = RoundedCornerShape(12.dp)

// Where a new song order scrolls back to: the first song, after the play
// line and the keep-downloaded line.
private const val FIRST_SONG = 2

// How to heart a song, shown while there are none.
private const val SONGS_EMPTY =
    "No liked songs yet. Tap the heart in the player to add the song that is playing, " +
        "or long press any song and choose Add to Liked songs."

// Liked songs, favourite albums and favourite artists, each under its own
// segment. This is the one home for hearted songs. Songs keep the Liked
// songs order; albums and artists share one sort: when they became
// favourites, or by name. The page opens on the part asked for (`openOn`,
// see openingSegment), otherwise on liked songs when there are any.
@Composable
fun FavouritesScreen(
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    openOn: FavouriteSegment? = null,
    vm: FavouritesViewModel = hiltViewModel(),
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(openOn) }
    // The segment to open on is picked once all three are read, then kept,
    // so the page never jumps while it is open.
    val ready = songs != null && albums != null && artists != null
    LaunchedEffect(ready) {
        if (ready && chosen == null) {
            chosen = firstSegment(songs?.items.orEmpty().size, albums?.items.orEmpty().size, artists?.items.orEmpty().size)
        }
    }
    val segment = chosen

    Refreshable {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            TitleWithSort("Favorites") {
                when (segment) {
                    null -> Unit
                    FavouriteSegment.Songs -> songs?.let { SortButton(SortList.Liked, it.order, vm::setSongOrder) }
                    else -> (albums ?: artists)?.let { SortButton(SortList.Favourites, it.order, vm::setOrder) }
                }
            }
            if (segment == null) {
                Loading()
            } else {
                Segmented(
                    Segments,
                    segment.ordinal,
                    { chosen = FavouriteSegment.entries[it] },
                    Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
                )
                when (segment) {
                    FavouriteSegment.Songs -> FavouriteSongs(songs, vm::playSongs, vm::shuffleSongs, vm::unlike)
                    FavouriteSegment.Albums -> FavouriteAlbums(albums, onOpen)
                    FavouriteSegment.Artists -> FavouriteArtists(artists, onOpen)
                }
            }
        }
        BackButton(onBack)
    }
}

// Liked songs: play and shuffle, the keep-downloaded switch, then the songs
// in their order. Swipe a song left to unlike it, with an Undo; long press
// to pick several.
@Composable
private fun FavouriteSongs(
    list: Sorted<TrackEntity>?,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onUnlike: (String) -> Unit,
) {
    if (list == null) {
        Loading()
        return
    }
    val tracks = list.items
    // The rows as drawn: a swiped one goes at once, the list catches up.
    var rows by remember(tracks) { mutableStateOf(tracks) }
    val state = rememberLazyListState()
    TopOnNewOrder(list.order, state, top = FIRST_SONG)
    val pickable = remember(rows) { rows.map { Pickable(it.id, it) } }
    SelectableSongs(pickable) {
        val selecting = LocalSongSelection.current?.active == true
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
            item(key = "play") {
                if (tracks.isNotEmpty()) {
                    PlayRow(
                        details = songs(tracks.size),
                        onPlay = { onPlay(0) },
                        onShuffle = onShuffle,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }
            }
            item(key = "keep") { KeepLikedDownloaded(tracks) }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyText(SONGS_EMPTY) }
            }
            items(rows, key = { it.id }) { track ->
                val swipe = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = swipe,
                    modifier = Modifier.animateItem(),
                    enableDismissFromStartToEnd = false,
                    gesturesEnabled = !selecting,
                    onDismiss = {
                        rows = rows - track
                        onUnlike(track.id)
                    },
                    backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                ) {
                    // Solid under the row while it moves, so it hides what it
                    // passes over; clear at rest, so the page's glow shows.
                    val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                    Box(Modifier.background(if (swiping) OctoColors.Background else Color.Transparent, RowShape)) {
                        // A swipe left unlikes here, so it does not also play next.
                        SongRow(track, swipeToPlayNext = false) { onPlay(tracks.indexOf(track)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouriteAlbums(albums: Sorted<AlbumEntity>?, onOpen: (NavKey) -> Unit) {
    Shown(albums, "Heart an album from its page or its menu to keep it here.") { sorted ->
        val grid = rememberLazyGridState()
        TopOnNewOrder(sorted.order, grid)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(sorted.items, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.animateItem(), width = null)
            }
        }
    }
}

@Composable
private fun FavouriteArtists(artists: Sorted<ArtistEntity>?, onOpen: (NavKey) -> Unit) {
    Shown(artists, "Heart an artist from their page or their menu to keep them here.") { sorted ->
        val state = rememberLazyListState()
        TopOnNewOrder(sorted.order, state)
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
            items(sorted.items, key = { it.id }) { artist ->
                Box(Modifier.animateItem()) { ArtistRow(artist) { onOpen(ArtistRoute(artist.id)) } }
            }
        }
    }
}

// A spinner until the first read, a note when there are none, the list otherwise.
@Composable
private fun <T> Shown(sorted: Sorted<T>?, empty: String, content: @Composable (Sorted<T>) -> Unit) {
    when {
        sorted == null -> Loading()
        sorted.items.isEmpty() -> EmptyText(empty)
        else -> content(sorted)
    }
}

// The quiet note in place of an empty list, saying how to add to it.
@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
    }
}
