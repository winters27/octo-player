package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// The last value that was not null, so a card can still show it while it
// fades away after it was answered.
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val held = remember { LastHeld<T>() }
    if (value != null) held.value = value
    return held.value
}

private class LastHeld<T : Any> {
    var value: T? = null
}

// A question in a glass pop-up: what is asked, a line about it, anything
// more it needs to show, then the answers as buttons, the first one lit.
@Composable
fun PopupQuestion(
    title: String,
    detail: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancel: String = "Cancel",
    width: Dp = MenuWidth,
    more: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.width(width).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Text(
            title,
            style = OctoType.body,
            color = OctoColors.TextPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (detail != null) {
            Text(detail, style = OctoType.bodySmall, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
        }
        more()
        AnswerRow(
            main = { AccentButton(confirm, onClick = onConfirm) },
            other = { GlazeButton(cancel, onClick = onCancel) },
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

// The two answers under a question: the lit one, then the quieter one `gap`
// after it. Side by side while both fit at their own widths, the lit one
// widened to the quieter one's width where the room allows, so the main
// answer never reads as the lesser one. Where they do not fit side by side
// (long words, or large text on a narrow phone) they stack, the lit one on
// top, each the room's full width, so neither is ever cut.
@Composable
fun AnswerRow(main: @Composable () -> Unit, other: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Dp = 10.dp) {
    Layout(contents = listOf(main, other), modifier = modifier) { (mains, others), constraints ->
        val lit = mains.single()
        val quiet = others.single()
        val space = gap.roundToPx()
        val litWidth = lit.maxIntrinsicWidth(Constraints.Infinity)
        val quietWidth = quiet.maxIntrinsicWidth(Constraints.Infinity)
        val room = constraints.maxWidth
        val tall = constraints.maxHeight
        if (!constraints.hasBoundedWidth || litWidth + space + quietWidth <= room) {
            val least = if (constraints.hasBoundedWidth) minOf(quietWidth, room - space - quietWidth) else quietWidth
            val wide = maxOf(litWidth, least)
            val first = lit.measure(Constraints(minWidth = wide, maxWidth = wide, maxHeight = tall))
            val second = quiet.measure(Constraints(minWidth = quietWidth, maxWidth = quietWidth, maxHeight = tall))
            val height = maxOf(first.height, second.height)
            layout((first.width + space + second.width).coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                first.placeRelative(0, (height - first.height) / 2)
                second.placeRelative(first.width + space, (height - second.height) / 2)
            }
        } else {
            val first = lit.measure(Constraints(minWidth = room, maxWidth = room, maxHeight = tall))
            val second = quiet.measure(Constraints(minWidth = room, maxWidth = room, maxHeight = tall))
            layout(room, (first.height + space + second.height).coerceIn(constraints.minHeight, constraints.maxHeight)) {
                first.placeRelative(0, 0)
                second.placeRelative(0, first.height + space)
            }
        }
    }
}

// A name to type in a glass pop-up, with its button beside the field. The
// keyboard opens as it appears. The button waits until `ready` says the name
// will do; `note` says why not, when there is something to say.
@Composable
fun PopupNameField(
    placeholder: String,
    action: String,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    initial: String = "",
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    ready: (String) -> Boolean = { it.isNotBlank() },
    note: (String) -> String? = { null },
) {
    var name by remember { mutableStateOf(initial) }
    val canDo = ready(name)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier) {
        Row {
            GlassInput(
                value = name,
                onValueChange = { name = it },
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canDo) onDone(name) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Spacer(Modifier.width(8.dp))
            AccentButton(action, onClick = { onDone(name) }, enabled = canDo)
        }
        note(name)?.let {
            Text(it, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
    }
}

// A page that asks for a name: its heading, or a way back when it was
// opened from another page, then the field.
@Composable
fun PopupNameForm(
    title: String,
    placeholder: String,
    action: String,
    onBack: (() -> Unit)?,
    onDone: (String) -> Unit,
    initial: String = "",
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    ready: (String) -> Boolean = { it.isNotBlank() },
    note: (String) -> String? = { null },
) {
    Column(Modifier.width(MenuWidth).padding(6.dp)) {
        if (onBack != null) GlassMenuBack(title, onBack) else GlassMenuHeading(title)
        PopupNameField(
            placeholder,
            action,
            onDone,
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            initial = initial,
            capitalization = capitalization,
            ready = ready,
            note = note,
        )
    }
}
