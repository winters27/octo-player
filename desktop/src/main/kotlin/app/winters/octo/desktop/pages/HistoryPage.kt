package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.history.HistoryRows
import app.winters.octo.desktop.history.historyRows
import app.winters.octo.desktop.history.recentPlays
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.filteredCount
import app.winters.octo.desktop.library.rememberFiltered
import app.winters.octo.desktop.listening.MAX_LOGGED_PLAYS
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.FilterBar
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.NoMatches
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.query.LibraryQuery
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Recently played, day by day: every play on this computer, and the last
// play of songs played elsewhere, newest first, each with its time. Read
// again when the playing song changes, since that is when a play counts.
@Composable
fun HistoryPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    val query = app.navigator.filterOf(visit)
    val filter: (LibraryQuery) -> Unit = { app.navigator.keepFilter(visit, it) }
    val fields = rememberShownFields(app)
    WithLibrary(app) { index ->
        val playing by remember(app.player) { app.player.state.map { it.current?.song?.id }.distinctUntilChanged() }
            .collectAsState(app.player.state.value.current?.song?.id)
        val rows by produceState<HistoryRows?>(null, index, playing) {
            value = withContext(Dispatchers.IO) {
                val log = app.plays.log()?.read().orEmpty()
                val plays = recentPlays(log, index.songs, logCut = log.size >= MAX_LOGGED_PLAYS)
                historyRows(plays, System.currentTimeMillis(), ZoneId.systemDefault())
            }
        }
        val shown = rows ?: return@WithLibrary LoadingLine()
        val filtered = rememberFiltered(shown.songs, query, fields) ?: return@WithLibrary LoadingLine()
        val headings = remember(shown, filtered) { shown.headingsShown(filtered.songs.size, filtered::placeOf) }
        SongTable(
            app,
            filtered.songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Plays, SongColumn.Length),
            list,
            id = "history",
            number = { at, _ -> shown.times.getOrElse(filtered.placeOf(at)) { "" } },
            groupTitle = { at -> headings[at] },
            empty = {
                if (query.filters && shown.songs.isNotEmpty()) NoMatches { filter(query.cleared()) } else Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                    NothingHere("Nothing played yet", "Play something and it shows here.")
                    GlazeCapsule(OctoIcons.Shuffle, "Shuffle your library", { app.play(index.songs, shuffle = true) }, enabled = index.songs.isNotEmpty())
                }
            },
        ) {
            item(key = "title") {
                PageTitle(
                    "Recently played",
                    detail = if (query.filters) filteredCount(filtered.songs.size, shown.songs.size, true) else "Plays on this computer, and the last play of songs played elsewhere.",
                )
            }
            if (shown.songs.isNotEmpty()) item(key = "filters") { FilterBar(app, query, filter, shown.songs) }
        }
    }
}
