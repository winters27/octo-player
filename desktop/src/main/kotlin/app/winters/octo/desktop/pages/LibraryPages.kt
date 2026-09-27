package app.winters.octo.desktop.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.winters.octo.catalog.sortKey
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.library.sortSongs
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.MediaCard
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberGridState
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.show
import app.winters.octo.desktop.ui.windowRect
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.glassPanel
import app.winters.octo.design.hoverLift
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.directionChoices
import app.winters.octo.sort.sortScale
import app.winters.octo.subsonic.Artist

// Every song in the library, as a table sorted by any column; the order is
// kept between runs.
@Composable
fun SongsPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    WithLibrary(app) { index ->
        val order = app.songOrder
        val songs = remember(index, order) { sortSongs(index.songs, order) }
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Year, SongColumn.Length, SongColumn.Plays, SongColumn.Added),
            list,
            order = order,
            onSort = app::sortSongs,
            empty = { NothingHere("No songs yet") },
        ) {
            item(key = "title") { PageTitle("Songs", detail = "${songs.size} songs") }
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
                    PageTitle("Albums", Modifier.weight(1f), detail = "${albums.size} albums")
                    SortButton(app, SortList.Albums, order, app::sortAlbums)
                }
            }
            items(albums, key = { it.id }) { AlbumCard(app, it) }
        }
    }
}

// Every artist, as a grid of round pictures.
@Composable
fun ArtistsPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val artists = remember(index) { index.artists.sortedBy { sortKey(it.name) } }
        LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header { PageTitle("Artists", detail = "${artists.size} artists") }
            items(artists, key = { it.id }) { ArtistCard(app, it) }
        }
    }
}

@Composable
fun ArtistCard(app: AppState, artist: Artist, outside: Boolean = false) {
    MediaCard(
        artist.name,
        if (artist.albumCount > 0) (if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums") else null,
        artist.coverArt,
        onOpen = { app.navigator.go(Page.Artist(artist.id, artist.name)) },
        round = true,
        online = outside,
    )
}

// Every genre, with how much the library has in each.
@Composable
fun GenresPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val genres = index.genres
        LazyVerticalGrid(GridCells.Adaptive(220.dp), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header { PageTitle("Genres", detail = "${genres.size} genres") }
            if (genres.isEmpty()) header { NothingHere("No genres", "The songs on your server have no genre tags.") }
            items(genres, key = { it.name }) { genre ->
                Column(
                    Modifier
                        .padding(6.dp)
                        .glassPanel(RoundedCornerShape(14.dp))
                        .hoverLift(RoundedCornerShape(14.dp))
                        .clickable { app.navigator.go(Page.Genre(genre.name)) }
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Txt(genre.name, OctoType.section)
                    Txt("${genre.songs} songs · ${genre.albums} albums", OctoType.caption, OctoColors.TextMuted)
                }
            }
        }
    }
}

// One genre: its albums, then its songs.
@Composable
fun GenrePage(app: AppState, visit: Visit, name: String) {
    val list = rememberListState(app.navigator, visit)
    WithLibrary(app) { index ->
        var order by remember { mutableStateOf(SortList.GenreSongs.default) }
        val all = remember(index, name) { index.songsInGenre(name) }
        val songs = remember(all, order) { sortSongs(all, order) }
        val albums = remember(index, name) { index.albumsInGenre(name) }
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Year, SongColumn.Length),
            list,
            order = order,
            onSort = { order = it },
        ) {
            item(key = "head") {
                ListHeader("Genre", name, albums.firstOrNull()?.coverArt, "${songs.size} songs · ${albums.size} albums", { app.play(songs) }, { app.play(songs, shuffle = true) })
            }
            item(key = "albums") { Shelf("Albums", albums, { it.id }) { AlbumCard(app, it) } }
            item(key = "songs-title") { Txt("Songs", OctoType.headline, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
        }
    }
}

