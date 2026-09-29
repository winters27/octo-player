package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.filteredCount
import app.winters.octo.desktop.library.rememberFiltered
import app.winters.octo.desktop.library.rememberSorted
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.library.sortSongs
import app.winters.octo.desktop.library.sortedByName
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.PageLoadingLine
import app.winters.octo.desktop.ui.FilterBar
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.MediaCard
import app.winters.octo.desktop.ui.NoMatches
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.artistMenu
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.desktop.ui.saveAsLiveList
import app.winters.octo.desktop.ui.rememberGridState
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.show
import app.winters.octo.desktop.ui.windowRect
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.directionChoices
import app.winters.octo.sort.sortScale
import app.winters.octo.subsonic.Artist

// Every song in the library, as a table sorted by any column; the order is
// kept between runs, the filters only for the visit.
@Composable
fun SongsPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    WithLibrary(app) { index ->
        val order = app.songOrder
        val sorted = rememberSorted(index.songs, order)
        val songs = rememberFiltered(sorted, query, fields)?.songs ?: return@WithLibrary PageLoadingLine()
        val facts = remember(index) { libraryLine(index) }
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Added, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "songs",
            order = order,
            onSort = app::sortSongs,
            empty = {
                if (query.filters && index.songs.isNotEmpty()) NoMatches { filter(query.cleared()) }
                else NothingHere("No songs yet", "Once your server has music, every song shows here.")
            },
        ) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.L), verticalAlignment = Alignment.Bottom) {
                    PageTitle("Songs", Modifier.weight(1f), detail = if (query.filters) filteredCount(songs.size, index.songs.size, true) else facts)
                    Row(Modifier.padding(bottom = Space.Xl), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                        GlazeCapsule(OctoIcons.Play, "Play", { app.play(songs) }, lit = true, enabled = songs.isNotEmpty())
                        GlazeCapsule(OctoIcons.Shuffle, "Shuffle", { app.play(songs, shuffle = true) }, enabled = songs.isNotEmpty())
                    }
                }
            }
            if (index.songs.isNotEmpty()) item(key = "filters") { FilterBar(app, query, filter, index.songs, onSaveAsLive = { saveAsLiveList(app, emptyList(), query, order) }) }
        }
    }
}

// How wide a card in a grid is at least; the grid fits as many as the
// window has room for, so covers grow and shrink with it.
private val GridCard = 168.dp

// Every album, as a grid that fills the width, in an order kept between runs.
@Composable
fun AlbumsPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val order = app.albumOrder
        val albums = remember(index, order) { sortAlbums(index.albums, order) }
        LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PageTitle("Albums", Modifier.weight(1f), detail = countText(albums.size, "album"))
                    SortButton(app, SortList.Albums, order, app::sortAlbums)
                }
            }
            if (albums.isEmpty()) header { NothingHere("No albums yet", "Once your server has music, every album shows here.") }
            items(albums, key = { it.id }) { AlbumCard(app, it) }
        }
    }
}

// The newest albums first, as the server dates them.
@Composable
fun RecentlyAddedPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val albums = remember(index) { sortAlbums(index.albums, SortOrder(AlbumSort.RecentlyAdded, descending = true)) }
        LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header { PageTitle("Recently added", detail = if (albums.isEmpty()) null else countText(albums.size, "album") + ", newest first") }
            if (albums.isEmpty()) header { NothingHere("Nothing added yet", "Albums added to your server show here, newest first.") }
            items(albums, key = { it.id }) { AlbumCard(app, it) }
        }
    }
}

// Every artist, as a grid of round pictures.
@Composable
fun ArtistsPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val artists = remember(index) { sortedByName(index.artists) { it.name } }
        LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header { PageTitle("Artists", detail = countText(artists.size, "artist")) }
            if (artists.isEmpty()) header { NothingHere("No artists yet", "Once your server has music, every artist shows here.") }
            items(artists, key = { it.id }) { ArtistCard(app, it) }
        }
    }
}

@Composable
fun ArtistCard(app: AppState, artist: Artist, outside: Boolean = false) {
    MediaCard(
        artist.name,
        if (artist.albumCount > 0) countText(artist.albumCount, "album") else null,
        artist.coverArt,
        onOpen = { app.navigator.go(Page.Artist(artist.id, artist.name)) },
        onMenu = artistMenu(app, artist, outside),
        round = true,
        online = outside,
    )
}

