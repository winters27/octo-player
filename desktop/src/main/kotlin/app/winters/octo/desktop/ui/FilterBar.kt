package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuBack
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.SelectedFill
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.ShownSongFields
import app.winters.octo.desktop.ratingOf
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryRule
import app.winters.octo.query.SongFields
import app.winters.octo.query.SubsonicSongs
import app.winters.octo.query.decadesIn
import app.winters.octo.query.genresIn
import app.winters.octo.query.label
import app.winters.octo.subsonic.Song

// Songs as this window shows them, hearts and ratings changed a moment ago
// included, for the filters to read.
@Composable
fun rememberShownFields(app: AppState): SongFields<Song> = remember(app) { ShownSongFields(app::isStarred, app::ratingOf) }

// A song list's filters: a quiet field for words, each filter that is on
// as a pill (a click takes it off), Add filter for the quick ones, and
// Clear all while anything is on. `songs` is the whole list, for the
// genres and decades Add filter offers. While shown, the find shortcut can
// bring the keyboard to the field. On a page's control line (`oneLine`)
// they keep to the line and scroll sideways; elsewhere they wrap.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    app: AppState,
    query: LibraryQuery,
    onChange: (LibraryQuery) -> Unit,
    songs: List<Song>,
    modifier: Modifier = Modifier,
    // Keeps these filters as a live list, on pages whose songs a live list can pick.
    onSaveAsLive: (() -> Unit)? = null,
    oneLine: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    DisposableEffect(focus) {
        app.pageFilterFocus = focus
        onDispose { if (app.pageFilterFocus === focus) app.pageFilterFocus = null }
    }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val field: @Composable () -> Unit = {
        GlassField(
            query.text,
            { onChange(query.copy(text = it)) },
            Modifier.width(FrameSize.FilterWidth).height(if (oneLine) ControlHeight.L else ControlHeight.S),
            placeholder = "Filter these songs",
            icon = OctoIcons.Filter,
            focusRequester = focus,
            onEscape = { onChange(query.copy(text = "")) },
            trailing = if (query.text.isEmpty()) null else ({
                IconAction(OctoIcons.Close, "Clear the words", { onChange(query.copy(text = "")) }, size = ControlHeight.Xs, iconSize = IconSize.Inline)
            }),
        )
    }
    val add: @Composable () -> Unit = {
        TextAction(
            "Add filter",
            { showAddFilter(app, anchor, query, onChange, songs) },
            Modifier.onGloballyPositioned { anchor = it.windowRect() },
            icon = OctoIcons.Add,
        )
    }
    if (oneLine) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            field()
            if (query.filters) FiltersOn(app, query, onChange, onSaveAsLive)
            add()
        }
        return
    }
    FlowRow(
        modifier.fillMaxWidth().padding(bottom = Space.L),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.M),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        field()
        query.rules.forEach { rule -> FilterPill(rule.label()) { onChange(query.without(rule)) } }
        add()
        if (query.filters) TextAction("Clear all", { onChange(query.cleared()) })
        if (query.filters && onSaveAsLive != null) TextAction("Save as live list", onSaveAsLive, icon = OctoIcons.Filter)
    }
}

// The filters that are on, as one button on a page's control line: the
// filter's own words when it is the only one, else how many, opening them
// to take one off, Clear all and Save as live list.
@Composable
private fun FiltersOn(app: AppState, query: LibraryQuery, onChange: (LibraryQuery) -> Unit, onSaveAsLive: (() -> Unit)?) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val count = query.rules.size + if (query.text.isBlank()) 0 else 1
    val words = query.rules.singleOrNull()?.takeIf { query.text.isBlank() }?.label() ?: if (count == 1) "1 filter" else "$count filters"
    FilterPill(words, chevron = true, Modifier.onGloballyPositioned { anchor = it.windowRect() }, height = ControlHeight.L) {
        app.popups.showUnder(anchor) { close ->
            MenuTitle("Filters on")
            if (query.text.isNotBlank()) MenuRow("With \"${query.text}\"", { onChange(query.copy(text = "")); close() }, OctoIcons.Close)
            query.rules.forEach { rule -> MenuRow(rule.label(), { onChange(query.without(rule)); close() }, OctoIcons.Close) }
            MenuSeparator()
            MenuRow("Clear all", { onChange(query.cleared()); close() })
            if (onSaveAsLive != null) MenuRow("Save as live list", { close(); onSaveAsLive() }, OctoIcons.Filter)
        }
    }
}

