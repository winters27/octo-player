package app.winters.octo.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.design.raisedPanel
import app.winters.octo.player.capMiddle
import app.winters.octo.player.rememberCapHeightPx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

// The parts a form page is built from, in the hierarchy every app's sign-in
// page shares: a section head, group titles over raised cards of rows, each
// field a titled row with its entry under its name, notes and errors under
// their card, and the quiet Optional tag. CalloutEffect brings a group (or a
// row) into view once a failure calls it out.

private val CardShape = RoundedCornerShape(20.dp)

// The room round a row's content, and how far a note sits under its card.
private val RowSides = 16.dp
private val RowEnds = 12.dp
private val NoteGap = 8.dp

// The chevron at a choice row's end, and the gap between a row's parts.
private val ChoiceChevron = 20.dp
private val ChoiceGap = 12.dp

// Descriptions and values keep to two lines at the phone's normal text
// size; larger text wraps freely.
@Composable
private fun lineCap(): Int = if (LocalDensity.current.fontScale <= 1f) 2 else Int.MAX_VALUE

// Where an icon of Octo's set (or the octopus) is read from: the app's own
// vector files. Tests, which run without the app's resources, provide
// stand-ins.
val LocalIconVectors = staticCompositionLocalOf<@Composable (Int) -> ImageVector> { { ImageVector.vectorResource(it) } }

// An icon of Octo's set, as a painter.
@Composable
fun octoIcon(@DrawableRes id: Int): Painter = rememberVectorPainter(LocalIconVectors.current(id))

// A section's head that opens and closes it, such as Advanced: its title,
// set as a group's title and one size whether open or closed, and a line
// naming what is inside, with only the chevron turning. The chevron ends
// where the rows' chevrons end, its middle on the middle of the title's
// capitals at any font scale. Screen readers hear a heading that expands
// and collapses.
@Composable
fun FormSectionHead(title: String, line: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "section chevron")
    val cap = rememberCapHeightPx(OctoType.formGroup)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                if (open) {
                    collapse {
                        onToggle()
                        true
                    }
                } else {
                    expand {
                        onToggle()
                        true
                    }
                }
            }
            .padding(start = 4.dp, end = RowSides, top = 10.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {
            Text(title, style = OctoType.formGroup, color = OctoColors.TextPrimary)
            Text(line, style = OctoType.formDescription, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(octoIcon(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(20.dp).rotate(turn))
    }
}

// A group's title, 9 above its card. Screen readers hear a heading.
@Composable
fun FormGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OctoType.formGroup,
        color = OctoColors.TextPrimary,
        modifier = modifier.padding(start = 4.dp, bottom = 9.dp).semantics { heading() },
    )
}

// The raised card a group's rows sit on. `outlined` rings it in the red of
// the error note under it, while the group calls out a failure.
@Composable
fun FormGroupCard(modifier: Modifier = Modifier, outlined: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val ring = OctoColors.ErrorWords
    Column(
        modifier
            .fillMaxWidth()
            .raisedPanel(CardShape)
            .then(if (outlined) Modifier.border(1.5.dp, ring, CardShape) else Modifier),
        content = content,
    )
}

// The line between two rows of a card, starting under their words.
@Composable
fun FormDivider() {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    Box(
        Modifier
            .padding(start = RowSides)
            .fillMaxWidth()
            .height(onePixel)
            .background(OctoColors.TextPrimary.copy(alpha = 0.10f)),
    )
}

