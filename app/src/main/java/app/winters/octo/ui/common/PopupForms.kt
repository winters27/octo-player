package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(confirm, onClick = onConfirm)
            GlazeButton(cancel, onClick = onCancel)
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
