package app.winters.octo.desktop.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PageSize
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.createLiveList
import app.winters.octo.desktop.createStarter
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.livelists.LiveListDraft
import app.winters.octo.desktop.livelists.liveListCovers
import app.winters.octo.desktop.livelists.rememberLiveSongs
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.FilterPill
import app.winters.octo.desktop.ui.LiveListMenu
import app.winters.octo.desktop.ui.LiveMark
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberShownFields
import app.winters.octo.desktop.ui.windowRect
import app.winters.octo.desktop.updateLiveList
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListLimits
import app.winters.octo.livelists.LiveListRuleGroups
import app.winters.octo.livelists.LiveListSorts
import app.winters.octo.livelists.LiveListStarters
import app.winters.octo.livelists.RuleGroup
import app.winters.octo.livelists.asksMatch
import app.winters.octo.livelists.limitWords
import app.winters.octo.livelists.liveListName
import app.winters.octo.livelists.liveListSummary
import app.winters.octo.livelists.sortWords
import app.winters.octo.livelists.toggling
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.query.SubsonicSongs
import app.winters.octo.query.artistsIn
import app.winters.octo.query.decadesIn
import app.winters.octo.query.foldText
import app.winters.octo.query.genresIn
import app.winters.octo.query.label
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.directionChoices
import app.winters.octo.subsonic.Song

private val LiveColumns = listOf(
    SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Added, SongColumn.Favourite, SongColumn.Length,
)

// A live list: the songs its rules pick from the whole library, played,
// shuffled, dragged and right-clicked like any list, with a quiet line
// saying what it picks. "Edit rules" turns the top of the page into the
// editor, and the table under it shows each change at once.
@Composable
fun LiveListPage(app: AppState, visit: Visit, id: String, startEditing: Boolean = false) {
    val lists by app.liveLists.lists.collectAsState()
    val list = lists.firstOrNull { it.id == id }
    if (list == null) {
        Box(Modifier.padding(horizontal = PageSide)) {
            NextStep("This live list is gone", "It was deleted, or it belongs to another account.", "Go to Home" to { app.navigator.go(Page.Home) })
        }
        return
    }
    var editing by remember(visit.id) { mutableStateOf(startEditing) }
    val draft = remember(visit.id) { mutableStateOf(LiveListDraft.of(list)) }
    LiveListBody(
        app, visit, list, editing, draft,
        onEdit = {
            draft.value = LiveListDraft.of(list)
            editing = true
        },
        onSave = {
            app.updateLiveList(list, draft.value.savedName, draft.value.query)
            editing = false
        },
        onCancel = { editing = false },
    )
}

// A live list being made: the editor over the songs it would pick. While
// the listener has none, a few starters are offered too.
@Composable
fun NewLiveListPage(app: AppState, visit: Visit, page: Page.NewLiveList) {
    val lists by app.liveLists.lists.collectAsState()
    val draft = remember(visit.id) { mutableStateOf(LiveListDraft(page.name, page.query)) }
    // Decided once, so the starters do not vanish while the page is open.
    val starters = remember(visit.id) { lists.isEmpty() && !page.query.filters }
    LiveListBody(
        app, visit, null, editing = true, draft,
        onEdit = {},
        onSave = { app.createLiveList(draft.value.savedName, draft.value.query) },
        onCancel = { if (!app.navigator.back()) app.navigator.go(Page.Home) },
        starters = starters,
    )
}

@Composable
private fun LiveListBody(
    app: AppState,
    visit: Visit,
    list: LiveList?,
    editing: Boolean,
    draft: MutableState<LiveListDraft>,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    starters: Boolean = false,
) {
    val table = rememberListState(app.navigator, visit)
    val fields = rememberShownFields(app)
    WithLibrary(app) { index ->
        val query = if (editing || list == null) draft.value.query else list.query
        val songs = rememberLiveSongs(index.songs, query, fields) ?: return@WithLibrary LoadingLine("Picking the songs")
        SongTable(
            app,
            songs,
            LiveColumns,
            table,
            id = "livelist",
            empty = {
                if (editing) {
                    NothingHere("No songs match these rules yet", "Take a rule off, or pick Any of these so a song needs to match only one.")
                } else {
                    NextStep("No songs match right now", "As songs are added, played and liked, the ones that match show up here.", "Edit rules" to onEdit)
                }
            },
        ) {
            item(key = "head") {
                if (editing) {
                    LiveListEditor(app, draft, index.songs, songs.size, list == null, onSave, onCancel)
                } else if (list != null) {
                    LiveListHeader(app, list, songs, onEdit)
                }
            }
            if (starters) item(key = "starters") { Starters(app) }
            if (editing) item(key = "preview") { PreviewLine(songs.size) }
        }
    }
}

