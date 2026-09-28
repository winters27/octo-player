package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.history.HistoryRows
import app.winters.octo.desktop.history.historyRows
import app.winters.octo.desktop.history.recentPlays
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.listening.MAX_LOGGED_PLAYS
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId

// Recently played, day by day: every play on this computer, and the last
// play of songs played elsewhere, newest first, each with its time. Read
// again when the playing song changes, since that is when a play counts.
@Composable
fun HistoryPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
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
        SongTable(
            app,
            shown.songs,
            listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Plays, SongColumn.Length),
            list,
            id = "history",
            number = { at, _ -> shown.times.getOrElse(at) { "" } },
            groupTitle = { at -> shown.headings[at] },
            empty = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                    NothingHere("Nothing played yet", "Play something and it shows here.")
                    GlazeCapsule(OctoIcons.Shuffle, "Shuffle your library", { app.play(index.songs, shuffle = true) }, enabled = index.songs.isNotEmpty())
                }
            },
        ) {
            item(key = "title") { PageTitle("Recently played", detail = "Plays on this computer, and the last play of songs played elsewhere.") }
        }
    }
}
