package app.winters.octo.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.listening.HistoryDay
import app.winters.octo.listening.HistoryRange
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.historyDays
import app.winters.octo.listening.mostPlayed
import app.winters.octo.listening.rangeStart
import app.winters.octo.listening.recentHistory
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.choiceAnchor
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.Segmented
import app.winters.octo.ui.common.SongRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    user: UserDao,
    listening: ListeningStore,
    private val playback: PlaybackConnection,
) : ViewModel() {
    var range by mutableStateOf(HistoryRange.FourWeeks)

    // The latest plays under a heading per day. Null until the first read.
    val days: StateFlow<List<HistoryDay>?> = combine(user.recentPlays(RECENT_PLAYS), user.serverPlayedTracks()) { local, server ->
        historyDays(recentHistory(local, server, RECENT_PLAYS), System.currentTimeMillis(), ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The most played songs over the chosen stretch of time.
    val mostPlayed: StateFlow<List<PlayedTrack>?> = snapshotFlow { range }
        .flatMapLatest { range ->
            val since = rangeStart(range, System.currentTimeMillis(), ZoneId.systemDefault())
            val local = if (since == null) user.playedTracks() else user.playedTracksSince(since)
            combine(local, user.serverPlayedTracks(), listening.sent) { mine, server, sent -> mostPlayed(mine, server, sent, since, MOST_PLAYED) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Plays a list from one of its songs. A song played twice plays once,
    // where it first shows.
    fun play(songs: List<TrackEntity>, from: TrackEntity) {
        val once = songs.distinctBy { it.id }
        playback.playTracks(once.map { it.id }, once.indexOfFirst { it.id == from.id }.coerceAtLeast(0), source = "History")
    }
}

// What was played: the latest plays day by day, or the songs played most.
@Composable
fun HistoryScreen(startMostPlayed: Boolean, onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    var most by rememberSaveable { mutableStateOf(startMostPlayed) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            ScreenTitle("History")
            Segmented(
                options = listOf("Recently played", "Most played"),
                selected = if (most) 1 else 0,
                onSelect = { most = it == 1 },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (most) MostPlayed(vm) else RecentlyPlayed(vm)
        }
        BackButton(onBack)
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
    val sheet = LocalChoiceSheet.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        RangeButton(range, Modifier.choiceAnchor(sheet)) {
            sheet.show(
                ChoiceRequest(
                    title = "Most played in",
                    choices = HistoryRange.entries.map { Choice(it.label) },
                    selected = range.ordinal,
                    onPick = { vm.range = HistoryRange.entries[it] },
                ),
            )
        }
    }
    val shown = top
    when {
        shown == null -> Loading()
        shown.isEmpty() -> Note(if (range == HistoryRange.AllTime) "Nothing played yet" else "Nothing played in the ${range.label.lowercase()}")
        else -> {
            val songs = remember(shown) { shown.map { it.track } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
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

// The stretch of time the list covers, as a quiet line like the sort
// button. A tap offers the others.
@Composable
private fun RangeButton(range: HistoryRange, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Change the time range", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Most played, ${range.label}" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(range.label, style = OctoType.label, color = OctoColors.TextSecondary)
        Icon(painterResource(OctoIcons.Collapse), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(14.dp))
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
