package app.winters.octo.desktop.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.Connection
import app.winters.octo.discovery.chartSongs
import app.winters.octo.subsonic.CHART_OVERALL
import app.winters.octo.subsonic.ChartChoices
import app.winters.octo.subsonic.OCTO_TOP_SONGS
import app.winters.octo.subsonic.OCTO_TOP_SONGS_CHARTS
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.TopSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// How long a chart is kept before it is asked for again, and the list of
// charts the server's country has.
private const val CHART_KEPT_MS = 60 * 60 * 1000L
private const val CHOICES_KEPT_MS = 24 * 60 * 60 * 1000L

// The Charts page's server side: which charts the server's country has
// (Popular right now, Best New Songs, Trending Songs and the genres) and
// each chart's songs, each kept an hour. Only a server that lists
// octoTopSongs version 2 has them; one signed in before it did is asked
// once, live, since the extensions saved at sign-in may be older than the
// server.
@Stable
class Charts(private val connection: Connection, private val scope: CoroutineScope) {
    // Whether this server has charts, once it has said.
    var offered by mutableStateOf<Boolean?>(null)
        private set

    var choices by mutableStateOf<ChartChoices?>(null)
        private set

    // The chart the page shows.
    var selected by mutableStateOf(CHART_OVERALL)

    // Each chart asked for, by id; an empty list when it had nothing.
    val lists = mutableStateMapOf<String, TopSongs>()

    private val listsAt = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    private var choicesAt = 0L
    private var choicesJob: Job? = null

    private suspend fun offered(): Boolean {
        offered?.let { return it }
        val yes = when {
            connection.supports(OCTO_TOP_SONGS, OCTO_TOP_SONGS_CHARTS) -> true
            !connection.isOcto -> false
            else -> connection.client.supportsIfKnown(OCTO_TOP_SONGS, OCTO_TOP_SONGS_CHARTS) ?: return false
        }
        offered = yes
        return yes
    }

    // Asks whether this server has charts, for the sidebar and Home.
    fun check() {
        if (offered != null) return
        scope.launch { offered() }
    }

    // The charts the server's country has, kept a day.
    fun loadChoices() {
        if (choicesJob?.isActive == true) return
        if (choices != null && System.currentTimeMillis() - choicesAt < CHOICES_KEPT_MS) return
        choicesJob = scope.launch {
            if (!offered()) return@launch
            choices = try {
                connection.client.charts()
            } catch (e: SubsonicException) {
                return@launch
            }
            choicesAt = System.currentTimeMillis()
        }
    }

    // A chart's songs, kept an hour: the whole chart (50, or 100 for Best
    // New Songs and Trending Songs).
    fun load(chart: String) {
        if (jobs[chart]?.isActive == true) return
        val at = listsAt[chart]
        if (at != null && System.currentTimeMillis() - at < CHART_KEPT_MS) return
        jobs[chart] = scope.launch {
            if (!offered()) return@launch
            val got = try {
                connection.client.topChart(chartSongs(chart), chart)
            } catch (e: SubsonicException) {
                return@launch
            }
            lists[chart] = got
            listsAt[chart] = System.currentTimeMillis()
        }
    }
}
