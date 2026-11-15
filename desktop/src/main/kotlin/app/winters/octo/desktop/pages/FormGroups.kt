package app.winters.octo.desktop.pages

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.connection.SignInWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.Focus
import app.winters.octo.design.FocusRing
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalFocusVisibility
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.WholeTxt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.motionScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

// The sign-in form's parts, on the page and in its sheets: groups of rows on
// the Rows glaze, each with its title above and its notes under its card; a
// field row (its name, the Optional tag, a line under the name, and the
// entry on the line below); a choice row whose options each say what they
// are for; the header rows; the notes and the error note; Advanced's head;
// and the callout that brings a group into view.

// The kinds of words a form shows, each in one style.
internal object FormType {
    // A group's title, and Advanced's head.
    val group: TextStyle = DesktopType.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

    // A setting's name, at the start of its row.
    val name: TextStyle = DesktopType.body.copy(fontWeight = FontWeight.Medium)

    // A choice's value, at the end of its row.
    val value: TextStyle = DesktopType.body

    // The line under a setting's name.
    val description: TextStyle = DesktopType.meta

    // A note or an error under a group's card.
    val note: TextStyle = DesktopType.table

    // The Optional tag.
    val tag: TextStyle = DesktopType.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

// How the groups stand apart: a title 9 above its card, the notes 8 under
// it, and 26 from one group's last note to the next group's title.
internal val GroupTitleGap = 9.dp
internal val NoteGap = Space.M
internal val GroupGap = 26.dp

// How far under the top of the view a group's title lands after a callout:
// on the page, and in a sheet.
internal val PageTopInset = 16.dp
internal val PopupTopInset = 8.dp

// How far a tall group's last note ends above the view's soft bottom edge.
internal val CalloutEnd = 8.dp

// How tall the soft edges are where the page's scroll, and a sheet's, fade
// what runs on past them.
internal val PageFade = 24.dp
internal val SheetFade = 12.dp

// The least width of a sheet's or pop-up's main button, so a short word
// ("Add", "Save", "Use it") never makes it narrower than Cancel beside it, at
// any text size up to 130% (Cancel is 99 there).
internal val SheetMainButton = 112.dp

// The least of a choice row a value may take beside its name.
private const val VALUE_SHARE = 0.45f

// Descriptions and values keep to two lines at the usual text size, and
// wrap freely once the text is set larger.
@Composable
internal fun twoLines(): Int = if (LocalDensity.current.fontScale <= 1f) 2 else Int.MAX_VALUE

// How far the middle of a style's capitals sits over its baseline, in
// pixels at the text size the person set. A glyph or control aligned by its
// own middle plus this, beside words aligned by their first baseline, sits
// on the middle of their capitals at every text size, as a song title's
// mark does.
@Composable
internal fun capsMiddle(style: TextStyle): Int = with(LocalDensity.current) { (style.fontSize.toPx() * CAP_SHARE / 2).roundToInt() }

// How tall a capital is, as a share of the type's size, in the desktop's
// faces.
private const val CAP_SHARE = 0.7f

// Advanced comes and goes with a fade only.
@Composable
internal fun advancedFade(): FiniteAnimationSpec<Float> = tween(motionScale().ms(OctoDuration.Neutral))

// A form sheet's title: its loudest words, in the main words and always
// whole, on its cards' edge. It takes no more of the sheet's height than a
// menu's title did, so the sheet's scroll keeps its room.
@Composable
internal fun FormSheetTitle(text: String) {
    WholeTxt(text, DesktopType.pageTitle, OctoColors.TextPrimary, Modifier.padding(start = Space.Xl, end = Space.Xl, top = Space.Xxs))
}

// A group: its title (a heading) when it has one, its rows on the Rows glaze,
// and its notes under the card, errors first. `outlined` rings the card in
// the error red while it calls a failure out. `place` hears where the group
// is, from its title through its last note, for the callout.
@Composable
internal fun FormGroup(
    title: String?,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    place: ((LayoutCoordinates) -> Unit)? = null,
    notes: @Composable ColumnScope.() -> Unit = {},
    rows: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().then(if (place != null) Modifier.onGloballyPositioned(place) else Modifier)) {
        if (title != null) {
            Txt(
                title,
                FormType.group,
                OctoColors.TextPrimary,
                Modifier.padding(start = RowInset, end = RowInset, bottom = GroupTitleGap).semantics { heading() },
                maxLines = 2,
            )
        }
        Box(Modifier.fillMaxWidth().then(if (outlined) Modifier.calloutOutline() else Modifier)) { Rows(content = rows) }
        Column(Modifier.fillMaxWidth().roomAboveWhenShown(NoteGap), verticalArrangement = Arrangement.spacedBy(Space.S)) { notes() }
    }
}