// The top of a live list: its picture from its first songs, its name, what
// it picks, and Play, Shuffle, Edit rules and More.
@Composable
private fun LiveListHeader(app: AppState, list: LiveList, songs: List<Song>, onEdit: () -> Unit) {
    val covers = remember(songs) { liveListCovers(songs) }
    EntityHeader(
        "Live list",
        list.name,
        picture = { modifier ->
            if (covers.isEmpty()) Cover(null, modifier, shape = Corner.ArtMShape, placeholder = OctoIcons.Filter) else Mosaic(covers, modifier)
        },
        note = { Txt(liveListSummary(list.query, songs.size), DesktopType.meta, OctoColors.TextSecondary, maxLines = 2) },
    ) {
        PlayAndShuffle({ app.play(songs, source = list.name) }, { app.play(songs, shuffle = true, source = list.name) }, enabled = songs.isNotEmpty())
        HeaderIcon(OctoIcons.Filter, "Edit rules", onEdit)
        MoreButton(app, "More") { close -> LiveListMenu(app, list, close, onEdit = onEdit) }
    }
}

// The editor: the name, the rules as pills with Add a rule, whether songs
// must match all or any of them (once there are two), the order and how
// many. Nothing is kept until Save.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveListEditor(
    app: AppState,
    draft: MutableState<LiveListDraft>,
    library: List<Song>,
    count: Int,
    new: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val value = draft.value
    val query = value.query
    fun change(to: LibraryQuery) {
        draft.value = draft.value.copy(query = to)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.L), verticalArrangement = Arrangement.spacedBy(Space.L)) {
        Txt(if (new) "NEW LIVE LIST" else "EDITING LIVE LIST", DesktopType.label, OctoColors.TextMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            GlassField(
                value.name,
                { draft.value = draft.value.copy(name = it) },
                Modifier.width(PageSize.NameField),
                placeholder = liveListName(query),
                onSubmit = onSave,
                onEscape = onCancel,
            )
            Spacer(Modifier.weight(1f))
            GlazeCapsule(null, "Cancel", onCancel, height = ControlHeight.L)
            GlazeCapsule(null, if (new) "Make live list" else "Save", onSave, lit = true, height = ControlHeight.L)
        }
        Txt(
            "Picks songs from your whole library that match these rules, and keeps up as songs are added, played and liked.",
            DesktopType.meta,
            OctoColors.TextMuted,
            maxLines = 2,
        )
        RuleRow(app, draft, library)
        if (query.asksMatch()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                Txt("A song needs to match", DesktopType.body, OctoColors.TextSecondary)
                GlazeSegments(QueryMatch.entries, query.match, { if (it == QueryMatch.All) "All of these" else "Any of these" }, { change(query.copy(match = it)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Txt("Order", DesktopType.body, OctoColors.TextMuted)
            OrderButton(app, query.sort) { change(query.copy(sort = it)) }
            Txt("How many", DesktopType.body, OctoColors.TextMuted, Modifier.padding(start = Space.L))
            LimitButton(app, query.limit) { change(query.copy(limit = it)) }
        }
        Separator()
    }
}

// The words field, each rule as a pill (a click takes it off), and Add a
// rule, which reads the draft as it is now so picks add up while it is open.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(app: AppState, draft: MutableState<LiveListDraft>, library: List<Song>) {
    val query = draft.value.query
    fun change(to: LibraryQuery) {
        draft.value = draft.value.copy(query = to)
    }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.M),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        GlassField(
            query.text,
            { change(query.copy(text = it)) },
            Modifier.width(FrameSize.FilterWidth).height(ControlHeight.S),
            placeholder = "With these words",
            icon = OctoIcons.Search,
            trailing = if (query.text.isEmpty()) null else ({
                IconAction(OctoIcons.Close, "Clear the words", { change(query.copy(text = "")) }, size = ControlHeight.Xs, iconSize = IconSize.Inline)
            }),
        )
        query.rules.forEach { rule -> FilterPill(rule.label()) { change(query.without(rule)) } }
        TextAction(
            if (query.rules.isEmpty()) "Add a rule" else "Add another rule",
            { app.popups.showUnder(anchor) { close -> RuleMenu({ draft.value.query }, ::change, library, close) } },
            Modifier.onGloballyPositioned { anchor = it.windowRect() },
            icon = OctoIcons.Add,
        )
    }
}

