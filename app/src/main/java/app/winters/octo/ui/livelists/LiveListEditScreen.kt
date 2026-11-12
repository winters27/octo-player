package app.winters.octo.ui.livelists

import app.winters.octo.ui.common.rememberOpenedBeside
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.design.GlassPopup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LiveChoices
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListDraft
import app.winters.octo.livelists.LiveListLimits
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListSorts
import app.winters.octo.livelists.LiveListStarter
import app.winters.octo.livelists.LiveListStarters
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.livelists.LiveShown
import app.winters.octo.livelists.asksMatch
import app.winters.octo.livelists.limitWords
import app.winters.octo.livelists.liveListName
import app.winters.octo.livelists.matchWords
import app.winters.octo.livelists.sortWords
import app.winters.octo.livelists.toggling
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.Shown
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QuerySort
import app.winters.octo.query.label
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortRequest
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.library.FilterChip
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = LiveListEditViewModel.Factory::class)
class LiveListEditViewModel @AssistedInject constructor(
    @Assisted private val id: String?,
    @Assisted private val start: LibraryQuery?,
    private val store: LiveListStore,
    private val songs: LiveListSongs,
    private val playback: PlaybackConnection,
    private val totals: PlayTotals,
    reads: LibraryReads,
    db: CatalogDatabase,
) : ViewModel() {
    private val _draft = MutableStateFlow(editorStart(null, start))
    val draft: StateFlow<LiveListDraft> = _draft

    // What the editor started from, to tell whether leaving loses anything.
    private var started = _draft.value

    // Whether the draft has changes not saved yet.
    val unsaved: Boolean get() = unsavedChanges(started, _draft.value)

    // Whether to offer the starters, worked out once.
    private val _starters = MutableStateFlow(false)
    val starters: StateFlow<Boolean> = _starters

    // The preview's own list, worked out in the database as the rules
    // change and let go with the editor.
    private val owner = songs.draft()

    // How many songs the rules pick now and how long they are together:
    // loading before the first walk, or why it could not be worked out.
    val shown: StateFlow<LiveShown> = songs.watch(owner, null, _draft.map { it.query }.distinctUntilChanged())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveShown.Loading)

    // The songs the rules pick, in pages, in the list's order.
    val preview: ListHandle<Unit, TrackEntity> = ListHandle(viewModelScope, Unit, { songs.pages(owner) }, reads, { _, loader -> db.tableChanges(loader.tables) })

    val choices: StateFlow<LiveChoices> = songs.choices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveChoices())

    // Whether play counts could not be brought up to date; Try again asks
    // once more.
    val totalsFailed: StateFlow<Boolean> = totals.failed

    fun tryAgain() = totals.tryAgain()

    init {
        viewModelScope.launch {
            if (id != null) store.byId(id)?.let {
                started = editorStart(it, null)
                _draft.value = started
            }
            _starters.value = offersStarters(store.lists.first(), id, start)
        }
    }

    fun rename(name: String) {
        _draft.value = _draft.value.copy(name = name)
    }

    fun change(query: LibraryQuery) {
        _draft.value = _draft.value.copy(query = query)
    }

    // Keeps the list, then hands back its id.
    fun save(then: (String) -> Unit) {
        val draft = _draft.value
        viewModelScope.launch {
            val saved = if (id == null) {
                store.save(LiveList.new(draft.savedName, draft.query, System.currentTimeMillis()))
            } else {
                val old = store.byId(id) ?: return@launch
                store.save(old.copy(name = draft.savedName, query = draft.query))
            }
            then(saved.id)
        }
    }

    // A starter the listener picked, made as it is.
    fun make(starter: LiveListStarter, then: (String) -> Unit) {
        viewModelScope.launch { then(store.save(LiveList.new(starter.name, starter.query, System.currentTimeMillis())).id) }
    }

    // Plays the preview from the song tapped: the draft's rules as a list.
    fun play(item: ListItem<TrackEntity>) {
        val draft = _draft.value
        playback.playList(
            ListSpec.Live(null, draft.query, draft.savedName),
            ListStart.Row(item.row.id, item.key),
            source = draft.savedName,
            shown = Shown(item.row.title, item.row.artist, item.row.artwork),
        )
    }

    override fun onCleared() {
        preview.close()
        songs.discard(owner)
    }

    @AssistedFactory
    interface Factory {
        fun create(id: String?, start: LibraryQuery?): LiveListEditViewModel
    }
}