// Room above something only when it draws anything.
private fun Modifier.roomAboveWhenShown(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (placeable.height == 0) return@layout layout(placeable.width, 0) {}
    val top = gap.roundToPx()
    layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
}

// The ring round a group's card while it calls a failure out, in the red of
// the error note under it.
@Composable
private fun Modifier.calloutOutline(): Modifier {
    val color = OctoColors.ErrorWords
    return drawWithContent {
        drawContent()
        val width = Focus.Ring.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(Corner.Panel.toPx()),
            style = Stroke(width),
        )
    }
}

// A row with a name, the line under it, and something at its end.
@Composable
internal fun FormRow(title: String, description: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, FormType.name, OctoColors.TextPrimary, maxLines = 2)
            description?.let { Txt(it, FormType.description, OctoColors.TextSecondary, maxLines = twoLines()) }
        }
        trailing()
    }
}

// A switch row: its name and the line saying what it does as it is set. The
// whole row flips it, and is the one stop a screen reader hears. The switch
// sits on the name's line, its middle on the middle of the name's capitals
// at every text size, where a choice row's chevron sits on its name.
@Composable
internal fun FormSwitchRow(title: String, description: String, on: Boolean, change: (Boolean) -> Unit) {
    val caps = capsMiddle(FormType.name)
    Row(
        Modifier
            .fillMaxWidth()
            .hoverLift(Corner.RowShape)
            .toggleable(value = on, interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Switch, onValueChange = change)
            .padding(horizontal = RowInset, vertical = Space.L),
        horizontalArrangement = Arrangement.spacedBy(Space.Xl),
    ) {
        Column(Modifier.weight(1f).alignBy(FirstBaseline)) {
            Txt(title, FormType.name, OctoColors.TextPrimary, maxLines = 2)
            Txt(description, FormType.description, OctoColors.TextSecondary, maxLines = twoLines())
        }
        OctoSwitch(on, change, Modifier.alignBy { it.measuredHeight / 2 + caps }.focusProperties { canFocus = false }.semantics { hideFromAccessibility() })
    }
}

// A titled field: its name with the Optional tag when it may stay empty, a
// line under the name, then the entry on the line below. `leading` holds the
// scheme toggle on the address row; `secret` gives the entry dots and Show.
// Enter runs `onSubmit`. `focus` lets the form put the keyboard in it.
// `readOnly` shows the value in the entry's usual look with no caret.
@Composable
internal fun FormFieldRow(
    title: String,
    optional: Boolean = false,
    description: String? = null,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onSubmit: () -> Unit,
    focus: FocusRequester? = null,
    readOnly: Boolean = false,
) {
    val name = if (optional) SignInWords.optionalName(title) else title
    Column(
        Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Txt(title, FormType.name, OctoColors.TextPrimary, Modifier.weight(1f, fill = false), maxLines = 2)
            if (optional) OptionalTag()
        }
        description?.let { Txt(it, FormType.description, OctoColors.TextSecondary, maxLines = twoLines()) }
        if (secret) {
            SecretEntry(value, onChange, placeholder, name, onSubmit, focus, readOnly)
        } else {
            FieldEntry(value, onChange, placeholder, name, onSubmit, focus, leading = leading, readOnly = readOnly)
        }
    }
}