// A filter that is on: its words in a filled pill, no border, and a cross
// that says a click takes it off. Live lists show their rules the same way.
// With `chevron` it opens the filters instead (see FiltersOn).
@Composable
internal fun FilterPill(text: String, chevron: Boolean = false, modifier: Modifier = Modifier, height: Dp = ControlHeight.S, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(if (hovered) OctoColors.AccentSelected.copy(alpha = 1f) else OctoColors.AccentSelected.copy(alpha = 0.72f))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = if (chevron) "Filters on: $text" else "Remove the filter $text" }
            .padding(start = Space.L, end = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        SelectedFill {
            Txt(text, DesktopType.meta, OctoColors.TextPrimary)
            val tint = if (hovered) OctoColors.TextPrimary else OctoColors.TextSecondary
            if (chevron) Glyph(OctoIcons.Chevron, Modifier.rotate(90f), size = IconSize.Inline, tint = tint) else Glyph(OctoIcons.Close, size = IconSize.Inline, tint = tint)
        }
    }
}

// Opens the quick filters under the control at `anchor`.
fun showAddFilter(app: AppState, anchor: IntRect, query: LibraryQuery, onChange: (LibraryQuery) -> Unit, songs: List<Song>) {
    app.popups.showUnder(anchor) { close -> AddFilterMenu(query, onChange, songs, close) }
}

private enum class FilterPage { Main, Rating, Genre, Year }

// The quick filters, the ones on ticked. Picking one on a field that
// already has a filter replaces it: "In the last month" takes the place of
// "In the last week".
@Composable
private fun ColumnScope.AddFilterMenu(query: LibraryQuery, onChange: (LibraryQuery) -> Unit, songs: List<Song>, close: () -> Unit) {
    var page by remember { mutableStateOf(FilterPage.Main) }
    // The genres and decades on offer, from the songs as the server gives them.
    val genres = remember(songs) { genresIn(songs, SubsonicSongs) }
    val decades = remember(songs) { decadesIn(songs, SubsonicSongs) }
    // Picking the one already on takes it off.
    fun pick(rule: QueryRule) {
        onChange(if (rule in query.rules) query.without(rule) else query.setting(rule))
        close()
    }
    fun on(rule: QueryRule) = rule in query.rules
    when (page) {
        FilterPage.Main -> {
            MenuTitle("Added")
            MenuRow("In the last week", { pick(FilterPresets.AddedThisWeek) }, checked = on(FilterPresets.AddedThisWeek))
            MenuRow("In the last month", { pick(FilterPresets.AddedThisMonth) }, checked = on(FilterPresets.AddedThisMonth))
            MenuRow("In the last year", { pick(FilterPresets.AddedThisYear) }, checked = on(FilterPresets.AddedThisYear))
            MenuSeparator()
            MenuTitle("Played")
            MenuRow("Never", { pick(FilterPresets.NeverPlayed) }, checked = on(FilterPresets.NeverPlayed))
            MenuRow("Not in the last 6 months", { pick(FilterPresets.NotPlayedLately) }, checked = on(FilterPresets.NotPlayedLately))
            MenuSeparator()
            MenuRow("Favorites", { pick(FilterPresets.Favourites) }, checked = on(FilterPresets.Favourites))
            MenuRow("Lossless", { pick(FilterPresets.Lossless) }, checked = on(FilterPresets.Lossless))
            MenuRow("Rating", { page = FilterPage.Rating }, more = true)
            MenuRow("Genre", { page = FilterPage.Genre }, more = true, enabled = genres.isNotEmpty())
            MenuRow("Year", { page = FilterPage.Year }, more = true, enabled = decades.isNotEmpty())
        }
        FilterPage.Rating -> {
            MenuBack("Rating") { page = FilterPage.Main }
            for (stars in 5 downTo 1) {
                val rule = FilterPresets.ratingAtLeast(stars)
                MenuRow(FilterPresets.ratingWords(stars), { pick(rule) }, checked = on(rule))
            }
        }
        FilterPage.Genre -> {
            MenuBack("Genre") { page = FilterPage.Main }
            genres.forEach { name ->
                val rule = FilterPresets.genre(name)
                MenuRow(name, { pick(rule) }, checked = on(rule))
            }
        }
        FilterPage.Year -> {
            MenuBack("Year") { page = FilterPage.Main }
            decades.asReversed().forEach { start ->
                val rule = FilterPresets.decade(start)
                MenuRow("The ${start}s", { pick(rule) }, checked = on(rule))
            }
        }
    }
}

// What a filtered list says when nothing is left, with the way back.
@Composable
fun NoMatches(onClear: () -> Unit) {
    Column(Modifier.padding(vertical = Space.Section), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Txt("No songs match these filters", OctoType.headline)
        Txt("Take a filter off, or clear them all.", OctoType.bodySmall, OctoColors.TextMuted)
        TextAction("Clear filters", onClear, Modifier.padding(top = Space.Xs))
    }
}
