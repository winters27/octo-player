package app.winters.octo.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.awtRole
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import javax.accessibility.AccessibleRole
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A round icon button with no glass: a white glyph that lifts faintly under
// the pointer. `active` marks a mode that is on (shuffle, repeat, an open
// panel) with the accent, never a border. `toggled`, when given, tells a
// screen reader it is a switch that is on or off. Resting the pointer on
// it shows `tooltip` (its description, unless told otherwise). `exact`
// draws the glyph as written, for one at a size outside the icon sizes.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = IconSize.Transport,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = OctoColors.TextPrimary,
    toggled: Boolean? = null,
    tooltip: String = description,
    exact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val tip = rememberTooltipState()
    val pressed by interaction.collectIsPressedAsState()
    val motion = motionScale()
    val press by animateFloatAsState(if (pressed) motion.scale(0.9f) else 1f, octoTween(motion, OctoDuration.Press), label = "press")
    // However small the glyph, the button is never less than the least a
    // pointer should have to hit.
    Box(
        modifier
            .size(size.coerceAtLeast(Focus.MinTarget))
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .alpha(if (enabled) 1f else 0.35f)
            .hoverLift(CircleShape, clickable = enabled)
            .tabStop(LocalTabStops.current)
            .tooltipTarget(tip)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (toggled != null) {
                    toggleableState = ToggleableState(toggled)
                    awtRole = AccessibleRole.TOGGLE_BUTTON
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // An active one (shuffle on, a panel open) glows rather than changing colour.
        GlowIcon(if (exact) exactPainter(icon) else crispPainter(icon), tint = tint, lit = active, modifier = Modifier.size(iconSize))
        TooltipPopup(tip, tooltip)
    }
}

// A glass capsule with a white icon and word, like Play and Shuffle on an
// album. A lit one catches a little more light, as a control under the
// finger does; any capsule does under the pointer. A `quiet` one opens a
// menu beside a title: the chips' resting fill, softer words and icon that
// brighten under the pointer, and a small chevron saying it opens.
@Composable
fun GlazeCapsule(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    quiet: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val look = rememberButtonLook(interaction)
    if (quiet) {
        val held = look.hovered || look.pressed
        val ink = if (held) OctoColors.TextPrimary else OctoColors.TextSecondary
        Row(
            modifier
                .height(height)
                .buttonMotion(look, CircleShape, OctoColors.FocusRing)
                .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
                .clip(CircleShape)
                .background(OctoColors.TextPrimary.copy(alpha = if (held) 0.12f else 0.08f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .tabStop(LocalTabStops.current)
                .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = text }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Glyph(icon, size = IconSize.Table, tint = ink)
            Txt(text, OctoType.label, ink, control = true)
            Glyph(OctoIcons.Chevron, Modifier.rotate(90f), size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted, exact = true)
        }
        return
    }
    Glaze(
        modifier
            .height(height)
            .buttonMotion(look, CircleShape, OctoColors.FocusRing)
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .tabStop(LocalTabStops.current)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        light = if (lit || look.hovered || look.pressed) GlazeLight.Lifted else GlazeLight.Rest,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Glyph(icon, size = IconSize.Transport)
            Txt(text, OctoType.label, control = true)
        }
    }
}

// A round glass button with a white icon, like Play and Shuffle at the end
// of a page's control line. A lit one catches a little more light, as any
// does under the pointer; one not `enabled` is dimmed.
@Composable
fun GlazeRound(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lit: Boolean = false,
    size: Dp = 36.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val look = rememberButtonLook(interaction)
    // With no words of its own, resting the pointer on it says what it does.
    val tip = rememberTooltipState()
    Glaze(
        modifier
            .size(size)
            .buttonMotion(look, CircleShape, OctoColors.FocusRing)
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .tabStop(LocalTabStops.current)
            .tooltipTarget(tip)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        light = if (lit || look.hovered || look.pressed) GlazeLight.Lifted else GlazeLight.Rest,
    ) {
        Glyph(icon, size = IconSize.Toolbar)
        TooltipPopup(tip, description)
    }
}