// The quiet tag after the name of a field that may stay empty: the accent's
// words on its tonal fill. A screen reader hears it in the field's own name.
@Composable
internal fun OptionalTag() {
    Txt(
        SignInWords.OPTIONAL_TAG,
        FormType.tag,
        OctoColors.TagWords,
        Modifier.background(OctoColors.TagFill, Corner.RowShape).padding(horizontal = Space.S, vertical = Space.Xxs),
        control = true,
    )
}

// The entry every field row and header row uses: a well in the page's color,
// edged with the theme's hairline, named for its field. In a sheet on a light
// theme, whose cards are trays a step under the sheet, the well takes the
// theme's card color, the sheet's own, a step over the tray. Taking the
// keyboard tells a waiting callout to leave the person where they are.
@Composable
internal fun FieldEntry(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    name: String,
    onSubmit: () -> Unit,
    focus: FocusRequester? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    password: Boolean = false,
    readOnly: Boolean = false,
) {
    val host = LocalCalloutHost.current
    var held by remember { mutableStateOf(false) }
    GlassField(
        value,
        onChange,
        modifier.fillMaxWidth().onFocusChanged { state ->
            if (state.hasFocus && !held) host?.focus?.taken = true
            held = state.hasFocus
        },
        placeholder = placeholder,
        password = password,
        focusRequester = focus,
        onSubmit = onSubmit,
        enabled = !readOnly,
        leading = leading,
        trailing = trailing,
        fill = if (LocalInSheet.current && LocalOctoSurfaces.current.light) LocalOctoSurfaces.current.card else OctoColors.Background,
        edge = OctoColors.Edge,
        name = name,
    )
}

// A password, key or secret header value: dots until Show. Tab goes from
// field to field, past Show.
@Composable
internal fun SecretEntry(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    name: String,
    onSubmit: () -> Unit,
    focus: FocusRequester? = null,
    readOnly: Boolean = false,
) {
    var reveal by remember { mutableStateOf(false) }
    FieldEntry(
        value,
        onChange,
        placeholder,
        name,
        onSubmit,
        focus,
        password = !reveal,
        readOnly = readOnly,
        trailing = {
            IconAction(
                if (reveal) OctoIcons.Conceal else OctoIcons.Reveal,
                if (reveal) "Hide" else "Show",
                { reveal = !reveal },
                Modifier.focusProperties { canFocus = false },
                size = 32.dp,
                iconSize = IconSize.Toolbar,
                tint = OctoColors.TextSecondary,
            )
        },
    )
}

// One custom header: its name over its value, and Remove at the right edge,
// named for the header. Enter in either runs `submit`. Tab goes from the
// value to Remove, past Show.
@Composable
internal fun HeaderRow(
    header: HeaderDraft,
    onName: (String) -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
    submit: () -> Unit,
    nameFocus: FocusRequester? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = RowInset, end = Space.S, top = Space.M, bottom = Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            FieldEntry(header.name, onName, SignInWords.HEADER_NAME, SignInWords.HEADER_NAME, submit, nameFocus)
            SecretEntry(header.value, onValue, if (header.saved) SignInWords.UNCHANGED else SignInWords.HEADER_VALUE, SignInWords.HEADER_VALUE, submit)
        }
        IconAction(OctoIcons.Close, SignInWords.removeHeader(header.name), onRemove, size = 32.dp, iconSize = IconSize.Table, tint = OctoColors.TextSecondary)
    }
}

// What a note under a group says it is.
internal enum class NoteKind { Info, Lock, OpenLock, Error }

