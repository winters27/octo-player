package app.winters.octo.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// The sizes a switch comes in: its track and its thumb.
enum class SwitchSize(val width: Dp, val height: Dp, val thumb: Dp) {
    Small(30.dp, 18.dp, 14.dp),
    Default(36.dp, 22.dp, 18.dp),
    Large(44.dp, 26.dp, 22.dp),
}

// The room between the track's edge and the thumb.
private val TrackPadding = 2.dp

// How much wider the thumb stretches while it is held.
const val SwitchSquish = 1.18f

// Where the thumb is and how wide, in a track `track` wide with `padding`
// either side. `position` runs from 0 (off, at the start) to 1 (on, at the
// end); `stretch` from 0 (at rest) to 1 (held). A held thumb grows toward
// where it is going, keeping the edge on the side it is leaving, as a
// finger pressing a soft thumb squashes it.
data class SwitchThumb(val x: Float, val width: Float)

fun switchThumb(track: Float, thumb: Float, padding: Float, position: Float, stretch: Float): SwitchThumb {
    val width = thumb * (1f + (SwitchSquish - 1f) * stretch)
    val travel = track - 2f * padding - thumb
    val x = padding + travel * position - (width - thumb) * position
    return SwitchThumb(x, width)
}

// The track off: a faint recessed white.
private val OffTrack = Color.White.copy(alpha = 0.10f)

// The on/off switch for settings rows: the accent, glowing faintly, when on;
// a faint recessed pill when off. The thumb slides on the default curve and
// squashes toward where it is going while held.
@Composable
fun OctoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: SwitchSize = SwitchSize.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = motionScale()
    val on by animateFloatAsState(if (checked) 1f else 0f, octoTween(motion, OctoDuration.Card), label = "switch on")
    val stretch by animateFloatAsState(
        if (pressed && enabled) motion.distance else 0f,
        octoTween(motion, OctoDuration.Press),
        label = "switch squish",
    )
    val hover by animateFloatAsState(if (hovered && enabled) 0.07f else 0f, octoTween(motion, OctoDuration.Fill), label = "switch hover")
    val ring by animateFloatAsState(if (focused) 1f else 0f, octoTween(motion, OctoDuration.Neutral), label = "switch ring")
    val thumbSize = size.thumb

    Box(
        modifier
            .size(size.width, size.height)
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .focusRing(CircleShape, { ring })
            // The glow under the track when it is on: tight and faint, since
            // glow is garnish. (The reference pulls it in with a negative
            // spread, which is not drawn the same everywhere; a smaller blur
            // and less of it reach the same look.)
            .dropShadow(CircleShape) {
                radius = shadowBlur(5f).toPx()
                offset = Offset(0f, 1.dp.toPx())
                color = OctoColors.Accent
                alpha = 0.30f * on
            }
            // A faint light drop along the bottom edge.
            .dropShadow(CircleShape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.05f), offset = DpOffset(0.dp, 0.5.dp)))
            .drawBehind {
                val corner = CornerRadius(this.size.height / 2f)
                drawRoundRect(OffTrack, cornerRadius = corner, alpha = 1f - on)
                drawRoundRect(OctoColors.Accent, cornerRadius = corner, alpha = on)
                // Under the pointer the track takes in a little white.
                drawRoundRect(Color.White, cornerRadius = corner, alpha = hover)
            }
            // Recessed while off: a shade along the top inside edge.
            .innerShadow(CircleShape) {
                radius = shadowBlur(1.5f).toPx()
                offset = Offset(0f, 0.5.dp.toPx())
                color = Color.Black
                alpha = 0.22f * (1f - on)
            }
            // A faint glaze rim.
            .innerShadow(CircleShape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.White.copy(alpha = 0.06f)))
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                // The thumb's shadows, then the thumb.
                .drawBehind {
                    val pad = TrackPadding.toPx()
                    val d = thumbSize.toPx()
                    val t = switchThumb(this.size.width, d, pad, on, stretch)
                    val top = (this.size.height - d) / 2f
                    val corner = CornerRadius(d / 2f)
                    drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(t.x, top + 0.5.dp.toPx()), Size(t.width, d), corner)
                    drawRoundRect(Color.Black.copy(alpha = 0.12f), Offset(t.x, top + 1.dp.toPx()), Size(t.width, d), corner)
                    drawRoundRect(Color.White, Offset(t.x, top), Size(t.width, d), corner)
                },
        )
    }
}
