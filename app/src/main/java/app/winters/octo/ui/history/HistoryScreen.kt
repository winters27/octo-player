package app.winters.octo.ui.history

import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.LineSegments
import app.winters.octo.ui.common.LineItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.listening.HistoryDay
import app.winters.octo.listening.HistoryRange
import app.winters.octo.listening.PlayHistory
import app.winters.octo.listening.TotalsAnswer
import app.winters.octo.listening.historyDays
import app.winters.octo.listening.rangeStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.HeaderDropdown
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.LoadStateContent
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.showDropdown
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

// How many plays the recent list goes back, and how many songs the most
// played list shows.
private const val RECENT_PLAYS = 500
private const val MOST_PLAYED = 100

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: PlayHistory,
    private val playback: PlaybackConnection,
) : ViewModel() {
    var range by mutableStateOf(HistoryRange.FourWeeks)

    // The latest plays under a heading per day. Null until the first read.
    val days: StateFlow<List<HistoryDay>?> = history.recentPlays(RECENT_PLAYS)
        .map { historyDays(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The most played songs over the chosen stretch of time, or why the
    // play counts cannot be read now. Null until the first read.
    val mostPlayed: StateFlow<TotalsAnswer<List<PlayedTrack>>?> = snapshotFlow { range }
        .flatMapLatest { range -> history.mostPlayed(rangeStart(range, System.currentTimeMillis(), ZoneId.systemDefault()), MOST_PLAYED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun tryAgain() = history.tryAgain()

    // Plays a list from one of its songs. A song played twice plays once,
    // where it first shows.
    fun play(songs: List<TrackEntity>, from: TrackEntity) {
        val once = songs.distinctBy { it.id }
        playback.playTracks(once.map { it.id }, once.indexOfFirst { it.id == from.id }.coerceAtLeast(0), source = "History")
    }
}

// The two ways to look at what was played.
val HistoryViews = listOf("Recently played", "Most played")

// The stretches of time Most played can cover.
private val ranges = HistoryRange.entries.map { it.label }

// What was played: the latest plays day by day, or the songs played most.
// The control line picks which as two segments, and for the songs played
// most, over what stretch of time.
@Composable
fun HistoryScreen(startMostPlayed: Boolean, onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    var most by rememberSaveable { mutableStateOf(startMostPlayed) }
    val sheet = LocalChoiceSheet.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            PageHeader(
                "History",
                onBack,
                segments = LineSegments(HistoryViews, if (most) 1 else 0, "Show") { most = it == 1 },
                // For the songs played most, over what stretch of time; on a
                // narrow phone it moves into More.
                tail = if (!most) emptyList() else listOf(
                    LineItem(
                        "range",
                        overflow = 0,
                        more = MoreItem("Most played in: ${vm.range.label}", OctoIcons.History) {
                            showDropdown(sheet, "Most played in", ranges, vm.range.ordinal) { vm.range = HistoryRange.entries[it] }
                        },
                    ) { _ ->
                        val range = vm.range
                        HeaderDropdown(range.label, onClick = {
                            showDropdown(sheet, "Most played in", ranges, range.ordinal) { vm.range = HistoryRange.entries[it] }
                        }, description = "Most played, ${range.label}", clickLabel = "Change the time range")
                    },
                ),
            )
            if (most) MostPlayed(vm) else RecentlyPlayed(vm)
        }
    }
}

private val ListPadding = PaddingValues(top = 8.dp, bottom = 140.dp)

@Composable
private fun RecentlyPlayed(vm: HistoryViewModel) {
    val days by vm.days.collectAsStateWithLifecycle()
    val shown = days
    when {
        shown == null -> Loading()
        shown.isEmpty() -> Note("Nothing played yet")
        else -> {
            val songs = remember(shown) { shown.flatMap { day -> day.plays.map { it.track } } }
            val clock = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
            val zone = remember { ZoneId.systemDefault() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                shown.forEach { day ->
                    item(key = "day:${day.label}:${day.plays.first().at}") { SectionTitle(day.label, Modifier.padding(top = 8.dp)) }
                    items(day.plays, key = { "${it.at}:${it.track.id}" }) { play ->
                        val time = Instant.ofEpochMilli(play.at).atZone(zone).format(clock)
                        SongRow(play.track, subtitle = { song -> listOf(song.artist, time).filter { it.isNotEmpty() }.joinToString(" • ") }) {
                            vm.play(songs, play.track)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MostPlayed(vm: HistoryViewModel) {
    val top by vm.mostPlayed.collectAsStateWithLifecycle()
    val range = vm.range
    val answer = top
    if (answer is TotalsAnswer.Problem) {
        LoadStateContent(LoadState.Failed(answer.words), onRetry = vm::tryAgain) {}
        return
    }
    val shown = (answer as? TotalsAnswer.Value)?.value
    when {
        shown == null -> Loading()
        shown.isEmpty() -> Note(if (range == HistoryRange.AllTime) "Nothing played yet" else "Nothing played in the ${range.label.lowercase()}")
        else -> {
            val songs = remember(shown) { shown.map { it.track } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                itemsIndexed(shown, key = { _, it -> it.track.id }) { _, played ->
                    val count = if (played.plays == 1) "1 play" else "${played.plays} plays"
                    SongRow(played.track, subtitle = { song -> listOf(song.artist, count).filter { it.isNotEmpty() }.joinToString(" • ") }) {
                        vm.play(songs, played.track)
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
    )
}
