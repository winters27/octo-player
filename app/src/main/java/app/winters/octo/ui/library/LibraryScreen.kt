package app.winters.octo.ui.library

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.livelists.liveListQueryFrom
import app.winters.octo.sort.SortList
import app.winters.octo.sort.Sorted
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.EmptyLibraryNote
import app.winters.octo.ui.common.GlassIconButton
import app.winters.octo.ui.common.LetterRail
import app.winters.octo.ui.common.LocalReduceMotion
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.PlayPills
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.RAIL_MIN_ITEMS
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortButton
import app.winters.octo.ui.common.TitleWithSort
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.letterRuns
import app.winters.octo.ui.common.letteredRows
import app.winters.octo.ui.common.railStops
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.sortedRows
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.AlbumsRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.ArtistsRoute
import app.winters.octo.ui.nav.DownloadsRoute
import app.winters.octo.ui.nav.FavouritesRoute
import app.winters.octo.ui.nav.FoldersRoute
import app.winters.octo.ui.nav.GenresRoute
import app.winters.octo.ui.nav.HistoryRoute
import app.winters.octo.ui.nav.LibraryHealthRoute
import app.winters.octo.ui.nav.LibraryRoute
import app.winters.octo.ui.nav.LiveListEditRoute
import app.winters.octo.ui.nav.PlaylistsRoute
import app.winters.octo.ui.nav.SongsRoute
import kotlin.math.roundToInt

// One way into the library, like Albums or Songs.
private class Section(@DrawableRes val icon: Int, val label: String, val route: NavKey)

private val sections = listOf(
    Section(OctoIcons.Playlists, "Playlists", PlaylistsRoute),
    Section(OctoIcons.Like, "Favorites", FavouritesRoute()),
    Section(OctoIcons.Artist, "Artists", ArtistsRoute),
    Section(OctoIcons.Album, "Albums", AlbumsRoute),
    Section(OctoIcons.Songs, "Songs", SongsRoute),
    Section(OctoIcons.Genres, "Genres", GenresRoute),
    Section(OctoIcons.Folder, "Folders", FoldersRoute),
    Section(OctoIcons.Downloaded, "Downloads", DownloadsRoute),
    // Stands in until the history symbol joins the icon set.
    Section(OctoIcons.History, "History", HistoryRoute()),
    Section(OctoIcons.Check, "Library health", LibraryHealthRoute),
)

// The library's front page: a menu of ways in, then the newest albums,
// two covers to a row.
@Composable
fun LibraryScreen(
    onOpen: (NavKey) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
    server: app.winters.octo.ui.downloads.ServerDownloadsViewModel = hiltViewModel(),
) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val serverDownloads by server.downloads.supported.collectAsStateWithLifecycle()
    val serverRows by server.downloads.rows.collectAsStateWithLifecycle()
    Refreshable {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding()) {
            item { ScreenTitle("Library") }
            sections.forEachIndexed { index, section ->
                item(key = section.label) {
                    Column {
                        if (index > 0) Separator()
                        SectionRow(section) { onOpen(section.route) }
                    }
                }
            }
            // What the server is downloading, on a server that keeps a log of it.
            if (serverDownloads) {
                item(key = "server-downloads") {
                    Column {
                        Separator()
                        val running = app.winters.octo.ui.downloads.runningCount(serverRows)
                        SectionRow(
                            Section(OctoIcons.Cloud, app.winters.octo.ui.downloads.SERVER_DOWNLOADS, LibraryRoute),
                            detail = if (running > 0) "$running on the way" else null,
                        ) { server.downloads.open() }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionTitle("Recently added", Modifier.padding(top = 24.dp)) }
                items(recent.chunked(2), key = { pair -> pair.first().id }) { pair ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        pair.forEach { album ->
                            AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.weight(1f), width = null)
                        }
                        // An odd last album keeps its half width.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(section: Section, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(section.icon), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(24.dp))
        Text(section.label, style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

// A hairline between menu rows, starting under the labels.
@Composable
private fun Separator() {
    Box(
        Modifier
            .padding(start = 60.dp, end = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(OctoColors.TextPrimary.copy(alpha = 0.08f)),
    )
}

// Every album, as a grid of covers, in the chosen order.
@Composable
fun AlbumsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    LibraryPage("Albums", onBack, action = {
        albums?.let { SortButton(SortList.Albums, it.order, onChange = { order -> vm.setOrder(SortList.Albums, order) }) }
    }, buttons = {
        PlayButtons(albums, "album", vm::playAlbums) {
            QuietButton("Random album") { vm.randomAlbum()?.let { onOpen(AlbumRoute(it.id)) } }
        }
    }) {
        Loaded(albums) { sorted ->
            val grid = rememberLazyGridState()
            TopOnNewOrder(sorted.order, grid)
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = grid,
                    contentPadding = ListPadding,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(sorted.items, key = { it.id }) { album ->
                        AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.animateItem(), width = null)
                    }
                }
                val names = sorted.headings
                if (names != null && sorted.items.size >= RAIL_MIN_ITEMS) {
                    val stops = remember(names) { railStops(letterRuns(names), headed = false) }
                    LetterRail(stops, onJump = { grid.requestScrollToItem(it) }, modifier = Modifier.fillMaxSize().padding(RailPadding))
                }
            }
        }
    }
}

// Every artist, in the chosen order: under letters when by name.
@Composable
fun ArtistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    LibraryPage("Artists", onBack, action = {
        artists?.let { SortButton(SortList.Artists, it.order, onChange = { order -> vm.setOrder(SortList.Artists, order) }) }
    }, buttons = { PlayButtons(artists, "artist", vm::playArtists) }) {
        Loaded(artists) { sorted ->
            SortedList(sorted, key = { it.id }) { artist ->
                ArtistRow(artist) { onOpen(ArtistRoute(artist.id)) }
            }
        }
    }
}