// A row that names what follows it in the card, with a line under its name.
@Composable
fun FormRowTitle(title: String, description: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = RowSides, end = RowSides, top = RowEnds)) {
        Text(title, style = OctoType.formName, color = OctoColors.TextPrimary)
        description?.let {
            Text(
                it,
                style = OctoType.formDescription,
                color = OctoColors.TextSecondary,
                maxLines = lineCap(),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// A field: its name (with the Optional tag when it may stay empty), the line
// under the name, then its entry. A screen reader hears the entry by the
// name, the tag included, so the name line itself is left out for it.
// `leading` sits in the entry before the words (the address's scheme);
// `onSubmit` is what the keyboard's Done does (null keeps the platform's own:
// Next moves on, Done closes the keyboard); `focus` lets the page move focus
// into the entry; `readOnly` shows the value with no caret.
@Composable
fun FormFieldRow(
    title: String,
    optional: Boolean = false,
    description: String? = null,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    contentType: ContentType? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    autocorrect: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    focus: FocusRequester? = null,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = RowSides, vertical = RowEnds)) {
        Row(
            Modifier.clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = OctoType.formName, color = OctoColors.TextPrimary)
            if (optional) OptionalTag()
        }
        description?.let {
            Text(
                it,
                style = OctoType.formDescription,
                color = OctoColors.TextSecondary,
                maxLines = lineCap(),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        FormEntry(
            value = value,
            onChange = onChange,
            name = if (optional) SignInWords.optionalName(title) else title,
            modifier = Modifier.padding(top = 8.dp),
            placeholder = placeholder,
            secret = secret,
            contentType = contentType,
            keyboardType = keyboardType,
            imeAction = imeAction,
            capitalization = capitalization,
            autocorrect = autocorrect,
            leading = leading,
            onSubmit = onSubmit,
            focus = focus,
            readOnly = readOnly,
        )
    }
}

// The well every entry on a form types into: the page's own color edged
// with the theme's hairline. `name` is what a screen reader calls it. A
// secret shows dots until Show. Each time it takes focus, the callout host
// hears of it, so a callout still waiting leaves the page where the person
// is.
@Composable
fun FormEntry(
    value: String,
    onChange: (String) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    contentType: ContentType? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    autocorrect: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    focus: FocusRequester? = null,
    readOnly: Boolean = false,
) {
    val host = LocalCalloutHost.current
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val toggle: (@Composable () -> Unit)? = if (secret) {
        { RevealToggle(revealed) { revealed = !revealed } }
    } else {
        null
    }
    GlassInput(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        modifier = modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused) host?.focus?.taken = true
                focused = it.isFocused
            }
            .semantics { contentDescription = name },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = autocorrect,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = if (onSubmit != null) KeyboardActions(onDone = { onSubmit() }) else KeyboardActions.Default,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        contentType = contentType,
        trailing = toggle,
        leading = leading,
        well = true,
        readOnly = readOnly,
    )
}