// A note under a group's card, on its content edge: a glyph and words in the
// secondary words, or, for an error, both in the error words. The glyph sits
// on the middle of the first line's capitals at every text size. With
// `onClick` it is a button (the pointer to the sign-in page group).
@Composable
internal fun FormNote(text: String, kind: NoteKind = NoteKind.Info, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val ink = if (kind == NoteKind.Error) OctoColors.ErrorWords else OctoColors.TextSecondary
    val glyph = when (kind) {
        NoteKind.Info -> OctoIcons.Info
        NoteKind.Error -> OctoIcons.Error
        NoteKind.Lock -> OctoIcons.Lock
        NoteKind.OpenLock -> OctoIcons.LockOpen
    }
    val tint = when (kind) {
        NoteKind.Info -> OctoColors.Accent
        NoteKind.Error -> ink
        NoteKind.Lock, NoteKind.OpenLock -> OctoColors.TextSecondary
    }
    val press = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .hoverLift(Corner.RowShape)
            .clickable(interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Button, onClick = onClick)
    }
    val caps = capsMiddle(FormType.note)
    Row(
        modifier.fillMaxWidth().then(press).padding(horizontal = RowInset),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Glyph(glyph, Modifier.alignBy { it.measuredHeight / 2 + caps }, size = IconSize.Table, tint = tint)
        Txt(text, FormType.note, ink, Modifier.weight(1f).alignBy(FirstBaseline), maxLines = Int.MAX_VALUE)
    }
}

// Advanced's head: its title, set as a group's title and at one size whether
// open or not, the line naming what is inside, and a chevron that alone
// turns. Its words start on the groups' content edge and its chevron ends on
// the rows' right edge, its middle on the middle of the title's capitals at
// every text size.
@Composable
internal fun AdvancedHead(open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, if (LocalReduceMotion.current) snap() else spring(), label = "advanced chevron")
    val caps = capsMiddle(FormType.group)
    Row(
        Modifier
            .fillMaxWidth()
            .hoverLift(Corner.RowShape)
            .clickable(interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Button, onClick = onClick)
            .semantics {
                heading()
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .padding(horizontal = RowInset, vertical = Space.M),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f).alignBy(FirstBaseline)) {
            Txt(SignInWords.ADVANCED, FormType.group, OctoColors.TextPrimary)
            Txt(SignInWords.ADVANCED_LINE, FormType.description, OctoColors.TextSecondary, maxLines = twoLines())
        }
        Glyph(
            OctoIcons.Chevron,
            Modifier.alignBy { it.measuredHeight / 2 + caps }.graphicsLayer {
                rotationZ = turn
                transformOrigin = CaretInk
            },
            size = IconSize.Toolbar,
            tint = OctoColors.TextSecondary,
        )
    }
}

// The middle of the caret's ink: Phosphor's caret-right sits from 88 to
// 184 of its 256 box, so it turns about there and stays on its line.
private val CaretInk = TransformOrigin(136f / 256f, 0.5f)

// A choice: its name, the line under it, and the chosen option's words at
// the row's end (under the name when the two do not fit side by side).
// Pressed, the options open under it in the card, each with a line on what
// it is for, the chosen one on the accent fill with a check; picking one
// closes them, and the keyboard goes back to the row. Its chevron sits on
// the middle of the name's capitals at every text size.
@Composable
internal fun <T> ChoiceFormRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    helper: (T) -> String?,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    var focusHead by remember { mutableStateOf(false) }
    val head = remember { FocusRequester() }
    val visibility = LocalFocusVisibility.current
    val value = label(selected)
    val caps = capsMiddle(FormType.name)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRequester(head)
                .hoverLift(Corner.RowShape)
                .clickable(interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Button) { open = !open }
                .semantics {
                    contentDescription = "$title, $value"
                    stateDescription = if (open) "Expanded" else "Collapsed"
                }
                .padding(horizontal = RowInset, vertical = Space.L),
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Box(Modifier.weight(1f).alignBy(FirstBaseline)) { NameAndValue(title, description, value) }
            Glyph(
                OctoIcons.Chevron,
                Modifier.alignBy { it.measuredHeight / 2 + caps }.graphicsLayer {
                    rotationZ = if (open) 270f else 90f
                    transformOrigin = CaretInk
                },
                size = IconSize.Table,
                tint = OctoColors.TextSecondary,
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = Space.M, end = Space.M, bottom = Space.M).selectableGroup()) {
                options.forEach { option ->
                    ChoiceOption(label(option), helper(option), option == selected) {
                        open = false
                        if (visibility.keyboard) focusHead = true
                        onSelect(option)
                    }
                }
            }
        }
    }
    LaunchedEffect(focusHead) {
        if (focusHead) {
            runCatching { head.requestFocus() }
            focusHead = false
        }
    }
}