// Every song, in the chosen order, which the search above narrows. A
// tap plays the list as shown from that song.
@Composable
fun SongsScreen(onBack: () -> Unit, onOpen: (NavKey) -> Unit = {}, vm: LibraryViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val shown by vm.shownSongs.collectAsStateWithLifecycle()
    val query by vm.songFilter.collectAsStateWithLifecycle()
    val choices by vm.songChoices.collectAsStateWithLifecycle()
    // Search opens from the title's line and stays open while it narrows the
    // list. Closing it (its button, or Back) clears the words and the chips.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || query.filters
    // Set by a tap on Search, so the field takes the keyboard then, and not
    // when the page comes back with a search already in it.
    var focusOnOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun closeSearch() {
        focusManager.clearFocus()
        searchOpen = false
        focusOnOpen = false
        vm.filterSongs(query.cleared())
    }
    BackHandler(enabled = searching) { closeSearch() }
    LibraryPage("Songs", onBack, action = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (songs?.items?.isNotEmpty() == true) {
                GlassIconButton(
                    if (searching) OctoIcons.Close else OctoIcons.Search,
                    if (searching) "Close the search" else "Search these songs",
                    onClick = {
                        if (searching) {
                            closeSearch()
                        } else {
                            searchOpen = true
                            focusOnOpen = true
                        }
                    },
                    lit = searching,
                )
            }
            songs?.let { SortButton(SortList.Songs, it.order, onChange = { order -> vm.setOrder(SortList.Songs, order) }) }
        }
    }, buttons = {
        if (songs != null) {
            SearchPanel(searching) {
                SongFilterRow(
                    query,
                    choices,
                    vm::filterSongs,
                    onSaveAsLive = { onOpen(LiveListEditRoute(start = liveListQueryFrom(emptyList(), query, songs?.order))) },
                    focusOnOpen = focusOnOpen,
                    onFocused = { focusOnOpen = false },
                )
            }
        }
        val all = songs?.items?.size ?: 0
        PlayButtons(shown, "song", vm::playSongs, details = if (query.filters) filteredCount(shown?.items?.size ?: 0, all) else null)
    }) {
        Loaded(songs) { _ ->
            val list = shown
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
                }
                list.items.isEmpty() -> NoMatchesNote { vm.filterSongs(query.cleared()) }
                else -> {
                    val pickable = remember(list.items) { list.items.map { Pickable(it.id, it) } }
                    SelectableSongs(pickable) {
                        SortedList(list, key = { it.id }) { track -> SongRow(track) { vm.playSong(track) } }
                    }
                }
            }
        }
    }
}

// "120 of 2,835 songs", for a filtered list.
internal fun filteredCount(shown: Int, all: Int): String = "%,d of %,d %s".format(shown, all, if (all == 1) "song" else "songs")

// Grids keep side margins; lists of rows run edge to edge. Both leave room
// at the bottom for the floating bar.
private val ListPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 140.dp)
private val RowsPadding = PaddingValues(top = 4.dp, bottom = 140.dp)

// The letter rail runs beside the rows, clear of the floating bar.
private val RailPadding = PaddingValues(top = 8.dp, bottom = 132.dp)

