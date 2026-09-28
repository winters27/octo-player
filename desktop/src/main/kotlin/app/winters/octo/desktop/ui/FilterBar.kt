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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
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
// bring the keyboard to the field.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBar(app: AppState, query: LibraryQuery, onChange: (LibraryQuery) -> Unit, songs: List<Song>, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    DisposableEffect(focus) {
        app.pageFilterFocus = focus
        onDispose { if (app.pageFilterFocus === focus) app.pageFilterFocus = null }
    }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    FlowRow(
        modifier.fillMaxWidth().padding(bottom = Space.L),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.M),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        GlassField(
            query.text,
            { onChange(query.copy(text = it)) },
            Modifier.width(FrameSize.FilterWidth).height(ControlHeight.S),
            placeholder = "Filter these songs",
            icon = OctoIcons.Filter,
            focusRequester = focus,
            onEscape = { onChange(query.copy(text = "")) },
            trailing = if (query.text.isEmpty()) null else ({
                IconAction(OctoIcons.Close, "Clear the words", { onChange(query.copy(text = "")) }, size = ControlHeight.Xs, iconSize = IconSize.Inline)
            }),
        )
        query.rules.forEach { rule -> FilterPill(rule.label()) { onChange(query.without(rule)) } }
        TextAction(
            "Add filter",
            { showAddFilter(app, anchor, query, onChange, songs) },
            Modifier.onGloballyPositioned { anchor = it.windowRect() },
            icon = OctoIcons.Add,
        )
        if (query.filters) TextAction("Clear all", { onChange(query.cleared()) })
    }
}

// A filter that is on: its words in a filled pill, no border, and a cross
// that says a click takes it off.
@Composable
private fun FilterPill(text: String, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(ControlHeight.S)
            .clip(CircleShape)
            .background(if (hovered) OctoColors.AccentSelected.copy(alpha = 1f) else OctoColors.AccentSelected.copy(alpha = 0.72f))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove the filter $text" }
            .padding(start = Space.L, end = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        Txt(text, DesktopType.meta, OctoColors.TextPrimary)
        Glyph(OctoIcons.Close, size = IconSize.Inline, tint = if (hovered) OctoColors.TextPrimary else OctoColors.TextSecondary)
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
            MenuRow("Favourites", { pick(FilterPresets.Favourites) }, checked = on(FilterPresets.Favourites))
            MenuRow("Lossless", { pick(FilterPresets.Lossless) }, checked = on(FilterPresets.Lossless))
            MenuRow("Rating", { page = FilterPage.Rating }, more = true)
            MenuRow("Genre", { page = FilterPage.Genre }, more = true, enabled = genres.isNotEmpty())
            MenuRow("Year", { page = FilterPage.Year }, more = true, enabled = decades.isNotEmpty())
        }
        FilterPage.Rating -> {
            MenuRow("Back", { page = FilterPage.Main }, OctoIcons.Back)
            MenuSeparator()
            for (stars in 5 downTo 1) {
                val rule = FilterPresets.ratingAtLeast(stars)
                MenuRow(FilterPresets.ratingWords(stars), { pick(rule) }, checked = on(rule))
            }
        }
        FilterPage.Genre -> {
            MenuRow("Back", { page = FilterPage.Main }, OctoIcons.Back)
            MenuSeparator()
            genres.forEach { name ->
                val rule = FilterPresets.genre(name)
                MenuRow(name, { pick(rule) }, checked = on(rule))
            }
        }
        FilterPage.Year -> {
            MenuRow("Back", { page = FilterPage.Main }, OctoIcons.Back)
            MenuSeparator()
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