// A choice row's name and line, and its value: side by side while the value
// fits beside the name in two lines, else the value under the name. The value
// may take all the room the name's own line leaves it, and never less than
// its share of the row.
@Composable
private fun NameAndValue(title: String, description: String?, value: String) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = with(density) { Space.Xl.roundToPx() }
        val wide = remember(value, measurer) { measurer.measure(value, FormType.value).size.width }
        val name = remember(title, measurer) { measurer.measure(title, FormType.name).size.width }
        val room = maxOf((constraints.maxWidth * VALUE_SHARE).toInt(), constraints.maxWidth - name - gap)
        if (wide <= room * 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Xl)) {
                NameLines(title, description, Modifier.weight(1f))
                Txt(value, FormType.value, OctoColors.TextSecondary, Modifier.widthIn(max = with(density) { room.toDp() }), maxLines = twoLines(), align = TextAlign.End)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
                NameLines(title, description, Modifier)
                Txt(value, FormType.value, OctoColors.TextSecondary, maxLines = twoLines())
            }
        }
    }
}

@Composable
private fun NameLines(title: String, description: String?, modifier: Modifier) {
    Column(modifier) {
        Txt(title, FormType.name, OctoColors.TextPrimary, maxLines = 2)
        description?.let { Txt(it, FormType.description, OctoColors.TextSecondary, maxLines = twoLines()) }
    }
}

// One option of an open choice: its words, what it is for, and, chosen, the
// accent fill and a check.
@Composable
private fun ChoiceOption(text: String, helper: String?, chosen: Boolean, pick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Regular)
            .hoverLift(Corner.RowShape)
            .selectable(chosen, interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.RadioButton, onClick = pick),
        contentAlignment = Alignment.CenterStart,
    ) {
        ChosenFill(chosen, Modifier.matchParentSize(), Corner.RowShape)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Column(Modifier.weight(1f)) {
                Txt(text, FormType.name, if (chosen) OctoColors.SelectedWords else OctoColors.TextPrimary, maxLines = 2)
                helper?.let { Txt(it, FormType.description, if (chosen) OctoColors.SelectedWords else OctoColors.TextSecondary, maxLines = twoLines()) }
            }
            if (chosen) Glyph(OctoIcons.Check, size = IconSize.Table, tint = OctoColors.SelectedWords)
        }
    }
}

// Where the parts a callout brings into view are: the sign-in page group,
// the Special setups group and the API key row. Also where Advanced's
// groups are, and whether Advanced was just opened from its head, so its
// first group is brought into view once.
@Stable
internal class FormPlaces {
    var gateway by mutableStateOf<LayoutCoordinates?>(null)
    var special by mutableStateOf<LayoutCoordinates?>(null)
    var key by mutableStateOf<LayoutCoordinates?>(null)
    var advanced by mutableStateOf<LayoutCoordinates?>(null)
    var openedFromHead = false
}

// Whether a field took the keyboard while a callout waited, so the callout
// leaves the person where they are.
@Stable
internal class CalloutFocus {
    var taken by mutableStateOf(false)
}

