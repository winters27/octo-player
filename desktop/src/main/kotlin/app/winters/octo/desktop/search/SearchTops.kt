package app.winters.octo.desktop.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.Connection
import app.winters.octo.discovery.SEARCH_TOP_SONGS
import app.winters.octo.discovery.TOP_CHART_SONGS
import app.winters.octo.discovery.searchedArtist
import app.winters.octo.subsonic.OCTO_TOP_SONGS
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.TopSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// How long the chart is kept before an empty search asks for it again.
private const val CHART_KEPT_MS = 60 * 60 * 1000L

// The ranked lists an Octo server keeps for search: the top songs of the
// artist a search names, and the chart of the moment for an empty search.
// Only a server that lists octoTopSongs has them; one signed in before it
// did is asked once, live, since the extensions saved at sign-in may be
// older than the server.
@Stable
class SearchTops(private val connection: Connection, private val scope: CoroutineScope) {
    // The searched artist's top songs, or null while there are none to show.
    var artist by mutableStateOf<TopSongs?>(null)
        private set

    // Whether the artist's list shows every song, or only the first few.
    var artistOpen by mutableStateOf(false)

    var chart by mutableStateOf<TopSongs?>(null)
        private set

    var chartOpen by mutableStateOf(false)

    private var offered: Boolean? = null
    private var artistJob: Job? = null
    private var chartJob: Job? = null
    private var chartAt = 0L

    private suspend fun offered(): Boolean = offered ?: (
        connection.supports(OCTO_TOP_SONGS) ||
            connection.isOcto && try {
                connection.client.supports(OCTO_TOP_SONGS)
            } catch (e: SubsonicException) {
                false
            }
        ).also { offered = it }

    // A search came back: the top songs of the artist it named, from among
    // the artists it found in the library and online, or none.
    fun follow(query: String, found: SearchFound, filter: SearchFilter) {
        forget()
        if (filter != SearchFilter.All) return
        val named = searchedArtist(query, found.library.artists + found.outside.artists) { it.name } ?: return
        artistJob = scope.launch {
            if (!offered()) return@launch
            artist = try {
                connection.client.artistTopSongs(named.name, named.id, SEARCH_TOP_SONGS).takeIf { it.entry.isNotEmpty() }
            } catch (e: SubsonicException) {
                null
            }
        }
    }

    // Nothing searched now, or a new search on its way.
    fun forget() {
        artistJob?.cancel()
        artist = null
        artistOpen = false
    }

    // The chart, when an empty search shows; kept an hour.
    fun loadChart() {
        if (chartJob?.isActive == true) return
        if (chart != null && System.currentTimeMillis() - chartAt < CHART_KEPT_MS) return
        chartJob = scope.launch {
            if (!offered()) return@launch
            val got = try {
                connection.client.topChart(TOP_CHART_SONGS)
            } catch (e: SubsonicException) {
                return@launch
            }
            chart = got.takeIf { it.entry.isNotEmpty() }
            chartAt = System.currentTimeMillis()
        }
    }
}