// Show or Hide at a secret entry's end.
@Composable
private fun RevealToggle(revealed: Boolean, onClick: () -> Unit) {
    Text(
        if (revealed) "Hide" else "Show",
        style = OctoType.label,
        color = OctoColors.AccentWords,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

// A setting with a few answers: its name, the answer chosen at the row's end
// (at most two lines), the line under the name, and a chevron on the middle
// of the name's capitals, at any font scale and however many lines follow
// it. A tap asks
// through the choice sheet, beside the row. Where the name and the widest
// answer do not fit on one line, the answer goes under the name.
@Composable
fun FormChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    answers: List<String> = emptyList(),
) {
    val sheet = LocalChoiceSheet.current
    val cap = rememberCapHeightPx(OctoType.formName)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val room = with(density) { (maxWidth - RowSides * 2 - ChoiceChevron - ChoiceGap * 2).roundToPx() }
        val stacked = remember(title, value, answers, room, density) {
            val name = measurer.measure(title, OctoType.formName, maxLines = 1, softWrap = false).size.width
            val widest = (answers + value).maxOf { measurer.measure(it, OctoType.formValue, maxLines = 1, softWrap = false).size.width }
            name + widest > room
        }
        Row(
            Modifier
                .fillMaxWidth()
                .choiceAnchor(sheet)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = RowSides, vertical = RowEnds),
            horizontalArrangement = Arrangement.spacedBy(ChoiceGap),
        ) {
            Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {
                if (stacked) {
                    Text(title, style = OctoType.formName, color = OctoColors.TextPrimary)
                    Text(
                        value,
                        style = OctoType.formValue,
                        color = OctoColors.TextSecondary,
                        maxLines = lineCap(),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ChoiceGap)) {
                        Text(title, style = OctoType.formName, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            value,
                            style = OctoType.formValue,
                            color = OctoColors.TextSecondary,
                            textAlign = TextAlign.End,
                            maxLines = lineCap(),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                description?.let {
                    Text(
                        it,
                        style = OctoType.formDescription,
                        color = OctoColors.TextSecondary,
                        maxLines = lineCap(),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(octoIcon(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(ChoiceChevron))
        }
    }
}

// One custom header: its name over its value, and the button that removes
// it at the row's end, named for the header it removes. `nameFocus` lets the
// page put focus in a row just added; `valueImeAction` is the value's
// keyboard action (Next to the next row's name, else Done).
@Composable
fun HeaderRow(
    name: String,
    value: String,
    saved: Boolean,
    onName: (String) -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    nameFocus: FocusRequester? = null,
    valueImeAction: ImeAction,
) {
    Row(
        modifier.fillMaxWidth().padding(start = RowSides, end = 4.dp, top = RowEnds),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormEntry(
                value = name,
                onChange = onName,
                name = SignInWords.HEADER_NAME,
                placeholder = SignInWords.HEADER_NAME,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
                focus = nameFocus,
            )
            FormEntry(
                value = value,
                onChange = onValue,
                name = SignInWords.HEADER_VALUE,
                placeholder = if (saved) SignInWords.UNCHANGED else SignInWords.HEADER_VALUE,
                secret = true,
                keyboardType = KeyboardType.Password,
                imeAction = valueImeAction,
            )
        }
        RemoveButton(SignInWords.removeHeader(name), onRemove)
    }
}

// The client certificate: its name and the line under it, the button that
// chooses or changes it, and, once one is chosen, the one that removes it.
@Composable
fun FormCertificateRow(
    title: String,
    line: String,
    action: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
    removeName: String,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = RowSides, end = 4.dp, top = RowEnds, bottom = RowEnds),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.formName, color = OctoColors.TextPrimary)
            Text(
                line,
                style = OctoType.formDescription,
                color = OctoColors.TextSecondary,
                maxLines = lineCap(),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        GlazeButton(action, onAction, enabled = actionEnabled, size = ButtonSize.Small)
        if (onRemove != null) RemoveButton(removeName, onRemove)
    }
}

// A round button that removes what its row holds, named for what it removes.
@Composable
private fun RemoveButton(name: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Icon(octoIcon(OctoIcons.Close), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

// One status row: what is happening, in the row's words, with a spinner at
// its end while it goes on.
@Composable
fun FormStatusRow(text: String, busy: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RowSides, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = OctoType.formName, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
        if (busy) Spinner(size = 20.dp)
    }
}

// A note under its card, on the content edge: a glyph (the accent's info by
// default; the locks for how the connection travels) on the middle of the
// first line's capitals at any font scale, and words a step quieter than
// the rows'.
@Composable
fun FormNote(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = OctoIcons.Info,
    iconTint: Color = OctoColors.Accent,
) {
    val cap = rememberCapHeightPx(OctoType.formNote)
    Row(
        modifier.fillMaxWidth().padding(start = RowSides, end = RowSides, top = NoteGap),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(octoIcon(icon), contentDescription = null, tint = iconTint, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(16.dp))
        Text(text, style = OctoType.formNote, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() })
    }
}

// A failure, where a note would sit and above the notes: its glyph (the
// error glyph by default; the open lock for how the connection travels), on
// the middle of the first line's capitals at any font scale, and words in
// the error red, said by a screen reader as it appears. With `onClick` it is
// a button (the pointer under Sign in).
@Composable
fun FormError(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = OctoIcons.Error,
    onClick: (() -> Unit)? = null,
) {
    val red = OctoColors.ErrorWords
    val cap = rememberCapHeightPx(OctoType.formNote)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = RowSides, end = RowSides, top = NoteGap)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(octoIcon(icon), contentDescription = null, tint = red, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(16.dp))
        Text(text, style = OctoType.formNote, color = red, modifier = Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() })
    }
}

