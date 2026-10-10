package app.winters.octo.design

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

// Whether someone is typing in a text field, so the window's shortcuts
// leave Space and the arrows to the field.
class TypingState {
    private var fields by mutableIntStateOf(0)
    val active: Boolean get() = fields > 0

    internal fun focused(on: Boolean) {
        fields = (fields + if (on) 1 else -1).coerceAtLeast(0)
    }
}

val LocalTyping = staticCompositionLocalOf { TypingState() }

private val FieldShape = RoundedCornerShape(12.dp)

// A text field set into the glass: a dark inset with a white caret, a
// muted hint while empty, and an optional icon at the start. `leading` and
// `trailing` hold small controls inside the field, before and after the
// text (a choice of scheme, a button that shows a password). Enter calls
// `onSubmit`; Escape calls `onEscape`. With `lines` over 1 it is a box of
// that many lines that scrolls its own text, and Enter starts a new line.
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    lines: Int = 1,
) {
    val single = lines <= 1
    val typing = LocalTyping.current
    var focused by remember { mutableStateOf(false) }
    // A field that leaves while focused must not leave the window thinking
    // someone is still typing.
    DisposableEffect(Unit) { onDispose { if (focused) typing.focused(false) } }
    // While typing, a thin accent ring fades in round the field.
    val ring by animateFloatAsState(if (focused) 1f else 0f, octoTween(motionScale(), OctoDuration.Card), label = "field ring")
    // The whole field is the target: a click on its edge puts the caret in too.
    val inner = focusRequester ?: remember { FocusRequester() }
    GlazeInset(
        fill = Color.Black.copy(alpha = 0.30f),
        shape = FieldShape,
        modifier = modifier
            .height(if (single) 40.dp else (lines * 20 + 20).dp)
            .focusRing(FieldShape, { ring }, width = 1.5.dp, color = OctoColors.Accent.copy(alpha = 0.60f))
            .pointerInput(inner) { detectTapGestures { runCatching { inner.requestFocus() } } },
    ) {
        Row(
            // A control inside sits closer to the edge, as far in as it is from the top.
            Modifier
                .fillMaxWidth()
                .padding(start = if (leading != null) 6.dp else 12.dp, end = if (trailing != null) 4.dp else 12.dp)
                .then(if (single) Modifier else Modifier.padding(vertical = 10.dp)),
            verticalAlignment = if (single) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Glyph(icon, size = 18.dp, tint = OctoColors.TextMuted)
            leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Txt(placeholder, OctoType.bodySmall, OctoColors.TextMuted)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = single,
                    textStyle = OctoType.bodySmall.copy(color = OctoColors.TextPrimary),
                    cursorBrush = SolidColor(Color.White),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                        imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (single) Modifier else Modifier.fillMaxHeight())
                        // Named by its hint, which a screen reader cannot see.
                        .then(if (placeholder.isNotBlank()) Modifier.semantics { contentDescription = placeholder } else Modifier)
                        .focusRequester(inner)
                        .onFocusChanged { state ->
                            if (state.isFocused != focused) {
                                focused = state.isFocused
                                typing.focused(state.isFocused)
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.Enter && onSubmit != null && single -> {
                                    onSubmit()
                                    true
                                }
                                event.key == Key.Escape && onEscape != null -> {
                                    onEscape()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            trailing?.invoke()
        }
    }
}