private sealed interface RulePage {
    data object Main : RulePage
    data class Group(val group: RuleGroup) : RulePage
    data object Rating : RulePage
    data object Genre : RulePage
    data object Artist : RulePage
    data object Year : RulePage
}

// Every rule a live list offers, a group to a page. Rules on a field that
// takes several (genre, artist, year) keep the menu open so more can be
// ticked; any other pick closes it.
@Composable
private fun ColumnScope.RuleMenu(current: () -> LibraryQuery, onChange: (LibraryQuery) -> Unit, library: List<Song>, close: () -> Unit) {
    var page by remember { mutableStateOf<RulePage>(RulePage.Main) }
    val query = current()
    fun on(rule: QueryRule) = rule in query.rules
    fun pick(rule: QueryRule, stay: Boolean = false) {
        onChange(query.toggling(rule))
        if (!stay) close()
    }
    val back = { page = RulePage.Main }
    when (val shown = page) {
        RulePage.Main -> {
            MenuTitle("Add a rule")
            LiveListRuleGroups.filter { it.title != "Other" }.forEach { group ->
                MenuRow(group.title, { page = RulePage.Group(group) }, more = true, checked = group.choices.any { on(it.rule) })
            }
            MenuRow("Rating", { page = RulePage.Rating }, more = true)
            MenuSeparator()
            MenuRow("Genre", { page = RulePage.Genre }, more = true)
            MenuRow("Artist", { page = RulePage.Artist }, more = true)
            MenuRow("Year", { page = RulePage.Year }, more = true)
            MenuSeparator()
            LiveListRuleGroups.firstOrNull { it.title == "Other" }?.choices?.forEach { choice ->
                MenuRow(choice.words, { pick(choice.rule) }, checked = on(choice.rule))
            }
        }
        is RulePage.Group -> {
            MenuRow("Back", back, OctoIcons.Back)
            MenuSeparator()
            MenuTitle(shown.group.title)
            shown.group.choices.forEach { choice -> MenuRow(choice.words, { pick(choice.rule) }, checked = on(choice.rule)) }
        }
        RulePage.Rating -> {
            MenuRow("Back", back, OctoIcons.Back)
            MenuSeparator()
            for (stars in 5 downTo 1) {
                val rule = FilterPresets.ratingAtLeast(stars)
                MenuRow(FilterPresets.ratingWords(stars), { pick(rule) }, checked = on(rule))
            }
        }
        RulePage.Genre -> {
            val genres = remember(library) { genresIn(library, SubsonicSongs) }
            FindAndPick("Find a genre", genres, back) { name -> FilterPresets.genre(name).let { MenuRow(name, { pick(it, stay = true) }, checked = on(it)) } }
        }
        RulePage.Artist -> {
            val artists = remember(library) { artistsIn(library, SubsonicSongs) }
            FindAndPick("Find an artist", artists, back) { name -> FilterPresets.artist(name).let { MenuRow(name, { pick(it, stay = true) }, checked = on(it)) } }
        }
        RulePage.Year -> {
            MenuRow("Back", back, OctoIcons.Back)
            MenuSeparator()
            val decades = remember(library) { decadesIn(library, SubsonicSongs).asReversed() }
            decades.forEach { start -> FilterPresets.decade(start).let { MenuRow("The ${start}s", { pick(it, stay = true) }, checked = on(it)) } }
        }
    }
}