// A long list of rows in its order: under letter headings with the letter
// rail when the order is by name, plain rows otherwise.
@Composable
private fun <T> SortedList(sorted: Sorted<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    TopOnNewOrder(sorted.order, state)
    val names = sorted.headings
    val runs = remember(names) { names?.let(::letterRuns) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = RowsPadding) {
            if (runs != null) {
                letteredRows(sorted.items, runs, key) { row(it) }
            } else {
                sortedRows(sorted.items, key) { row(it) }
            }
        }
        if (runs != null && sorted.items.size >= RAIL_MIN_ITEMS) {
            val stops = remember(runs) { railStops(runs, headed = true) }
            LetterRail(stops, onJump = { state.requestScrollToItem(it) }, modifier = Modifier.fillMaxSize().padding(RailPadding))
        }
    }
}

// A page inside the library: the back button, a title with the list's sort
// button beside it, the buttons that play the list, then the list.
@Composable
private fun LibraryPage(
    title: String,
    onBack: () -> Unit,
    action: @Composable () -> Unit,
    buttons: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Refreshable {
        CollapsingHeader(
            Modifier.fillMaxSize().statusBarsPadding(),
            header = {
                Column {
                    Spacer(Modifier.height(DetailTopGap))
                    TitleWithSort(title, sort = action)
                    buttons()
                }
            },
            content = content,
        )
        BackButton(onBack)
    }
}

// A header over a list that slides up out of the way as the list scrolls
// down, giving the list the whole screen, and comes back as soon as the
// list is pulled down from its top. The list itself is left as it is: the
// header gives up its height first, then the list scrolls.
@Composable
private fun CollapsingHeader(modifier: Modifier, header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val state = remember { HeaderSlide() }
    Column(modifier.nestedScroll(state)) {
        Box(
            Modifier.clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                state.height = placeable.height
                val moved = state.offset.roundToInt().coerceIn(-placeable.height, 0)
                layout(placeable.width, placeable.height + moved) { placeable.place(0, moved) }
            },
        ) { header() }
        Box(Modifier.weight(1f)) { content() }
    }
}

// How far the header has slid up, in pixels (0 to minus its height).
private class HeaderSlide : NestedScrollConnection {
    var height = 0
    var offset by mutableFloatStateOf(0f)

    // Scrolling down the list: the header goes first.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) slide(available.y) else Offset.Zero

    // Pulled down with the list at its top: the header comes back.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) slide(available.y) else Offset.Zero

    private fun slide(by: Float): Offset {
        val next = (offset + by).coerceIn(-height.toFloat(), 0f)
        val used = next - offset
        offset = next
        return Offset(0f, used)
    }
}

// The search under the title. It grows open from the top on a soft spring,
// pushing the list down rather than jumping it, while its field and chips
// drift down a little and fade in a beat behind; closing folds it back up,
// quicker. With the phone's animations off it only fades.
@Composable
private fun SearchPanel(open: Boolean, content: @Composable () -> Unit) {
    val still = LocalReduceMotion.current
    AnimatedVisibility(
        visible = open,
        enter = if (still) {
            fadeIn(tween(120))
        } else {
            expandVertically(spring(0.9f, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
                slideInVertically(spring(0.85f, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)) { -it / 5 } +
                fadeIn(tween(220, delayMillis = 60))
        },
        exit = if (still) {
            fadeOut(tween(100))
        } else {
            shrinkVertically(spring(1f, Spring.StiffnessMedium, IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top) +
                fadeOut(tween(120))
        },
    ) { content() }
}

// Play and Shuffle for the whole list, once it has something in it: how
// many `noun`s it holds on the left (or `details`), then room for one
// quieter action, then the small glass Play and Shuffle.
@Composable
private fun PlayButtons(
    list: Sorted<*>?,
    noun: String,
    onPlay: (shuffle: Boolean) -> Unit,
    details: String? = null,
    extra: @Composable () -> Unit = {},
) {
    if (list == null || list.items.isEmpty()) return
    val count = list.items.size
    Row(
        // The right edge lines up with the sort capsule's above: the glass
        // sits 7dp inside its 48dp touch target.
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 13.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            details ?: "%,d %s".format(count, if (count == 1) noun else "${noun}s"),
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        extra()
        PlayPills(onPlay = { onPlay(false) }, onShuffle = { onPlay(true) })
    }
}

// Shows a spinner until the first read, a note if there is nothing, and
// the list otherwise.
@Composable
private fun <T> Loaded(sorted: Sorted<T>?, content: @Composable (Sorted<T>) -> Unit) {
    when {
        sorted == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
        }
        // Offers phone access or a server sign-in when either is missing.
        sorted.items.isEmpty() -> EmptyLibraryNote()
        else -> content(sorted)
    }
}