// The server's folders from the top, as it files them.
@Composable
fun FoldersPage(app: AppState, visit: Visit) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection) { connection.client.indexes() }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { top ->
        SongTable(app, top.songs, listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Length), list, covers = false) {
            item(key = "title") { PageTitle("Folders") }
            if (top.folders.isEmpty() && top.songs.isEmpty()) item(key = "empty") { NothingHere("No folders", "This server doesn't list its folders.") }
            items(top.folders, key = { "f:${it.id}" }) { folder -> FolderRow(folder.name) { app.navigator.go(Page.Folder(folder.id, folder.name)) } }
        }
    }
}

// One folder: the folders in it, then its songs, in the server's order.
@Composable
fun FolderPage(app: AppState, visit: Visit, id: String, name: String) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection, id) { connection.client.musicDirectory(id) }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { folder ->
        SongTable(
            app,
            folder.songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Length),
            list,
            covers = false,
            number = { index, song -> song.track?.toString() ?: "${index + 1}" },
        ) {
            item(key = "title") {
                ListHeader("Folder", folder.name.ifEmpty { name }, folder.songs.firstOrNull()?.coverArt, if (folder.songs.isEmpty()) null else songsLine(folder.songs.size, folder.songs.sumOf { it.duration }), { app.play(folder.songs) }, { app.play(folder.songs, shuffle = true) }, playable = folder.songs.isNotEmpty(), art = 140.dp)
            }
            items(folder.folders, key = { "f:${it.id}" }) { sub -> FolderRow(sub.name) { app.navigator.go(Page.Folder(sub.id, sub.name)) } }
        }
    }
}

@Composable
private fun FolderRow(name: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).hoverLift(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(OctoIcons.Folder, size = 18.dp, tint = OctoColors.TextSecondary)
        Txt(name, OctoType.bodySmall, modifier = Modifier.weight(1f))
        Glyph(OctoIcons.Chevron, size = 16.dp, tint = OctoColors.TextMuted)
    }
}

private enum class FavouriteKind(val label: String) { Songs("Songs"), Albums("Albums"), Artists("Artists") }

// Songs, albums and artists starred on the server, which every app on it
// shares.
@Composable
fun FavouritesPage(app: AppState, visit: Visit) {
    val connection = app.connection ?: return
    var kind by remember { mutableStateOf(FavouriteKind.Songs) }
    val loaded = rememberLoad(connection) { connection.client.starred() }
    val list = rememberListState(app.navigator, visit)
    val grid = rememberGridState(app.navigator, visit)
    val title: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageTitle("Favourites", Modifier.weight(1f))
            GlazeSegments(FavouriteKind.entries, kind, { it.label }, { kind = it })
        }
    }
    loaded.show(Modifier.padding(horizontal = 28.dp)) { starred ->
        when (kind) {
            FavouriteKind.Songs -> {
                val songs = starred.song.filter(app::isStarred)
                SongTable(
                    app,
                    songs,
                    listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Length),
                    list,
                    empty = { NothingHere("No favourite songs yet", "Right-click a song and pick Add to favourites.") },
                ) {
                    item(key = "title") { title() }
                }
            }
            FavouriteKind.Albums, FavouriteKind.Artists -> LazyVerticalGrid(GridCells.Adaptive(GridCard), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
                header { title() }
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

// What was played, newest first, from the play dates the server keeps.
@Composable
fun HistoryPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    WithLibrary(app) { index ->
        var order by remember { mutableStateOf(SortOrder(SongSort.RecentlyPlayed, descending = true)) }
        val songs = remember(index, order) { sortSongs(index.history, order) }
        SongTable(
            app,
            songs,
            listOf(SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Plays, SongColumn.Played),
            list,
            order = order,
            onSort = { order = it },
            empty = { NothingHere("No history yet", "Your server hasn't recorded any plays, or doesn't share them.") },
        ) {
            item(key = "title") { PageTitle("History") }
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
