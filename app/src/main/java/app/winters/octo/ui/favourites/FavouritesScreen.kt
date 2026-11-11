package app.winters.octo.ui.favourites

import app.winters.octo.ui.common.sortItem
import app.winters.octo.ui.common.CopyNote
import app.winters.octo.ui.common.playShuffleItems
import app.winters.octo.ui.common.LineSegments
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.ui.common.picked
import app.winters.octo.ui.paging.HiddenRows
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.LocalSongSelection
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.playlist.keepLikedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavouritesViewModel @Inject constructor(
    dao: FavouritesDao,
    private val sorting: SortSettings,
    private val library: SortedLibrary,
    private val playback: PlaybackConnection,
    private val likes: LikeStore,
    private val feedback: Feedback,
    loaders: ListLoaders,
    reads: LibraryReads,
    db: CatalogDatabase,
    user: UserDao,
) : ViewModel() {
    // The Liked songs order; null until read.
    val songOrder: StateFlow<SortOrder?> = library.settings.order(SortList.Liked).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var handle: ListHandle<SortOrder, TrackEntity>? = null

    // Liked songs in pages, in the Liked songs order. Null until the order
    // is read.
    val songs: StateFlow<ListHandle<SortOrder, TrackEntity>?> = songOrder.filterNotNull().map { order ->
        handle?.also { it.ask(order) } ?: ListHandle(
            viewModelScope,
            order,
            { o -> loaders.songs(SongScope.Liked, o) },
            reads,
            { _, loader -> db.tableChanges(loader.tables) },
        ).also { handle = it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // How many liked songs the library has, by a count.
    val songCount: StateFlow<Int?> = user.likedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Songs swiped away, shown gone at once, each until a generation of the
    // list that began after its unlike (by number) shows.
    private val hiddenRows = HiddenRows()
    val hidden: StateFlow<Set<String>> = hiddenRows.among(songs.flatMapLatest { it?.shown ?: flowOf(null) })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    private fun spec() = songOrder.value?.let { ListSpec.Songs(SongScope.Liked, it) }

    fun playSongs(item: ListItem<TrackEntity>?) {
        val spec = spec() ?: return
        playback.playList(spec, item?.let { ListStart.Row(it.row.id, it.key) }, source = "Favorites")
    }

    fun shuffleSongs() {
        spec()?.let { playback.playList(it, null, shuffle = true, source = "Favorites") }
    }

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Favourites, order) }
    }

    // Songs keep their own order, the one saved for Liked songs.
    fun setSongOrder(order: SortOrder) {
        viewModelScope.launch { library.setOrder(SortList.Liked, order) }
    }

    // Unlikes a song, with an Undo that likes it again in the same place.
    // It shows gone at once, until a generation begun after the unlike shows.
    fun unlike(trackId: String) {
        hiddenRows.hide(trackId)
        likes.unlike(trackId) { row ->
            hiddenRows.committed(trackId, handle?.generationNow ?: 0)
            feedback.undoable("Removed from Liked songs") {
                hiddenRows.show(trackId)
                likes.restore(row)
            }
        }
    }
}

private val RowShape = RoundedCornerShape(12.dp)

// Where a new song order scrolls back to: the first song.
private const val FIRST_SONG = 0

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
    val songOrder by vm.songOrder.collectAsStateWithLifecycle()
    val songCount by vm.songCount.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(openOn) }
    // The segment to open on is picked once all three are read, then kept,
    // so the page never jumps while it is open.
    val ready = songCount != null && albums != null && artists != null
    LaunchedEffect(ready) {
        if (ready && chosen == null) {
            chosen = firstSegment(songCount ?: 0, albums?.items.orEmpty().size, artists?.items.orEmpty().size)
        }
    }
    val segment = chosen

    Refreshable {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            val keep = keepLikedItem()
            PageHeader(
                "Favorites",
                onBack,
                count = segment?.let { favouriteCount(it, songCount, albums?.items?.size, artists?.items?.size) },
                // The order, the three parts as segments, and on Liked songs
                // Play, Shuffle and More with Keep downloaded. On a narrow
                // phone the sort moves into More first.
                lead = listOfNotNull(
                    when (segment) {
                        null -> null
                        FavouriteSegment.Songs -> songOrder?.let { sortItem(SortList.Liked, it, vm::setSongOrder) }
                        else -> (albums ?: artists)?.let { sortItem(SortList.Favourites, it.order, vm::setOrder) }
                    },
                ),
                segments = segment?.let { part ->
                    LineSegments(FavouriteSegment.entries.map { it.label }, part.ordinal, "Show") { chosen = FavouriteSegment.entries[it] }
                },
                tail = if (segment == FavouriteSegment.Songs && (songCount ?: 0) > 0) playShuffleItems({ vm.playSongs(null) }, vm::shuffleSongs) else emptyList(),
                more = if (segment == FavouriteSegment.Songs) listOfNotNull(keep) else emptyList(),
            )
            when (segment) {
                null -> Loading()
                FavouriteSegment.Songs -> FavouriteSongs(songs, songOrder, songCount, vm)
                FavouriteSegment.Albums -> FavouriteAlbums(albums, onOpen)
                FavouriteSegment.Artists -> FavouriteArtists(artists, onOpen)
            }
        }
    }
}

// Liked songs in their order, in pages, under the header's Play and
// Shuffle. Swipe a song left to unlike it, with an Undo: it goes at once
// and stays gone until the list reads again after the unlike; long press
// to pick several.
@Composable
private fun FavouriteSongs(handle: ListHandle<SortOrder, TrackEntity>?, order: SortOrder?, count: Int?, vm: FavouritesViewModel) {
    if (handle == null || order == null || count == null) {
        Loading()
        return
    }
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    TopOnNewOrder(order, state, top = FIRST_SONG)
    val items = rememberListRows(handle, state)
    PagedPicks(handle, items, pickOf = { key, item -> if (item.row.id == key) Pickable(key, item.row, item.key).picked() else null }, base = ListSpec.Songs(SongScope.Liked, order)) {
        val selecting = LocalSongSelection.current?.active == true
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
            if (count == 0) {
                item(key = "empty") { CopyNote(likes = true) { EmptyText(SONGS_EMPTY) } }
            }
            listRows(items, null, heading = {}) { item ->
                when {
                    item == null -> RowPlaceholder()
                    item.row.id in hidden -> Unit
                    else -> {
                        val track = item.row
                        val swipe = rememberSwipeToDismissBoxState()
                        SwipeToDismissBox(
                            state = swipe,
                            enableDismissFromStartToEnd = false,
                            gesturesEnabled = !selecting,
                            onDismiss = { vm.unlike(track.id) },
                            backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                        ) {
                            // Solid under the row while it moves, so it hides what it
                            // passes over; clear at rest, so the page's glow shows.
                            val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                            Box(Modifier.background(if (swiping) OctoColors.Background else Color.Transparent, RowShape)) {
                                // A swipe left unlikes here, so it does not also play next.
                                SongRow(track, queueSwipe = false) { vm.playSongs(item) }
                            }
                        }
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
        sorted.items.isEmpty() -> CopyNote(likes = true) { EmptyText(empty) }
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
