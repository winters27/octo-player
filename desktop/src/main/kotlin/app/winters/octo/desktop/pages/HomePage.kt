package app.winters.octo.desktop.pages

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.home.loadHome
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.desktop.ui.Load
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.MediaCard
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.FailedLine
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.design.Spinner
import app.winters.octo.subsonic.RadioStation
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.launch

// Home: what came in lately, what was played lately and most, a handful at
// random, and Octo's stations and mixes when the server runs them.
@Composable
fun HomePage(app: AppState, visit: Visit) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection) { loadHome(connection) }
    val list = rememberListState(app.navigator, visit)
    var starting by remember { mutableStateOf<String?>(null) }
    LazyColumn(state = list, contentPadding = pagePadding(LocalBottomRoom.current)) {
        item(key = "title") { PageTitle("Home") }
        when (val state = loaded.state) {
            Load.Loading -> item(key = "loading") { LoadingLine() }
            is Load.Failed -> item(key = "failed") { FailedLine(state.message, loaded.retry) }
            is Load.Ready -> {
                val home = state.data
                if (home.isEmpty) item(key = "empty") { NothingHere("Nothing here yet", "Once your server has music, the newest albums show here.") }
                item(key = "stations") {
                    Shelf("Stations", home.stations, { it.id }) { station ->
                        StationCard(station, starting == station.id) {
                            if (starting != null) return@StationCard
                            starting = station.id
                            app.scope.launch {
                                try {
                                    app.play(connection.client.playlist(station.id).entry)
                                } catch (e: SubsonicException) {
                                    app.notice = "Couldn't start ${station.name}: ${e.userMessage()}"
                                } finally {
                                    starting = null
                                }
                            }
                        }
                    }
                }
                item(key = "added") { Shelf("Recently added", home.recentlyAdded, { it.id }) { AlbumCard(app, it) } }
                item(key = "played") { Shelf("Recently played", home.recentlyPlayed, { it.id }) { AlbumCard(app, it) } }
                item(key = "most") { Shelf("Most played", home.mostPlayed, { it.id }) { AlbumCard(app, it) } }
                item(key = "random") { Shelf("Random albums", home.random, { it.id }) { AlbumCard(app, it) } }
            }
        }
    }
}

// A station Octo runs: a click plays the songs it has lined up today.
@Composable
private fun StationCard(station: RadioStation, starting: Boolean, onPlay: () -> Unit) {
    MediaCard(
        station.name,
        "Station",
        station.coverArt ?: station.id,
        onOpen = onPlay,
        badge = if (starting) ({ Spinner(size = 22.dp) }) else null,
        modifier = Modifier,
    )
}
