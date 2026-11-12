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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.AlbumUpgrade
import app.winters.octo.data.FixDone
import app.winters.octo.data.FixUndo
import app.winters.octo.data.LibraryFiles
import app.winters.octo.data.StepSource
import app.winters.octo.data.Upgrades
import app.winters.octo.data.canPutBack
import app.winters.octo.data.stepsOf
import app.winters.octo.data.userMessage
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.songs
import androidx.compose.ui.text.style.TextAlign
import app.winters.octo.health.DuplicateGroup
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
import app.winters.octo.health.healthOverview
import app.winters.octo.health.heading
import app.winters.octo.health.meaning
import app.winters.octo.health.title
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.SongLookup
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.nav.HealthCheckRoute
import app.winters.octo.ui.nav.HealthTrashRoute
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What a check page's pop-up shows. Each fix is planned as it opens, so
// what it shows is what it does, even if the library changes meanwhile.
sealed interface HealthSheet {
    // One set of copies put right, keeping `keep` (null for the best).
    data class Duplicate(val title: String, val group: DuplicateGroup<SourceTrackEntity>, val keep: SourceTrackEntity? = null) : HealthSheet

    // Every finding's fix, shown before it runs: how many, and the first
    // page of them.
    data class FixAll(val check: HealthCheck, val preview: FixPreview) : HealthSheet

    // One split album joined.
    data class Join(val title: String, val words: String, val steps: List<FixStep>) : HealthSheet

    // Songs with no length, asked of Find higher quality: how many, and
    // the first of them.
    data class Upgrade(val asks: AlbumUpgrade) : HealthSheet

    // Songs looked up, then what was found, to pick from.
    data object LookUp : HealthSheet

    // Steps on their way to the server.
    data object Running : HealthSheet

    // How it went.
    // `server` is the one the run went to, where its Undo goes too.
    data class Done(val outcome: FixDone, val server: String? = null) : HealthSheet
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

// The report every Library health page shows, built once for all of them
// while any is open: after each change of the library, and again each
// time a fix finds the set it was asked for gone, so its line goes.
@Singleton
class HealthPages internal constructor(
    build: HealthBuild,
    recheck: HealthRecheck,
    // How long the report stays shared after the last page closes.
    keepMillis: Long,
) {
    @Inject constructor(build: HealthBuild, recheck: HealthRecheck) : this(build, recheck, 5_000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val shown: StateFlow<HealthShown> = build.watch(recheck.gone.drop(1))
        .stateIn(scope, SharingStarted.WhileSubscribed(keepMillis), HealthShown.Loading)
}

// The report a page lists: the one published, also beneath the words for
// why it could not be brought up to date; null before the first.
fun HealthShown.report(): HealthShown.Shown? = when (this) {
    is HealthShown.Shown -> this
    is HealthShown.Problem -> before
    HealthShown.Loading -> null
}

// Which check's page reads which server's ids.
data class HealthAsk(val check: HealthCheck, val source: String?)

// What a check's button for everything does now: how many it takes (0
// hides it), and what a tap opens.
sealed interface FixAllOffer {
    val count: Int

    // Nothing the server in use lets this user do.
    data object None : FixAllOffer {
        override val count = 0
    }

    // A plan made from the report, counted.
    data class Plan(val counted: FixAllCount) : FixAllOffer {
        override val count = counted.count
    }

    // Songs with no length, for Find higher quality.
    data class Upgrade(val asks: AlbumUpgrade) : FixAllOffer {
        override val count = asks.count
    }

    // Songs with no track number, looked up first.
    data class LookUp(val batch: List<Pair<TrackEntity, String>>) : FixAllOffer {
        override val count = batch.size
    }
}

// Library health on the phone: the same checks and words as the desktop.
// The front lists what was found, each with its count; a check opens its
// own page with what it means, what to do, and its songs in pages, which
// play from a tap and open the usual song menu on a long press. When the
// signed-in Octo server lets this user change its files, each page also
// offers its fixes, which run on the server one song at a time.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    pages: HealthPages,
    private val build: HealthBuild,
    private val plans: FixAllPlans,
    private val playback: PlaybackConnection,
    private val files: LibraryFiles,
    private val upgrades: Upgrades,
    private val recheck: HealthRecheck,
    private val reads: LibraryReads,
    private val db: CatalogDatabase,
) : ViewModel() {
    // What the report shows: loading until the first is published, then
    // the last one, with the words for why it could not be brought up to
    // date over it when it could not.
    val shown: StateFlow<HealthShown> = pages.shown

    // What the server lets this user do to its files.
    val actions: StateFlow<LibraryActions?> = files.actions
    val offers: StateFlow<HealthOffers> = files.actions.map(::healthOffers)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthOffers())

