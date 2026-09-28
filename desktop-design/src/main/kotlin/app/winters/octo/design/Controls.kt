package app.winters.octo.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A round icon button with no glass: a white glyph that lifts faintly under
// the pointer. `active` marks a mode that is on (shuffle, repeat, an open
// panel) with the accent, never a border.
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = motionScale()
    val press by animateFloatAsState(if (pressed) motion.scale(0.9f) else 1f, octoTween(motion, OctoDuration.Press), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .alpha(if (enabled) 1f else 0.35f)
            .hoverLift(CircleShape, clickable = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // An active one (shuffle on, a panel open) glows rather than changing colour.
        GlowIcon(rememberVectorPainter(icon), tint = tint, lit = active, modifier = Modifier.size(iconSize))
    }
}

// A glass capsule with a white icon and word, like Play and Shuffle on an
// album. A lit one catches a little more light, as a control under the
// finger does; any capsule does under the pointer.
@Composable
fun GlazeCapsule(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val look = rememberButtonLook(interaction)
    Glaze(
        modifier
            .height(height)
            .buttonMotion(look, CircleShape)
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        light = if (lit || look.hovered || look.pressed) GlazeLight.Lifted else GlazeLight.Rest,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Glyph(icon, size = 20.dp)
            Txt(text, OctoType.label)
        }
    }
}

// A quiet action in words: accent text that lifts under the pointer, with
// no border and no fill.
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .hoverLift(CircleShape, clickable = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Glyph(icon, size = 16.dp, tint = OctoColors.TextSecondary)
        Txt(text, OctoType.label, OctoColors.TextSecondary)
    }
}

// A row of choices in one glaze, the chosen one a darker pill set into it,
// the way the phone's tab bar marks its tab.
@Composable
fun <T> GlazeSegments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlazeTabs(
        count = options.size,
        selected = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        height = 36.dp,
        fillWidth = false,
    ) { index, chosen ->
        Txt(label(options[index]), OctoType.label, if (chosen) OctoColors.TextPrimary else OctoColors.TextSecondary)
    }
}

// A ring that fills as something downloads; with no fraction known it turns.
@Composable
fun ProgressRing(fraction: Float?, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    if (fraction == null) {
        Spinner(modifier, size, Color.White.copy(alpha = 0.8f))
        return
    }
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), octoTween(motionScale(), OctoDuration.Neutral), label = "ring")
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, inset, box, style = Stroke(stroke))
        drawArc(Color.White, -90f, 360f * shown, false, inset, box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

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
    return animateDpAsState(target, spring(0.6f, 400f), label = "thumb").value
}

// Keeps a line of controls apart evenly.
@Composable
fun Spread(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