// The most names a pick list shows at once; typing finds the rest.
private const val PICK_ROWS = 60

// A long list to pick from, with a field that narrows it as you type.
@Composable
private fun ColumnScope.FindAndPick(hint: String, names: List<String>, back: () -> Unit, row: @Composable (String) -> Unit) {
    var find by remember { mutableStateOf("") }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.Xs)) {
        GlassField(find, { find = it }, Modifier.fillMaxWidth(), placeholder = hint, icon = OctoIcons.Search)
    }
    val wanted = foldText(find)
    val shown = if (wanted.isEmpty()) names else names.filter { foldText(it).contains(wanted) }
    shown.take(PICK_ROWS).forEach { row(it) }
    if (shown.size > PICK_ROWS) Txt("Type to find the other ${shown.size - PICK_ROWS}", DesktopType.meta, OctoColors.TextMuted, Modifier.padding(horizontal = Space.Xl, vertical = Space.S))
    if (shown.isEmpty()) Txt("Nothing by that name", DesktopType.meta, OctoColors.TextMuted, Modifier.padding(horizontal = Space.Xl, vertical = Space.S))
}

// The order the list runs in, in words, opening the orders and which way.
@Composable
private fun OrderButton(app: AppState, sort: QuerySort?, onPick: (QuerySort?) -> Unit) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val order = sort?.order()
    val words = sort?.let(::sortWords)?.replaceFirstChar(Char::uppercaseChar) ?: "In library order"
    TextAction(words, {
        app.popups.showUnder(anchor) { close ->
            MenuTitle("Order")
            LiveListSorts.forEach { option ->
                val picked = order?.by == option
                MenuRow(option.label, { onPick(QuerySort.of(order?.takeIf { picked } ?: SortOrder(option, option.startsDescending))); close() }, checked = picked)
            }
            if (order != null) {
                MenuSeparator()
                directionChoices(order.by).forEach { choice ->
                    MenuRow(choice.label, { onPick(QuerySort.of(order.copy(descending = choice.descending))); close() }, checked = choice.descending == order.descending)
                }
            }
        }
    }, Modifier.onGloballyPositioned { anchor = it.windowRect() }, icon = OctoIcons.Sort)
}

// How many songs it keeps, opening the choices with a word on when to use one.
@Composable
private fun LimitButton(app: AppState, limit: Int?, onPick: (Int?) -> Unit) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    TextAction(limitWords(limit), {
        app.popups.showUnder(anchor) { close ->
            MenuTitle("How many songs")
            Txt("Keep it short for a list like a top 50.", DesktopType.meta, OctoColors.TextMuted, Modifier.padding(horizontal = Space.Xl).padding(bottom = Space.S))
            LiveListLimits.forEach { choice -> MenuRow(limitWords(choice), { onPick(choice); close() }, checked = choice == limit) }
        }
    }, Modifier.onGloballyPositioned { anchor = it.windowRect() }, icon = OctoIcons.Songs)
}

// How many songs the rules pick now, above the preview.
@Composable
private fun PreviewLine(count: Int) {
    if (count == 0) return
    Txt(
        if (count == 1) "1 song matches right now" else "%,d songs match right now".format(count),
        DesktopType.meta,
        OctoColors.TextMuted,
        Modifier.padding(bottom = Space.M),
    )
}

// Ready-made live lists, offered while the listener has none. One is made
// only when picked.
@Composable
private fun Starters(app: AppState) {
    Column(Modifier.fillMaxWidth().padding(bottom = Space.Xl), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        Txt("Or start with one of these", DesktopType.section, modifier = Modifier.padding(bottom = Space.S))
        LiveListStarters.forEach { starter ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hoverLift(Corner.RowShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { app.createStarter(starter) }
                    .padding(horizontal = Space.M, vertical = Space.S),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.L),
            ) {
                LiveMark(ControlHeight.M)
                Column(Modifier.weight(1f)) {
                    Txt(starter.name, DesktopType.emphasis)
                    Txt(starter.detail, DesktopType.meta, OctoColors.TextMuted)
                }
                Txt("Make it", DesktopType.meta, OctoColors.TextSecondary)
            }
        }
    }
}