    // The server's source in the library, for which sets of copies are its.
    val source: StateFlow<String?> = files.source

    val progress = files.progress

    // How many songs the library holds, for the wait before the first report.
    val songTotal: StateFlow<Int> = db.dao().libraryTrackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lastUndo: StateFlow<FixUndo?> = files.lastUndo
    val canUpgrade: StateFlow<Boolean> = upgrades.canUpgrade

    var sheet by mutableStateOf<HealthSheet?>(null)
        private set

    var lookups by mutableStateOf(Lookups())
        private set

    private var looking: Job? = null
    private var cleared = false

    // Each check's lines and its button for everything, for each check
    // this page shows.
    private val handles = HashMap<HealthCheck, ListHandle<HealthAsk, HealthRow>>()
    private val fixAlls = HashMap<HealthCheck, StateFlow<FixAllOffer?>>()

    // An admin may have changed what the server allows since sign-in.
    init {
        viewModelScope.launch { files.refresh() }
        // Another server in use: a plan or a look-up holds the last one's
        // song ids, so it closes; a run already sent says how it went.
        viewModelScope.launch {
            files.servers.drop(1).collect {
                looking?.cancel()
                lookups = Lookups(round = lookups.round + 1)
                if (sheet != HealthSheet.Running && sheet !is HealthSheet.Done) show(null)
            }
        }
    }

    // A check's lines in pages, read from the published report (No
    // length's from the library), each song with what the server in use
    // can do to it.
    fun lines(check: HealthCheck): ListHandle<HealthAsk, HealthRow> = handles.getOrPut(check) {
        ListHandle(
            viewModelScope,
            HealthAsk(check, files.source.value),
            { ask -> build.lines(ask.check, ask.source) },
            reads,
            { _, loader -> db.tableChanges(loader.tables) },
        ).also { handle -> viewModelScope.launch { files.source.drop(1).collect { handle.ask(HealthAsk(check, it)) } } }
    }