// What a callout needs from the place a form scrolls in: its scroll, how far
// under the top a group's title lands, the scroll's direct child (taken
// before its padding), whether the form has settled, the on-screen
// keyboard's height, the focus mark, and how tall the scroll's soft bottom
// edge is. The desktop has settled at once and has no on-screen keyboard.
internal class CalloutHost(
    val scroll: ScrollState,
    val topInset: Dp,
    val content: LayoutCoordinates,
    val settled: () -> Boolean,
    val imeBottom: () -> Int,
    val focus: CalloutFocus,
    val fade: Dp = 0.dp,
)

internal val LocalCalloutHost = compositionLocalOf<CalloutHost?> { null }

// Where the scroll goes so a group's title (at `groupTop` in the content)
// sits `inset` under the top of a view `viewport` tall; for a group, notes
// included, taller than the view less `inset`, so its last note ends `end`
// above the view's bottom. The scroll clamps what it cannot reach.
internal fun calloutTarget(groupTop: Float, groupHeight: Int, viewport: Int, inset: Int, end: Int): Int =
    if (groupHeight > viewport - inset) (groupTop + groupHeight + end - viewport).roundToInt() else (groupTop - inset).roundToInt()

private sealed interface CalloutStep {
    data object Taken : CalloutStep

    class Go(val host: CalloutHost, val target: Int) : CalloutStep
}

// Brings a group into view once `pending` turns true and the group is laid
// out, then runs `onShown`. A field taking the keyboard meanwhile ends it
// with no scroll. A drag that stops the scroll still counts as shown; a host
// that leaves the screen keeps `pending` for when it is back.
@Composable
internal fun CalloutEffect(pending: Boolean, host: CalloutHost?, group: () -> LayoutCoordinates?, onShown: () -> Unit) {
    val hostNow by rememberUpdatedState(host)
    val groupNow by rememberUpdatedState(group)
    val shown by rememberUpdatedState(onShown)
    val still by rememberUpdatedState(LocalReduceMotion.current)
    val density = LocalDensity.current
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        hostNow?.focus?.taken = false
        // An on-screen keyboard closes first; with none, this passes at once.
        snapshotFlow { hostNow?.let { it.focus.taken || it.imeBottom() == 0 } == true }.first { it }
        // The layout of the change that set `pending` is done by the next frame.
        withFrameNanos { }
        val step = snapshotFlow<CalloutStep?> {
            val at = hostNow ?: return@snapshotFlow null
            if (at.focus.taken) return@snapshotFlow CalloutStep.Taken
            val coordinates = groupNow() ?: return@snapshotFlow null
            if (!at.settled() || !coordinates.isAttached || !at.content.isAttached) return@snapshotFlow null
            val top = at.content.localPositionOf(coordinates, Offset.Zero).y
            val target = with(density) {
                calloutTarget(top, coordinates.size.height, at.scroll.viewportSize, at.topInset.roundToPx(), (CalloutEnd + at.fade).roundToPx())
            }
            CalloutStep.Go(at, target)
        }.filterNotNull().first()
        when (step) {
            CalloutStep.Taken -> shown()
            is CalloutStep.Go -> try {
                if (still) step.host.scroll.scrollTo(step.target) else step.host.scroll.glideTo(step.target)
                shown()
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) shown()
                throw e
            }
        }
    }
}

// Scrolls to `target` as a place, not a distance: each frame goes to where
// the animation is now, so content that shrinks under the scroll mid-way (a
// part closing, which clamps the scroll) does not carry it past the target.
// A drag still cancels it, as it cancels any scroll.
internal suspend fun ScrollState.glideTo(target: Int, spec: AnimationSpec<Float> = spring()) {
    scroll {
        animate(value.toFloat(), target.toFloat(), animationSpec = spec) { now, _ -> scrollBy(now - value) }
    }
}
