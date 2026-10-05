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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.data.LibraryFiles
import app.winters.octo.data.Upgrades
import app.winters.octo.data.canRunAll
import app.winters.octo.data.serverSongIds
import app.winters.octo.data.userMessage
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.health.FixOutcome
import app.winters.octo.health.FixStep
import app.winters.octo.health.HEALTH_ALL_CLEAR
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.LOOK_UP_TAGS
import app.winters.octo.health.RECENTLY_REMOVED
import app.winters.octo.health.TagChange
import app.winters.octo.health.UNDO_LAST_CHANGE
import app.winters.octo.health.advice
import app.winters.octo.health.changes
import app.winters.octo.health.countLabel
import app.winters.octo.health.fixAllLabel
import app.winters.octo.health.fixMeaning
import app.winters.octo.health.meaning
import app.winters.octo.health.overview
import app.winters.octo.health.title
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.SongLookup
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.nav.HealthCheckRoute
import app.winters.octo.ui.nav.HealthTrashRoute
import app.winters.octo.ui.upgrade.UpgradeAsk
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// What a check page's pop-up shows. Each fix is planned as it opens, so
// what it shows is what it does, even if the library changes meanwhile.
sealed interface HealthSheet {
    // One set of copies put right, keeping `keep` (null for the best).
    data class Duplicate(val title: String, val group: DuplicateGroup<SourceTrackEntity>, val keep: SourceTrackEntity? = null) : HealthSheet

    // Every finding's fix, shown before it runs.
    data class FixAll(val check: HealthCheck, val preview: FixPreview) : HealthSheet

    // One split album joined.
    data class Join(val title: String, val words: String, val steps: List<FixStep>) : HealthSheet

    // Songs with no length, asked of Find higher quality.
    data class Upgrade(val asks: List<UpgradeAsk>) : HealthSheet

    // Songs looked up, then what was found, to pick from.
    data object LookUp : HealthSheet

    // Steps on their way to the server.
    data object Running : HealthSheet

    // How it went.
    // `server` is the one the run went to, where its Undo goes too.
    data class Done(val outcome: FixOutcome, val server: String? = null) : HealthSheet
}

// One song looked up: the library song, its id on the server, and what
// came back, or why nothing did.
data class LookedUp(val track: TrackEntity, val serverId: String, val lookup: SongLookup?, val problem: String?) {
    // What it would change, each picked or not to begin with.
    val changes: List<Pair<TagChange, Boolean>> = lookup?.takeIf { it.found }?.changes().orEmpty()
}

// Songs being looked up: those done so far, out of how many. Each look-up
// is a new `round`, so what was ticked in the last one is forgotten.
data class Lookups(val songs: List<LookedUp> = emptyList(), val total: Int = 0, val finished: Boolean = false, val round: Int = 0)