    // What a check's button for everything would do over the report
    // shown, for the server in use; null while it is worked out.
    fun fixAll(check: HealthCheck): StateFlow<FixAllOffer?> = fixAlls.getOrPut(check) {
        combine(shown, files.source, offers) { shown, source, offers -> Triple(shown.report()?.gen ?: if (shown is HealthShown.Problem) -1L else null, source, offers) }
            .distinctUntilChanged()
            .mapLatest { (gen, source, offers) ->
                when {
                    gen == null -> null
                    gen < 0 || !offers.fixesAll(check) -> FixAllOffer.None
                    check == HealthCheck.NoLength -> FixAllOffer.Upgrade(plans.upgradable(upgrades))
                    check == HealthCheck.NoTrackNumber -> FixAllOffer.LookUp(plans.lookUpBatch(check, source))
                    else -> plans.count(check, source, offers.edit)?.let(FixAllOffer::Plan) ?: FixAllOffer.None
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    // Plays a check's songs from the one tapped, each song once.
    fun play(check: HealthCheck, track: TrackEntity) {
        val gen = shown.value.report()?.gen ?: return
        playback.playList(ListSpec.Health(gen, check), ListStart.Row(track.id, null), source = "Library health")
    }

    fun open(next: HealthSheet) = show(next)

    // Opens what a check's button for everything does: its preview, made
    // from the report with its counts and its first page; the songs with
    // no length to ask for; or the songs with no track number looked up.
    fun openFixAll(check: HealthCheck, offer: FixAllOffer) {
        when (offer) {
            FixAllOffer.None -> Unit
            is FixAllOffer.Upgrade -> show(HealthSheet.Upgrade(offer.asks))
            is FixAllOffer.LookUp -> lookUp(offer.batch)
            is FixAllOffer.Plan -> viewModelScope.launch {
                val plan = plans.open(check, files.source.value, offers.value.edit, offer.counted) ?: return@launch
                if (cleared) plan.putAside() else show(HealthSheet.FixAll(check, FixPreview(plan.count, plan.lines, plan)))
            }
        }
    }

    // Opens a set's fix once its copies are checked again: the set as the
    // check finds it now, keeping `keep` when it is still there. A set the
    // check no longer finds opens nothing, and the report is built again
    // at once, so its line goes.
    fun fixGroup(title: String, group: DuplicateGroup<SourceTrackEntity>, keep: SourceTrackEntity? = null) {
        viewModelScope.launch { recheck.fixSheet(title, group, keep)?.let(::show) }
    }

    // Opens a split album's join once its parts are checked again: the
    // album as the check finds it now, with the steps that join it. One the
    // report or the check no longer finds, or with nothing the server could
    // move, opens nothing, and the report is built again at once.
    fun joinAlbum(line: HealthRow.Split) {
        viewModelScope.launch {
            val album = withContext(Dispatchers.IO) { splitAlbumOf(db, line.gen, line.setId) }
            if (album == null) recheck.missing() else recheck.joinSheet(line.title, album, files::serverIds)?.let(::show)
        }
    }

    // Runs a Fix all, its steps made from the report a page at a time. For
    // sets of copies and split albums, each page of them is checked again
    // just before its steps are sent: one the check finds otherwise now is
    // skipped, and the rest are fixed.
    fun runFixAll(preview: FixPreview) {
        // Sent once, from its own window: a second tap finds it gone.
        if ((sheet as? HealthSheet.FixAll)?.preview !== preview) return
        run(preview.plan.steps(recheck::samePage))
    }

    // Closing stops a look-up; a run already sent carries on and says how
    // it went once it is done.
    fun close() {
        looking?.cancel()
        show(null)
    }

    // Sends the steps, showing how far they have got, then how it went.
    // They go to the server `on` they were planned on: the one signed in to
    // now.
    fun run(steps: List<FixStep>, on: String? = files.server()) = run(stepsOf(steps), on)

    fun run(source: StepSource, on: String? = files.server()) {
        if (source.total == 0) {
            files.putAside(source)
            return
        }
        show(HealthSheet.Running)
        files.launch(source, on) { done -> shown(done, on) }
    }

    // Puts back an earlier run, on the server `on` it went to.
    fun undo(undo: FixUndo, on: String?) {
        show(HealthSheet.Running)
        files.launchUndo(undo, on) { done -> shown(done, on) }
    }

    // Shows how a run went, while its window is still up; that window then
    // offers its Undo until it closes.
    private fun shown(done: FixDone, on: String?): Boolean =
        if (!cleared && sheet == HealthSheet.Running) {
            show(HealthSheet.Done(done, on))
            true
        } else {
            false
        }

    // The outcome window of a run whose undo is `owner` closed.
    fun letGo(owner: String) = files.letGo(owner)

    // A new window, or none: a Fix all's preview not sent lets its report go.
    private fun show(next: HealthSheet?) {
        (sheet as? HealthSheet.FixAll)?.takeIf { it != next }?.preview?.plan?.putAside()
        sheet = next
    }

    fun stop() = files.stop()

    // Puts back the last fix or delete, and says how that went.
    fun undoLast() {
        val undo = lastUndo.value ?: return
        if (actions.value.canPutBack(undo)) undo(undo, files.server())
    }

    // Looks the songs up one at a time, at most a batch, for one review.
    fun lookUp(songs: List<Pair<TrackEntity, String>>) {
        looking?.cancel()
        if (songs.isEmpty()) return
        lookups = Lookups(total = songs.size, round = lookups.round + 1)
        show(HealthSheet.LookUp)
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

    // Asks for the songs shown, and counts the rest as left out.
    fun upgrade(asks: AlbumUpgrade) {
        upgrades.request(asks.first, beyond = asks.count - asks.first.size)
        show(null)
    }

    override fun onCleared() {
        cleared = true
        (sheet as? HealthSheet.FixAll)?.preview?.plan?.putAside()
        (sheet as? HealthSheet.Done)?.outcome?.undo?.let { files.letGo(it.owner) }
        handles.values.forEach { it.close() }
    }
}

private val ListPadding = PaddingValues(top = 4.dp, bottom = 140.dp)

// The checks that found something, one row each with its count, then the
// server's trash and the way back from the last change, where it allows,
// and at the foot how many songs were checked.
@Composable
fun LibraryHealthScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val shown by vm.shown.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val lastUndo by vm.lastUndo.collectAsStateWithLifecycle()
    val canUndo = actions.canPutBack(lastUndo)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            PageHeader("Library health", onBack)
            val report = shown.report()
            val problem = (shown as? HealthShown.Problem)?.words
            if (report == null && problem == null) {
                val songTotal by vm.songTotal.collectAsStateWithLifecycle()
                Loading(healthCheckingWords(songTotal))
            } else {
                val findings = report?.let { r -> HealthCheck.entries.filter { r.count(it) > 0 } }.orEmpty()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                    if (problem != null) item(key = "problem") { Words(problem) }
                    if (report != null && findings.isEmpty()) {
                        item { Text("Everything looks right", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
                        item { Words(HEALTH_ALL_CLEAR) }
                    }
                    itemsIndexed(findings, key = { _, check -> check.name }) { index, check ->
                        Column {
                            if (index > 0) Hairline()
                            FindingRow(check.title(), check.countLabel(report!!.count(check))) { onOpen(HealthCheckRoute(check.name)) }
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
                    if (report != null) item(key = "overview") { Footnote(healthOverview(report.checked, findings.size)) }
                }
            }
        }
        HealthSheetHost(vm)
    }
}

// One check: its fix for everything it found on the control line when the
// server can, what it means and what to do in one paragraph, then its songs
// under their headings, a page at a time.
@Composable
fun HealthCheckScreen(checkName: String, onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val check = HealthCheck.entries.firstOrNull { it.name == checkName }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            if (check == null) {
                PageHeader("Library health", onBack)
                Words("This check is not in this version of the app.", muted = true)
            } else {
                CheckPage(check, onBack, vm)
            }
        }
        HealthSheetHost(vm)
    }
}

@Composable
private fun CheckPage(check: HealthCheck, onBack: () -> Unit, vm: HealthViewModel) {
    val shown by vm.shown.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val offer by remember(check) { vm.fixAll(check) }.collectAsStateWithLifecycle()
    val handle = remember(check) { vm.lines(check) }
    val meta by handle.shown.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val items = rememberListRows(handle, state, above = 1)
    val fixAll = offer
    PageHeader(
        check.title(),
        onBack,
        actions = if (fixAll == null || fixAll.count == 0) null else {
            { AccentButton(check.fixAllLabel(fixAll.count), onClick = { vm.openFixAll(check, fixAll) }, size = ButtonSize.Small) }
        },
    )
    val problem = (shown as? HealthShown.Problem)?.words
    when {
        shown.report() == null && problem == null || fixAll == null -> Loading()
        else -> LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = ListPadding) {
            item(key = "words") {
                Column {
                    if (problem != null) Words(problem)
                    Words(checkWords(check.meaning(), if (fixAll.count > 0) check.fixMeaning() else check.advice()))
                }
            }
            if (meta?.count == 0) item(key = "empty") { Words("Nothing to fix here any more.", muted = true) }
            listRows(items, null, heading = {}) { item -> LineRow(check, item?.row, offers, source, vm) }
        }
    }
}

// One line of a check's page: a set's or an album's heading with its fix,
// or a song with its own button where the server can do something to it.
@Composable
private fun LineRow(check: HealthCheck, line: HealthRow?, offers: HealthOffers, source: String?, vm: HealthViewModel) {
    when (line) {
        null -> RowPlaceholder()
        is HealthRow.Copies -> HeadingLine(line.title, line.detail) {
            if (offers.fixDuplicates && source != null && line.group.best.sourceId == source) RowButton("Fix", "Fix this song") { vm.fixGroup(line.title, line.group) }
        }
        is HealthRow.Split -> HeadingLine(line.title, line.detail) {
            if (offers.join && line.joinable) RowButton("Join", "Join this album") { vm.joinAlbum(line) }
        }
        is HealthRow.Song -> {
            val track = line.track
            val group = line.group
            val copy = line.copy
            val serverId = line.serverId
            val trailing: (@Composable () -> Unit)? = when {
                check == HealthCheck.Duplicates && offers.fixDuplicates -> {
                    if (group != null && copy != null && source != null && group.best.sourceId == source && copy.id != group.best.id) {
                        { RowButton("Keep", "Keep this copy") { vm.fixGroup(group.heading(SourceCopyHealth), group, copy) } }
                    } else {
                        null
                    }
                }
                offers.looksUp(check) && serverId != null -> {
                    { RowButton("Look up", LOOK_UP_TAGS) { vm.lookUp(listOf(track to serverId)) } }
                }
                else -> null
            }
            val note = line.note
            if (note == null) {
                SongRow(track, trailing = trailing) { vm.play(check, track) }
            } else {
                SongRow(track, subtitle = { note }, trailing = trailing) { vm.play(check, track) }
            }
        }
    }
}

// A heading over a set of copies or a split album, with its own fix.
@Composable
private fun HeadingLine(title: String, detail: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = OctoType.label, color = OctoColors.TextPrimary)
            Text(detail, style = OctoType.caption, color = OctoColors.TextMuted)
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
    WordsLine(text, muted)
}

// What a check means and what to do about it, as one paragraph.
internal fun checkWords(meaning: String, then: String): String = "$meaning $then"

// A quiet line at the foot of a page, for what is worth knowing but not
// first: how many songs were checked, or how long the trash keeps a song.
@Composable
internal fun Footnote(text: String) {
    Text(
        text,
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun WordsLine(text: String, muted: Boolean) {
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

// The spinner while a page is read, with a quiet line under it when there
// is something to say about the wait.
@Composable
internal fun Loading(words: String? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
        if (words != null) {
            Text(
                words,
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 14.dp),
            )
        }
    }
}

// What the front says while the report is first worked out: how many songs
// it is going through, once that is known.
fun healthCheckingWords(count: Int): String? = if (count <= 0) null else "Checking your ${songs(count)}"
