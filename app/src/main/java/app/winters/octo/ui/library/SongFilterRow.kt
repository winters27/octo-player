package app.winters.octo.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryRule
import app.winters.octo.query.label
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.choiceAnchor

// The Songs list's search: a field for words, then a row of chips, the
// same quick filters the desktop offers. With `focusOnOpen` the field takes
// the keyboard as soon as it is on screen, then says so with `onFocused`. A chip that is on sits in the
// darker pill; a tap turns it off. Rating, Genre and Year ask which first.
// Picking one on a field that already has a filter replaces it.
@Composable
internal fun SongFilterRow(
    query: LibraryQuery,
    choices: SongChoices,
    onChange: (LibraryQuery) -> Unit,
    // Keeps these filters as a live list.
    onSaveAsLive: (() -> Unit)? = null,
    focusOnOpen: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    LaunchedEffect(focusOnOpen) {
        if (focusOnOpen) {
            // One frame, so the field is laid out before it is asked.
            withFrameNanos { }
            field.requestFocus()
            keyboard?.show()
            onFocused()
        }
    }
    val sheet = LocalChoiceSheet.current
    fun toggle(rule: QueryRule) = onChange(if (rule in query.rules) query.without(rule) else query.setting(rule))
    // A chip that asks: on, it shows the filter and a tap takes it off.
    fun asking(field: QueryField, ask: () -> Unit): Pair<QueryRule?, () -> Unit> {
        val on = query.rules.firstOrNull { it.field == field }
        return on to { if (on != null) onChange(query.without(on)) else ask() }
    }
    val rating = asking(QueryField.Rating) {
        sheet.show(ChoiceRequest("Rating", (5 downTo 1).map { Choice(FilterPresets.ratingWords(it)) }, -1) { onChange(query.setting(FilterPresets.ratingAtLeast(5 - it))) })
    }
    val genre = asking(QueryField.Genre) {
        sheet.show(ChoiceRequest("Genre", choices.genres.map { Choice(it) }, -1) { onChange(query.setting(FilterPresets.genre(choices.genres[it]))) })
    }
    val year = asking(QueryField.Year) {
        val decades = choices.decades.asReversed()
        sheet.show(ChoiceRequest("Year", decades.map { Choice("The ${it}s") }, -1) { onChange(query.setting(FilterPresets.decade(decades[it]))) })
    }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp)) {
        GlassInput(
            value = query.text,
            onValueChange = { onChange(query.copy(text = it)) },
            placeholder = "Search songs",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.padding(horizontal = 20.dp).focusRequester(field),
            trailing = if (query.text.isEmpty()) null else ({ ClearWords { onChange(query.copy(text = "")) } }),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            if (query.filters) item(key = "clear") { QuietButton("Clear all") { onChange(query.cleared()) } }
            if (query.filters && onSaveAsLive != null) item(key = "save-live") { QuietButton("Save as live list", onSaveAsLive) }
            items(FilterPresets.simple, key = { it.toString() }) { rule -> FilterChip(rule.label(), rule in query.rules) { toggle(rule) } }
            item(key = "rating") { FilterChip(rating.first?.label() ?: "Rating", rating.first != null, Modifier.choiceAnchor(sheet), rating.second) }
            if (choices.genres.isNotEmpty() || genre.first != null) {
                item(key = "genre") { FilterChip(genre.first?.label() ?: "Genre", genre.first != null, Modifier.choiceAnchor(sheet), genre.second) }
            }
            if (choices.decades.isNotEmpty() || year.first != null) {
                item(key = "year") { FilterChip(year.first?.label() ?: "Year", year.first != null, Modifier.choiceAnchor(sheet), year.second) }
            }
        }
    }
}

// One filter as a filled chip, no border: the darker pill with white words
// while on, a faint fill with quieter words while off. Live lists show
// their rules the same way.
@Composable
internal fun FilterChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(if (on) OctoColors.AccentSelected else OctoColors.TextPrimary.copy(alpha = 0.07f))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OctoType.label, color = if (on) OctoColors.TextPrimary else OctoColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun ClearWords(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Clear the words" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(OctoIcons.Close), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

// What the list says when its filters leave nothing, with the way back.
@Composable
internal fun NoMatchesNote(onClear: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("No songs match these filters", style = OctoType.bodySmall, color = OctoColors.TextMuted)
        QuietButton("Clear filters", onClear)
    }
}
