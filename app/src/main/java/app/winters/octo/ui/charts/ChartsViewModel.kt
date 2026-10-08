package app.winters.octo.ui.charts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.discovery.ChartList
import app.winters.octo.discovery.ChartLists
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.subsonic.CHART_OVERALL
import app.winters.octo.subsonic.ChartChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The Charts page: the charts the server's country has, as chips, and the
// chosen chart's songs.
@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val charts: ChartLists,
    private val playback: PlaybackConnection,
) : ViewModel() {
    var choices by mutableStateOf<List<ChartChoice>>(emptyList())
        private set

    var selected by mutableStateOf(CHART_OVERALL)
        private set

    // The chosen chart's songs, or null while they are on their way.
    var list by mutableStateOf<ChartList?>(null)
        private set

    // Whether the server answered with nothing it can show.
    var none by mutableStateOf(false)
        private set

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            choices = charts.choices()?.chart.orEmpty()
            if (choices.isEmpty() && !charts.offered()) none = true
        }
        load()
    }

    fun pick(chart: String) {
        if (chart == selected) return
        selected = chart
        load()
    }

    private fun load() {
        loading?.cancel()
        list = null
        val chart = selected
        loading = viewModelScope.launch {
            val got = charts.chart(chart)
            if (chart != selected) return@launch
            list = got ?: ChartList(chart, null, "", null, emptyList())
        }
    }

    fun play(index: Int) {
        val songs = list?.songs ?: return
        playback.playTracks(songs.map { it.track.id }, index.coerceAtLeast(0))
    }
}