// A live list's editor: its name, words, rules as chips (a tap takes one
// off) with Add a rule, whether a song needs to match all or any of them
// once there are two, the order and how many. The songs it would pick show
// under it as the rules change. A new one offers starters while the
// listener has no live lists.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveListEditScreen(
    id: String?,
    start: LibraryQuery?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    vm: LiveListEditViewModel = hiltViewModel<LiveListEditViewModel, LiveListEditViewModel.Factory> { it.create(id, start) },
) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val starters by vm.starters.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    val totalsFailed by vm.totalsFailed.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val query = draft.query
    val new = id == null
    // Leaving with changes not saved asks first; without, it just goes.
    var discarding by remember { mutableStateOf(false) }
    val leave = { if (vm.unsaved) discarding = true else onBack() }
    BackHandler(enabled = !discarding && vm.unsaved) { discarding = true }

    // The page of choices to ask next. The sheet closes itself after a pick,
    // so the next page opens once that has happened.
    var next by remember { mutableStateOf<RuleAsk?>(null) }
    LaunchedEffect(next) {
        val ask = next ?: return@LaunchedEffect
        next = null
        if (ask.rules.isEmpty()) return@LaunchedEffect
        sheet.show(ChoiceRequest(ask.title, ask.rules.map { Choice(it.first) }, pickedIn(ask.rules.map { it.second }, vm.draft.value.query)) { i ->
            vm.change(vm.draft.value.query.toggling(ask.rules[i].second))
        })
    }

    // Asks which rule to add, a page at a time.
    fun addRule() {
        val menu = ruleMenu()
        sheet.show(ChoiceRequest("Add a rule", menu.map { Choice(it.first) }, -1) { picked ->
            next = when (val pick = menu[picked].second) {
                is RulePick.Group -> RuleAsk(pick.group.title, pick.group.choices.map { it.words to it.rule })
                RulePick.Rating -> RuleAsk("Rating", RatingRules)
                RulePick.Genre -> RuleAsk("Genre", choices.genres.map { it to FilterPresets.genre(it) })
                RulePick.Artist -> RuleAsk("Artist", choices.artists.map { it to FilterPresets.artist(it) })
                RulePick.Year -> RuleAsk("Year", choices.decades.asReversed().map { "The ${it}s" to FilterPresets.decade(it) })
                is RulePick.Rule -> {
                    vm.change(vm.draft.value.query.toggling(pick.rule))
                    null
                }
            }
        })
    }

    // The editor's own lines above the preview's songs.
    val above = 7 + (if (query.asksMatch()) 1 else 0) + (if (starters) 1 + LiveListStarters.size else 0)
    val state = rememberLazyListState()
    val items = rememberListRows(vm.preview, state, above = above)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
            // Back leaves, asking first when there are changes; the save is
            // on the control line.
            item(key = "title") {
                PageHeader(
                    if (new) "New live list" else "Edit live list",
                    leave,
                    actions = { AccentButton(if (new) "Make live list" else "Save", onClick = { vm.save(onSaved) }, size = ButtonSize.Small) },
                )
            }
            item(key = "name") {
                GlassInput(draft.name, vm::rename, placeholder = liveListName(query), modifier = Modifier.padding(horizontal = 20.dp))
            }
            item(key = "about") {
                Text(
                    "Picks songs from your whole library that match these rules, and keeps up as songs are added, played and liked.",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            // The words rule reads as a sentence, "Songs with ...", so it
            // never passes for a second name field.
            item(key = "words") {
                GlassInput(
                    query.text,
                    { vm.change(query.copy(text = it)) },
                    placeholder = WORDS_PLACEHOLDER,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    leading = { Text(WORDS_LEAD, style = OctoType.body, color = OctoColors.TextMuted, modifier = Modifier.padding(end = 8.dp)) },
                )
            }
            item(key = "rules") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    query.rules.forEach { rule -> FilterChip(rule.label(), on = true) { vm.change(query.without(rule)) } }
                    FilterChip(if (query.rules.isEmpty()) "Add a rule" else "Add another rule", on = false, onClick = ::addRule)
                }
            }
            if (query.asksMatch()) {
                item(key = "match") {
                    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("A song needs to match", style = OctoType.caption, color = OctoColors.TextMuted)
                        FilterChip("All of these", on = query.match == QueryMatch.All) { vm.change(query.copy(match = QueryMatch.All)) }
                        FilterChip("Any of these", on = query.match == QueryMatch.Any) { vm.change(query.copy(match = QueryMatch.Any)) }
                    }
                }
            }
            item(key = "order") {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Order", style = OctoType.caption, color = OctoColors.TextMuted)
                    val order = query.sort?.order()
                    QuietButton(query.sort?.let(::sortWords)?.replaceFirstChar(Char::uppercaseChar) ?: "In library order") {
                        val now = order ?: SortOrder(LiveListSorts.first(), LiveListSorts.first().startsDescending)
                        sheet.show(SortRequest(LiveListSorts, now) { vm.change(vm.draft.value.query.copy(sort = QuerySort.of(it))) })
                    }
                    Text("How many", style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(start = 12.dp))
                    QuietButton(limitWords(query.limit)) {
                        sheet.show(ChoiceRequest("How many songs", LiveListLimits.map { Choice(limitWords(it)) }, LiveListLimits.indexOf(query.limit)) { i ->
                            vm.change(vm.draft.value.query.copy(limit = LiveListLimits[i]))
                        })
                    }
                }
            }
            if (starters) {
                item(key = "starters-title") { SectionTitle("Or start with one of these") }
                LiveListStarters.forEach { starter ->
                    item(key = "starter:${starter.name}") { StarterLine(starter) { vm.make(starter, onSaved) } }
                }
            }
            // How many the rules pick, or why that could not be worked out
            // over what they picked before; nothing before the first walk.
            val now = when (val s = shown) {
                is LiveShown.Shown -> s
                is LiveShown.Problem -> s.before
                LiveShown.Loading -> null
            }
            val problem = (shown as? LiveShown.Problem)?.words
            item(key = "count") {
                Column {
                    if (problem != null) ProblemLine(problem, if (totalsFailed) vm::tryAgain else null)
                    if (now != null) {
                        Text(
                            if (now.count == 0) NO_RULE_MATCHES else matchWords(now.count),
                            style = OctoType.caption,
                            color = OctoColors.TextMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (now != null) {
                listRows(items, null, heading = {}) { item ->
                    if (item == null) RowPlaceholder() else SongRow(item.row) { vm.play(item) }
                }
            }
        }
        GlassPopup(
            visible = discarding,
            anchor = rememberOpenedBeside(discarding),
            onDismiss = { discarding = false },
            backdrop = LocalHaze.current,
            title = DISCARD_CHANGES,
        ) {
            PopupQuestion(
                DISCARD_CHANGES,
                if (new) "This live list has not been made yet." else "Your changes to this live list are not saved.",
                "Discard",
                onConfirm = {
                    discarding = false
                    onBack()
                },
                onCancel = { discarding = false },
                cancel = "Keep editing",
            )
        }
    }
}

const val DISCARD_CHANGES = "Discard changes?"

// The words rule's sentence: "Songs with" before what is typed.
const val WORDS_LEAD = "Songs with"
const val WORDS_PLACEHOLDER = "these words"

const val NO_RULE_MATCHES = "No songs match these rules yet. Take a rule off, or pick Any of these so a song needs to match only one."