// Library health on the phone: the same checks and words as the desktop.
// The front lists what was found, each with its count; a check opens its
// own page with what it means, what to do, and its songs, which play from
// a tap and open the usual song menu on a long press. When the signed-in
// Octo server lets this user change its files, each page also offers its
// fixes, which run on the server one song at a time.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    dao: CatalogDao,
    private val sources: SourceDao,
    private val playback: PlaybackConnection,
    private val files: LibraryFiles,
    private val upgrades: Upgrades,
) : ViewModel() {
    // Checked again whenever the library changes, away from the main
    // thread. Null until the first check is done.
    val health: StateFlow<PhoneHealth?> = combine(dao.tracks(), dao.albums()) { tracks, albums -> tracks to albums }
        .mapLatest { (tracks, albums) ->
            val copies = sources.sourceIds().flatMap { sources.tracks(it) }
            phoneHealth(tracks, albums, copies)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // What the server lets this user do to its files.
    val actions: StateFlow<LibraryActions?> = files.actions
    val offers: StateFlow<HealthOffers> = files.actions.map(::healthOffers)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthOffers())

    // The server's source in the library, for which sets of copies are its.
    val source: StateFlow<String?> = files.source

    // Each library song's id on the server, for those it has.
    val serverIds: StateFlow<Map<String, String>> = combine(health, files.source) { health, source ->
        health?.let { serverSongIds(it.copies, source) }.orEmpty()
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val progress = files.progress
    val lastUndo: StateFlow<List<FixStep>> = files.lastUndo
    val canUpgrade: StateFlow<Boolean> = upgrades.canUpgrade

    var sheet by mutableStateOf<HealthSheet?>(null)
        private set

    var lookups by mutableStateOf(Lookups())
        private set

    private var looking: Job? = null
    private var cleared = false

    // An admin may have changed what the server allows since sign-in.
    init {
        viewModelScope.launch { files.refresh() }
    }

    // Plays a check's songs from the one tapped.
    fun play(songs: List<TrackEntity>, from: TrackEntity) {
        val once = songs.distinctBy { it.id }
        playback.playTracks(once.map { it.id }, once.indexOfFirst { it.id == from.id }.coerceAtLeast(0), source = "Library health")
    }

    fun open(next: HealthSheet) {
        sheet = next
    }

    // Closing stops a look-up; a run already sent carries on and says how
    // it went once it is done.
    fun close() {
        looking?.cancel()
        sheet = null
    }

    // Sends the steps, showing how far they have got, then how it went.
    // They go to the server `on` they were planned on: the one signed in to
    // now, or for an Undo, the one the run it puts back went to.
    fun run(steps: List<FixStep>, undo: Boolean = false, on: String? = files.server()) {
        if (steps.isEmpty()) return
        sheet = HealthSheet.Running
        files.launch(steps, undo, on) { outcome ->
            if (!cleared && sheet == HealthSheet.Running) {
                sheet = HealthSheet.Done(outcome, on)
                true
            } else {
                false
            }
        }
    }

    fun stop() = files.stop()

    // Puts back the last fix or delete, and says how that went.
    fun undoLast() {
        val steps = lastUndo.value
        if (actions.value.canRunAll(steps)) run(steps, undo = true)
    }

    // Looks the songs up one at a time, at most a batch, for one review.
    fun lookUp(songs: List<Pair<TrackEntity, String>>) {
        looking?.cancel()
        if (songs.isEmpty()) return
        lookups = Lookups(total = songs.size, round = lookups.round + 1)
        sheet = HealthSheet.LookUp
        looking = viewModelScope.launch {
            for ((track, serverId) in songs) {
                val found = try {
                    LookedUp(track, serverId, files.lookUp(serverId), null)
                } catch (e: SubsonicException) {
                    LookedUp(track, serverId, null, e.userMessage())
                }
                lookups = lookups.copy(songs = lookups.songs + found)
            }
            lookups = lookups.copy(finished = true)
        }
    }

    // Stops looking up; what was found so far can still be written.
    fun stopLookUp() {
        looking?.cancel()
        lookups = lookups.copy(finished = true)
    }

    // The songs among these a higher quality copy could replace.
    suspend fun upgradable(songs: List<TrackEntity>): List<UpgradeAsk> = upgrades.upgradable(songs.map { it.id })

    fun upgrade(asks: List<UpgradeAsk>) {
        upgrades.request(asks)
        sheet = null
    }

    override fun onCleared() {
        cleared = true
    }
}

private val ListPadding = PaddingValues(top = 4.dp, bottom = 140.dp)

// The checks that found something, one row each with its count, then the
// server's trash and the way back from the last change, where it allows.
@Composable
fun LibraryHealthScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val health by vm.health.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val lastUndo by vm.lastUndo.collectAsStateWithLifecycle()
    val canUndo = actions.canRunAll(lastUndo)
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
                    if (offers.restore || canUndo) item { Spacer(Modifier.height(12.dp)) }
                    if (offers.restore) {
                        item(key = "trash") {
                            FindingRow(RECENTLY_REMOVED, "Songs deleted from disk, kept in the server's trash for now") { onOpen(HealthTrashRoute) }
                        }
                    }
                    if (canUndo) {
                        item(key = "undo") {
                            Column {
                                if (offers.restore) Hairline()
                                FindingRow(UNDO_LAST_CHANGE, "Puts back what the last fix or delete changed", chevron = false) { vm.undoLast() }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
        HealthSheetHost(vm)
    }
}

// One check: what it means, what to do, its fix for everything it found
// when the server can, then its songs under their headings.
@Composable
fun HealthCheckScreen(checkName: String, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val health by vm.health.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val serverIds by vm.serverIds.collectAsStateWithLifecycle()
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
                    // Which set of copies or split album each line is under.
                    val groupOf = remember(lines) {
                        var at = -1
                        lines.map { line ->
                            if (line is HealthLine.Heading) at++
                            at
                        }
                    }
                    val fixAll = fixAllButton(check, shown, offers, source, serverIds, vm)
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                        item { Words(check.meaning()) }
                        item { Words(if (fixAll.count > 0) check.fixMeaning() else check.advice()) }
                        if (fixAll.count > 0) {
                            item(key = "fix all") {
                                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
                                    AccentButton(check.fixAllLabel(fixAll.count), onClick = fixAll.open, size = ButtonSize.Medium)
                                }
                            }
                        }
                        if (lines.isEmpty()) item { Words("Nothing to fix here any more.", muted = true) }
                        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                            val group = groupOf[index]
                            when (line) {
                                is HealthLine.Heading -> HeadingLine(line) {
                                    when (check) {
                                        HealthCheck.Duplicates -> if (offers.fixDuplicates && shown.duplicatePlan(group, source) != null) {
                                            RowButton("Fix", "Fix this song") { vm.open(HealthSheet.Duplicate(line.title, shown.copyGroups[group])) }
                                        }
                                        HealthCheck.SplitAlbums -> if (offers.join) {
                                            val plan = shown.joinPlan(group, serverIds)
                                            if (plan != null) RowButton("Join", "Join this album") { vm.open(HealthSheet.Join(line.title, plan.first.words, plan.second)) }
                                        }
                                        else -> Unit
                                    }
                                }
                                is HealthLine.Song -> {
                                    val track = line.track
                                    val trailing: (@Composable () -> Unit)? = when {
                                        check == HealthCheck.Duplicates && offers.fixDuplicates -> {
                                            val plan = shown.duplicatePlan(group, source)
                                            val copy = shown.copyIn(group, track.id)
                                            if (plan != null && copy != null && copy.id != plan.keep.id) {
                                                { RowButton("Keep", "Keep this copy") { vm.open(HealthSheet.Duplicate(headingOf(lines, index), shown.copyGroups[group], copy)) } }
                                            } else {
                                                null
                                            }
                                        }
                                        offers.looksUp(check) && track.id in serverIds -> {
                                            { RowButton("Look up", LOOK_UP_TAGS) { vm.lookUp(listOf(track to serverIds.getValue(track.id))) } }
                                        }
                                        else -> null
                                    }
                                    val note = line.note
                                    if (note == null) {
                                        SongRow(track, trailing = trailing) { vm.play(songs, track) }
                                    } else {
                                        SongRow(track, subtitle = { note }, trailing = trailing) { vm.play(songs, track) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
        HealthSheetHost(vm)
    }
}

// What the button for every finding would do, and how many it takes: 0
// hides it. Songs with no length go to Find higher quality, and songs with
// no track number are looked up first.
private class FixAllButton(val count: Int, val open: () -> Unit)

@Composable
private fun fixAllButton(check: HealthCheck, health: PhoneHealth, offers: HealthOffers, source: String?, serverIds: Map<String, String>, vm: HealthViewModel): FixAllButton {
    when (check) {
        HealthCheck.NoLength -> {
            val songs = health.report.noLength
            val asks by produceState(emptyList<UpgradeAsk>(), songs, offers.upgrade) { value = if (offers.upgrade) vm.upgradable(songs) else emptyList() }
            return FixAllButton(asks.size) { vm.open(HealthSheet.Upgrade(asks)) }
        }
        HealthCheck.NoTrackNumber -> {
            val batch = remember(health, serverIds, offers) { if (offers.lookUp) lookupBatch(health.report.songs(check), serverIds) else emptyList() }
            return FixAllButton(batch.size) { vm.lookUp(batch) }
        }
        else -> {
            val preview = remember(check, health, offers, source, serverIds) { health.fixAllPreview(check, offers, source, serverIds) }
            return FixAllButton(preview.count) { vm.open(HealthSheet.FixAll(check, preview)) }
        }
    }
}

// The heading a line is under.
private fun headingOf(lines: List<HealthLine>, index: Int): String =
    (lines.subList(0, index + 1).lastOrNull { it is HealthLine.Heading } as? HealthLine.Heading)?.title.orEmpty()

// A heading over a set of copies or a split album, with its own fix.
@Composable
private fun HeadingLine(line: HealthLine.Heading, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.title, style = OctoType.label, color = OctoColors.TextPrimary)
            Text(line.detail, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        action()
    }
}

// A small word button at the end of a row; `label` is what it does, for
// screen readers.
@Composable
internal fun RowButton(text: String, label: String, onClick: () -> Unit) {
    Text(
        text,
        style = OctoType.label,
        color = OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun FindingRow(title: String, count: String, chevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.body, color = OctoColors.TextPrimary)
            Text(count, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        if (chevron) Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun Words(text: String, muted: Boolean = false) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = if (muted) OctoColors.TextMuted else OctoColors.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

// A hairline between rows.
@Composable
internal fun Hairline() {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(OctoColors.TextPrimary.copy(alpha = 0.08f)))
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
    }
}