// The quiet tag after the name of a field that may stay empty. A screen
// reader hears it as part of the field's name instead.
@Composable
fun OptionalTag(modifier: Modifier = Modifier) {
    Text(
        SignInWords.OPTIONAL_TAG,
        style = OctoType.formTag,
        color = OctoColors.TagWords,
        modifier = modifier
            .background(OctoColors.TagFill, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
    )
}

// Whether a field took focus since the callout started waiting: a field the
// person moves into ends the wait where they are.
class CalloutFocus {
    var taken by mutableStateOf(false)
}

// What a callout scrolls and reads, from the page that hosts it: the scroll,
// how far below the scroll's top a group's title should land, the
// coordinates of the scroll's own child (taken before its padding), whether
// the part that holds the group has settled, how far up the on-screen
// keyboard reaches, the focus record its entries keep, and how tall the
// scroll's soft bottom edge is, in pixels.
data class CalloutHost(
    val scroll: ScrollState,
    val topInset: Int,
    val content: LayoutCoordinates,
    val settled: () -> Boolean,
    val imeBottom: () -> Int,
    val focus: CalloutFocus,
    val fade: Int = 0,
)

// The host of the page drawn, null until its content is placed.
val LocalCalloutHost = compositionLocalOf<CalloutHost?> { null }

// Where a callout's wait ended: a field took focus, or the place to scroll to.
private sealed interface CalloutWait {
    data object Taken : CalloutWait
    class At(val target: Int) : CalloutWait
}

// Brings `group` into view once `pending` turns true: the on-screen keyboard
// closes first (in touch mode by clearing focus; otherwise by closing it
// alone, since clearing focus there hands it to the first field and scrolls
// to it), then, once the keyboard is down and the host has settled with the
// group placed, the group's title goes `topInset` below the scroll's top (a
// group taller than that room puts its last note's bottom 8 above where the
// scroll's soft bottom edge starts instead). A field taking focus during
// either wait ends it
// with no scroll. `onShown` runs when the scroll ends, or when a drag cuts it
// short, but never when the effect itself leaves (the flag then stays set and
// the callout runs again when the host is back).
@Composable
fun CalloutEffect(pending: Boolean, host: CalloutHost?, group: () -> LayoutCoordinates?, onShown: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val still = LocalReduceMotion.current
    val gap = with(LocalDensity.current) { NoteGap.roundToPx() }
    val currentHost by rememberUpdatedState(host)
    val currentGroup by rememberUpdatedState(group)
    val currentShown by rememberUpdatedState(onShown)
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        currentHost?.focus?.taken = false
        if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else keyboard?.hide()
        try {
            val down = snapshotFlow {
                val now = currentHost
                when {
                    now == null -> null
                    now.focus.taken -> false
                    now.imeBottom() == 0 -> true
                    else -> null
                }
            }.filterNotNull().first()
            if (!down) {
                currentShown()
                return@LaunchedEffect
            }
            val wait = snapshotFlow<CalloutWait?> {
                val now = currentHost ?: return@snapshotFlow null
                if (now.focus.taken) return@snapshotFlow CalloutWait.Taken
                val placed = currentGroup() ?: return@snapshotFlow null
                if (!now.settled() || !placed.isAttached || !now.content.isAttached) return@snapshotFlow null
                CalloutWait.At(calloutTarget(now, placed, gap))
            }.filterNotNull().first()
            val now = currentHost
            if (wait is CalloutWait.At && now != null) {
                if (still) now.scroll.scrollTo(wait.target) else now.scroll.glideTo(wait.target)
            }
            currentShown()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) currentShown()
            throw e
        }
    }
}

// Where to scroll to bring `group` into view on `host`.
private fun calloutTarget(host: CalloutHost, group: LayoutCoordinates, gap: Int): Int {
    val top = host.content.localPositionOf(group, Offset.Zero).y.roundToInt()
    val height = group.size.height
    val view = host.scroll.viewportSize
    return if (height > view - host.topInset) top + height + gap + host.fade - view else top - host.topInset
}

// Scrolls to `target` as a place, not a distance: each frame goes to where
// the animation is now, so content that shrinks under the scroll mid-way (a
// part closing, which clamps the scroll) does not carry it past the target.
// A drag still cancels it, as it cancels any scroll.
internal suspend fun ScrollState.glideTo(target: Int) {
    scroll {
        animate(value.toFloat(), target.toFloat(), animationSpec = spring()) { now, _ -> scrollBy(now - value) }
    }
}