// A quiet action in words: accent text that lifts under the pointer, with
// no border and no fill. `style` sets it in the words beside it instead, as
// under a list row's own lines.
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    style: TextStyle = OctoType.label,
) {
    // With `checked` it is a switch, in white while on.
    val press = if (checked == null) {
        Modifier.clickable(interactionSource = null, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.toggleable(checked, interactionSource = null, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    }
    val tint = if (checked == true) OctoColors.TextPrimary else OctoColors.TextSecondary
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .hoverLift(CircleShape, clickable = enabled)
            .tabStop(LocalTabStops.current)
            .then(press)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Glyph(icon, size = IconSize.Table, tint = tint)
        Txt(text, style, tint, control = true)
    }
}

// The one action a notice offers: a capsule of the accent with the page's dark ink, so it reads
// as a button beside the notice's words. Lighter under the pointer, a little dimmer pressed.
@Composable
fun AccentAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val hovered by press.collectIsHoveredAsState()
    val fill = when {
        pressed -> OctoColors.Accent.copy(alpha = 0.82f)
        hovered -> OctoColors.AccentHover
        else -> OctoColors.Accent
    }
    Row(
        modifier
            .tabStop(LocalTabStops.current)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .hoverable(press)
            .clickable(interactionSource = press, indication = FocusRing(CircleShape), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, OctoType.label, OctoColors.Background)
    }
}

// A row of choices in one glaze, the chosen one a darker pill set into it,
// the way the phone's tab bar marks its tab. A choice `enabled` refuses is
// shown dimmed and cannot be picked.
@Composable
fun <T> GlazeSegments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    labelWidth: Boolean = false,
    tabPadding: Dp = 14.dp,
) {
    GlazeTabs(
        count = options.size,
        selected = options.indexOf(selected),
        onSelect = { if (enabled(options[it])) onSelect(options[it]) },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        height = 36.dp,
        fillWidth = false,
        indication = FocusRing(CircleShape),
        enabled = { enabled(options[it]) },
        labelWidth = labelWidth,
        tabPadding = tabPadding,
    ) { index, chosen ->
        val color = when {
            !enabled(options[index]) -> OctoColors.TextMuted
            chosen -> OctoColors.OnAccent
            else -> OctoColors.TextSecondary
        }
        // Every segment takes the same room, so the pill does not change
        // size as the choice moves: the words keep one weight in every
        // face, the chosen one wears the check before its words, the others
        // hold half of that room on each side.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chosen) {
                Glyph(OctoIcons.Check, size = SegmentCheck, tint = OctoColors.OnAccent)
                Spacer(Modifier.width(SegmentCheckGap))
            } else {
                Spacer(Modifier.width((SegmentCheck + SegmentCheckGap) / 2))
            }
            Txt(label(options[index]), OctoType.label, color)
            if (!chosen) Spacer(Modifier.width((SegmentCheck + SegmentCheckGap) / 2))
        }
    }
}

// The check shown before the chosen segment's words, and the gap after it.
val SegmentCheck = IconSize.Inline
val SegmentCheckGap = 4.dp

// A ring that fills as something downloads; with no fraction known it turns.
@Composable
fun ProgressRing(fraction: Float?, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val ink = OctoColors.TextPrimary
    if (fraction == null) {
        Spinner(modifier, size, ink.copy(alpha = 0.8f))
        return
    }
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), octoTween(motionScale(), OctoDuration.Neutral), label = "ring")
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(ink.copy(alpha = 0.18f), 0f, 360f, false, inset, box, style = Stroke(stroke))
        drawArc(ink, -90f, 360f * shown, false, inset, box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// The ring laid flat: a thin line that fills, for how far a download has
// come or how much of a list the library has. With no fraction known it
// is the empty track alone.
@Composable
fun MeterLine(fraction: Float?, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), octoTween(motionScale(), OctoDuration.Neutral), label = "line")
    val ink = OctoColors.TextPrimary
    Canvas(modifier.fillMaxWidth().height(MeterLineHeight)) {
        val round = CornerRadius(size.height / 2)
        drawRoundRect(ink.copy(alpha = 0.18f), cornerRadius = round)
        if (fraction != null && shown > 0f) drawRoundRect(ink, size = Size(size.width * shown, size.height), cornerRadius = round)
    }
}

val MeterLineHeight = 4.dp

// The thumb width a line slider grows to under the pointer.
private val ThumbHover = 6.dp

// Space between a small control row's items.
val ControlGap = 4.dp

// A glass pill that wraps a row of small controls, for a group like the
// transport buttons: 4 dp in from its ends, the controls 4 dp apart, with
// the chrome shadow. The controls inside wear no glass of their own.
@Composable
fun GlazeGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Glaze(modifier.chromeShadow(CircleShape)) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(ControlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

// How wide a thumb shows, by whether the pointer is over the line or it is
// being dragged.
@Composable
internal fun thumbSize(hovered: Boolean, dragging: Boolean): Dp {
    val target = when {
        dragging -> 7.dp
        hovered -> ThumbHover
        else -> 0.dp
    }
    return animateDpAsState(target, if (motionScale().still) snap() else spring(0.6f, 400f), label = "thumb").value
}

// Keeps a line of controls apart evenly.
@Composable
fun Spread(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
