package app.winters.octo.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

// How a chrome button's glass and icon answer a press, the pointer and the
// keyboard: pressed, the glass pops out while the icon dips in; under the
// pointer, the icon grows a little.
internal class ChromeLook(val glass: () -> Float, val icon: () -> Float, val ring: () -> Float, val lit: Boolean)

@Composable
internal fun rememberChromeLook(interaction: MutableInteractionSource): ChromeLook {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = motionScale()
    val glass by animateFloatAsState(
        if (pressed) motion.scale(OctoPress.Pop) else 1f,
        octoTween(motion, OctoDuration.Press),
        label = "chrome glass",
    )
    val icon by animateFloatAsState(
        when {
            pressed -> motion.scale(OctoPress.Scale)
            hovered -> motion.scale(OctoPress.Pop)
            else -> 1f
        },
        octoTween(motion, if (pressed) OctoDuration.Press else OctoDuration.Hover),
        label = "chrome icon",
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, octoTween(motion, OctoDuration.Neutral), label = "chrome ring")
    return ChromeLook({ glass }, { icon }, { ring }, pressed || hovered)
}

// A lone round control floating over content, the same glaze as the bar.
// Pressed, the glass pops out and the icon dips in. `accent` plays the
// icon's gesture on every tap, like Pulse for a heart.
@Composable
fun GlazedIconButton(
    backdrop: HazeState,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    accent: IconAccent? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val look = rememberChromeLook(interaction)
    val gesture = if (accent != null) rememberIconAccent(accent) else null
    FloatingGlaze(
        backdrop,
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = look.glass()
                scaleY = look.glass()
            }
            .focusRing(CircleShape, look.ring)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    gesture?.play()
                    onClick()
                },
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        Image(
            rememberVectorPainter(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(OctoColors.TextPrimary),
            modifier = Modifier
                .align(Alignment.Center)
                .size(if (size >= 56.dp) 22.dp else 20.dp)
                .graphicsLayer {
                    scaleX = look.icon()
                    scaleY = look.icon()
                }
                .iconAccent(gesture),
        )
    }
}