private enum class FavouriteKind(val label: String) { Songs("Songs"), Albums("Albums"), Artists("Artists") }

// Songs, albums and artists starred on the server, which every app on it
// shares.
@Composable
fun FavouritesPage(app: AppState, visit: Visit) {
    val connection = app.connection ?: return
    // The tab is kept with the visit, so Back returns to it.
    var kind by remember(visit.id) { mutableStateOf(FavouriteKind.entries.firstOrNull { it.name == app.navigator.tabOf(visit) } ?: FavouriteKind.Songs) }
    val loaded = rememberLoad(connection) { connection.client.starred() }
    val list = rememberListState(app.navigator, visit)
    val grid = rememberGridState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    val title: @Composable (String?) -> Unit = { detail ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageTitle("Favourites", Modifier.weight(1f), detail = detail)
            GlazeSegments(FavouriteKind.entries, kind, { it.label }, { kind = it; app.navigator.keepTab(visit, it.name) })
        }
    }
    loaded.show(Modifier.padding(horizontal = 28.dp)) { starred ->
        when (kind) {
            FavouriteKind.Songs -> {
                val all = starred.song.filter(app::isStarred)
                val songs = rememberFiltered(all, query, fields)?.songs ?: return@show PageLoadingLine()
                SongTable(
                    app,
                    songs,
                    listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Length),
                    list,
                    id = "favourites",
                    empty = {
                        if (query.filters && all.isNotEmpty()) NoMatches { filter(query.cleared()) }
                        else NothingHere("No favourite songs yet", "Right-click a song and pick Add to favourites.")
                    },
                ) {
                    item(key = "title") { title(if (query.filters) filteredCount(songs.size, all.size, true) else null) }
                    if (all.isNotEmpty()) item(key = "filters") { FilterBar(app, query, filter, all, onSaveAsLive = { saveAsLiveList(app, listOf(FilterPresets.Favourites), query, null) }) }
                }
            }
            FavouriteKind.Albums, FavouriteKind.Artists -> LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
                header { title(null) }
                if (kind == FavouriteKind.Albums) {
                    val albums = starred.album.filter { app.isAlbumStarred(it.id, it.starred) }
                    if (albums.isEmpty()) header { NothingHere("No favourite albums yet") }
                    items(albums, key = { it.id }) { AlbumCard(app, it) }
                } else {
                    val artists = starred.artist.filter { app.isArtistStarred(it.id, it.starred) }
                    if (artists.isEmpty()) header { NothingHere("No favourite artists yet") }
                    items(artists, key = { it.id }) { ArtistCard(app, it) }
                }
            }
        }
    }
}

// A heading across the whole width of a grid.
fun LazyGridScope.header(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

// The order a list is in, in words, opening the choice of orders and
// which way they run, in the phone's words ("A to Z", "Newest").
@Composable
fun SortButton(app: AppState, list: SortList, order: SortOrder, onPick: (SortOrder) -> Unit) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val words = "${order.by.label}, ${sortScale(order.by).label(order.descending)}"
    TextAction(words, {
        app.popups.showUnder(anchor) { close ->
            MenuTitle("Sort by")
            list.options.forEach { option ->
                MenuRow(option.label, { onPick(order.picking(option)); close() }, if (option == order.by) OctoIcons.Check else null)
            }
            MenuSeparator()
            directionChoices(order.by).forEach { choice ->
                MenuRow(choice.label, { onPick(order.copy(descending = choice.descending)); close() }, if (choice.descending == order.descending) OctoIcons.Check else null)
            }
        }
    }, Modifier.onGloballyPositioned { anchor = it.windowRect() }, icon = OctoIcons.Sort)
}

// "2,835 songs · 182 albums · 143 artists · 6 d 14 h", from what the
// library holds.
fun libraryLine(index: LibraryIndex): String {
    val seconds = index.songs.sumOf { it.duration.toLong() }
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    val length = when {
        days > 0 -> "$days d $hours h"
        hours > 0 -> "$hours h $minutes min"
        else -> "$minutes min"
    }
    fun count(n: Int, one: String) = "%,d %s".format(n, if (n == 1) one else one + "s")
    return listOf(count(index.songs.size, "song"), count(index.albums.size, "album"), count(index.artists.size, "artist"), length).joinToString(" · ")
}
