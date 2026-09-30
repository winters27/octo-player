package app.winters.octo.ui.library.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.health.HEALTH_ALL_CLEAR
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.advice
import app.winters.octo.health.countLabel
import app.winters.octo.health.meaning
import app.winters.octo.health.overview
import app.winters.octo.health.title
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.nav.HealthCheckRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

// Library health on the phone: the same checks and words as the desktop.
// The front lists what was found, each with its count; a check opens its
// own page with what it means, what to do, and its songs, which play from
// a tap and open the usual song menu on a long press.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    dao: CatalogDao,
    private val sources: SourceDao,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Checked again whenever the library changes, away from the main
    // thread. Null until the first check is done.
    val health: StateFlow<PhoneHealth?> = combine(dao.tracks(), dao.albums()) { tracks, albums -> tracks to albums }
        .mapLatest { (tracks, albums) ->
            // Only copies in the library: another kept server's are not.
            val copies = sources.sourceIds().flatMap { sources.tracks(it) }.filter { it.mergedId.isNotEmpty() }
            phoneHealth(tracks, albums, copies)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Plays a check's songs from the one tapped.
    fun play(songs: List<TrackEntity>, from: TrackEntity) {
        val once = songs.distinctBy { it.id }
        playback.playTracks(once.map { it.id }, once.indexOfFirst { it.id == from.id }.coerceAtLeast(0), source = "Library health")
    }
}

private val ListPadding = PaddingValues(top = 4.dp, bottom = 140.dp)

// The checks that found something, one row each with its count.
@Composable
fun LibraryHealthScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val health by vm.health.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            ScreenTitle("Library health")
            val shown = health
            if (shown == null) {
                Loading()
            } else {
                val report = shown.report
                LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                    item { Words(report.overview(), muted = true) }
                    if (report.clean) {
                        item { Text("Everything looks right", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
                        item { Words(HEALTH_ALL_CLEAR) }
                    }
                    itemsIndexed(report.findings, key = { _, check -> check.name }) { index, check ->
                        Column {
                            if (index > 0) Hairline()
                            FindingRow(check.title(), check.countLabel(report.count(check))) { onOpen(HealthCheckRoute(check.name)) }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

// One check: what it means, what to do, then its songs under their
// headings.
@Composable
fun HealthCheckScreen(checkName: String, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val health by vm.health.collectAsStateWithLifecycle()
    val check = HealthCheck.entries.firstOrNull { it.name == checkName }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            ScreenTitle(check?.title() ?: "Library health")
            val shown = health
            when {
                check == null -> Words("This check is not in this version of the app.", muted = true)
                shown == null -> Loading()
                else -> {
                    val lines = remember(shown, check) { shown.lines(check) }
                    val songs = remember(lines) { lines.mapNotNull { (it as? HealthLine.Song)?.track } }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                        item { Words(check.meaning()) }
                        item { Words(check.advice()) }
                        if (lines.isEmpty()) item { Words("Nothing to fix here any more.", muted = true) }
                        itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                            when (line) {
                                is HealthLine.Heading -> Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(line.title, style = OctoType.label, color = OctoColors.TextPrimary)
                                    Text(line.detail, style = OctoType.caption, color = OctoColors.TextMuted)
                                }
                                is HealthLine.Song -> {
                                    val note = line.note
                                    if (note == null) {
                                        SongRow(line.track) { vm.play(songs, line.track) }
                                    } else {
                                        SongRow(line.track, subtitle = { note }) { vm.play(songs, line.track) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

@Composable
private fun FindingRow(title: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.body, color = OctoColors.TextPrimary)
            Text(count, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Words(text: String, muted: Boolean = false) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = if (muted) OctoColors.TextMuted else OctoColors.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

// A hairline between rows.
@Composable
private fun Hairline() {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(OctoColors.TextPrimary.copy(alpha = 0.08f)))
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
    }
}
