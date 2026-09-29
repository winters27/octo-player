package app.winters.octo.desktop.pages

import app.winters.octo.design.LocalReduceMotion
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.OctoColors
import app.winters.octo.design.PageSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.GenreContents
import app.winters.octo.desktop.library.GenreCount
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.genreContents
import app.winters.octo.desktop.library.genreCovers
import app.winters.octo.desktop.library.mosaicCovers
import app.winters.octo.desktop.library.filteredCount
import app.winters.octo.desktop.library.rememberFiltered
import app.winters.octo.desktop.library.rememberSorted
import app.winters.octo.desktop.library.totalLengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.PageLoadingLine
import app.winters.octo.desktop.ui.FilterBar
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.NoMatches
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberGridState
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.desktop.ui.saveAsLiveList
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.SongFields
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Every genre as a dense list: a small mosaic of its albums' covers, its
// name, and how many songs and albums it has. The covers are worked out
// away from the window's thread and fill in when ready.
@Composable
fun GenresPage(app: AppState, visit: Visit) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val genres = index.genres
        val covers by produceState(emptyMap<String, List<String>>(), index) {
            value = withContext(Dispatchers.Default) { genreCovers(index) }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(PageSize.GenreLine),
            state = grid,
            contentPadding = pagePadding(LocalBottomRoom.current),
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            header { PageTitle("Genres", detail = countText(genres.size, "genre")) }
            if (genres.isEmpty()) {
                header {
                    NextStep(
                        "No genres",
                        "The songs on your server have no genre tags. Tag them, rescan the server, and they show here.",
                        "Go to Albums" to { app.navigator.go(Page.Albums) },
                    )
                }
            }
            items(genres, key = { it.name }) { genre -> GenreLine(genre, covers[genre.name.lowercase()].orEmpty()) { app.navigator.go(Page.Genre(genre.name)) } }
        }
    }
}

// One genre in the list.
@Composable
private fun GenreLine(genre: GenreCount, covers: List<String>, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hoverLift(Corner.PanelShape).clickable(role = Role.Button, onClick = onOpen).padding(Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        Mosaic(covers, Modifier.size(PageSize.Mosaic))
        Column(Modifier.weight(1f)) {
            Txt(genre.name, DesktopType.emphasis)
            Txt(genreCounts(genre.songs, genre.albums), DesktopType.meta, OctoColors.TextMuted)
        }
    }
}

// "42 songs · 5 albums".
fun genreCounts(songs: Int, albums: Int): String =
    listOf(if (songs == 1) "1 song" else "$songs songs", albumCount(albums)).joinToString(" · ")

private fun albumCount(albums: Int) = if (albums == 1) "1 album" else "$albums albums"

// One genre: its counts, Play and Shuffle, links down to its Artists,
// Albums and Songs, then a row of its artists, its albums as a grid, and
// its songs as a table sorted by any column, which the bar above them
// filters.
@Composable
fun GenrePage(app: AppState, visit: Visit, name: String) {
    val list = rememberListState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    WithLibrary(app) { index ->
        // The library is scanned for the genre away from the window's thread.
        val contents by produceState<GenreContents?>(null, index, name) {
            value = withContext(Dispatchers.Default) { genreContents(index, name) }
        }
        val found = contents ?: return@WithLibrary PageLoadingLine()
        GenreBody(app, name, found, list, query, filter, fields)
    }
}

@Composable
private fun GenreBody(
    app: AppState,
    name: String,
    contents: GenreContents,
    list: androidx.compose.foundation.lazy.LazyListState,
    query: LibraryQuery,
    filter: (LibraryQuery) -> Unit,
    fields: SongFields<Song>,
) {
    var order by remember { mutableStateOf(SortList.GenreSongs.default) }
    val songs = rememberFiltered(rememberSorted(contents.songs, order), query, fields)?.songs ?: return PageLoadingLine()
    val covers = remember(contents) { mosaicCovers(contents.albums) }
    var artistsAll by remember(name) { mutableStateOf(false) }
    val spots = remember(name) { HashMap<String, Int>() }
    val scope = rememberCoroutineScope()
    val still = LocalReduceMotion.current
    fun jump(key: String) {
        spots[key]?.let { at -> scope.launch { if (still) list.scrollToItem(at) else list.animateScrollToItem(at) } }
    }
    BoxWithConstraints {
        val columns = cardColumns(maxWidth)
        val artistColumns = cardColumns(maxWidth, PageSize.ArtistCard)
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Year, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "genre",
            order = order,
            onSort = { order = it },
            empty = {
                if (query.filters && contents.songs.isNotEmpty()) {
                    NoMatches { filter(query.cleared()) }
                } else NextStep(
                    "No songs in $name",
                    "The library may have changed since it was read.",
                    "Read the library again" to { app.library?.load() },
                    "All genres" to { app.navigator.go(Page.Genres) },
                )
            },
        ) {
            val page = PageItems(this, spots)
            page.item("head") {
                EntityHeader(
                    "Genre",
                    name,
                    picture = { Mosaic(covers, it) },
                    facts = listOf(
                        Fact(if (query.filters) "${filteredCount(songs.size, contents.songs.size, true)} · ${albumCount(contents.albums.size)}" else genreCounts(songs.size, contents.albums.size)),
                        Fact(if (contents.artists.size == 1) "1 artist" else "${contents.artists.size} artists"),
                        Fact(totalLengthText(songs.sumOf { it.duration })),
                    ),
                ) {
                    PlayAndShuffle({ app.play(songs) }, { app.play(songs, shuffle = true) }, enabled = songs.isNotEmpty())
                }
            }
            if (contents.songs.isEmpty()) return@SongTable
            page.item("jump") {
                JumpLinks(
                    listOfNotNull(
                        ("Artists" to { jump("artists") }).takeIf { contents.artists.isNotEmpty() },
                        ("Albums" to { jump("albums") }).takeIf { contents.albums.isNotEmpty() },
                        "Songs" to { jump("songs") },
                    ),
                )
            }
            if (contents.artists.isNotEmpty()) {
                page.item("artists") {
                    GroupTitle(
                        "Artists",
                        contents.artists.size,
                        action = when {
                            contents.artists.size <= artistColumns -> null
                            artistsAll -> "Show fewer"
                            else -> "Show all ${contents.artists.size}"
                        },
                    ) { artistsAll = !artistsAll }
                }
                page.cards("artist-cards", if (artistsAll) contents.artists else contents.artists.take(artistColumns), artistColumns) { ArtistCard(app, it) }
            }
            if (contents.albums.isNotEmpty()) {
                page.item("albums") { GroupTitle("Albums", contents.albums.size) }
                page.cards("album-cards", contents.albums, columns) { AlbumCard(app, it) }
            }
            page.item("songs") { GroupTitle("Songs", contents.songs.size) }
            page.item("filters") { FilterBar(app, query, filter, contents.songs, onSaveAsLive = { saveAsLiveList(app, listOf(FilterPresets.genre(name)), query, null) }) }
        }
    }
}
