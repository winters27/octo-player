package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.filteredCount
import app.winters.octo.desktop.library.rememberFiltered
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.FilterBar
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.NoMatches
import app.winters.octo.desktop.ui.SongPlace
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.desktop.ui.show
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Album

// A playlist: its songs in the playlist's own order, which the bar under
// its heading filters. Removals still go by the playlist's own places.
@Composable
fun PlaylistPage(app: AppState, visit: Visit, id: String) {
    val connection = app.connection ?: return
    // Opened again after songs are added from a menu, so it shows them.
    val known = app.playlists.firstOrNull { it.id == id }
    val loaded = rememberLoad(connection, id, known?.songCount, known?.changed) { connection.client.playlist(id) }
    val list = rememberListState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    loaded.show(Modifier.padding(horizontal = 28.dp)) { playlist ->
        val all = playlist.entry
        val shown = rememberFiltered(all, query, fields) ?: return@show LoadingLine()
        val songs = shown.songs
        SongTable(
            app,
            songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Favourite, SongColumn.Length),
            list,
            id = "playlist",
            place = { picked -> SongPlace.Playlist(id, picked.map { shown.placeOf(it.position) }) },
            // The number is the song's place in the playlist, filtered or not.
            number = { index, _ -> "${shown.placeOf(index) + 1}" },
            empty = {
                if (query.filters && all.isNotEmpty()) NoMatches { filter(query.cleared()) }
                else NothingHere("This playlist is empty", "Right-click songs anywhere and pick Add to playlist.")
            },
        ) {
            item(key = "head") {
                ListHeader(
                    "Playlist",
                    playlist.name,
                    playlist.coverArt,
                    listOfNotNull(
                        playlist.owner?.let { "By $it" },
                        if (query.filters) filteredCount(songs.size, all.size, true) else songsLine(all.size, all.sumOf { it.duration }),
                    ).joinToString(" · "),
                    { app.play(songs) },
                    { app.play(songs, shuffle = true) },
                    playable = songs.isNotEmpty(),
                    subtitle = playlist.comment?.takeIf(String::isNotBlank)?.let { { Txt(it, OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 2) } },
                )
            }
            if (all.isNotEmpty()) item(key = "filters") { FilterBar(app, query, filter, all) }
        }
    }
}
