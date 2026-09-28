package app.winters.octo.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The sizes a standard button comes in: its height, its height in a dense
// list, the room either side of its words, and the size of its words.
// Large is the phone's, the size a thumb wants.
enum class ButtonSize(val height: Dp, val dense: Dp, val padding: Dp, val iconSize: Dp, val fontSize: TextUnit) {
    ExtraSmall(24.dp, 20.dp, 8.dp, 14.dp, 12.5.sp),
    Small(30.dp, 24.dp, 12.dp, 16.dp, 13.5.sp),
    Medium(36.dp, 28.dp, 12.dp, 18.dp, 13.5.sp),
    Large(50.dp, 40.dp, 24.dp, 20.dp, 13.5.sp),
}

// Where a button is in its states, each animated: how far it is pressed
// in, how far it has risen under the pointer, how strong its hover and
// press shades are, and how visible its focus ring is.
@Stable
class ButtonLook internal constructor(
    private val scaleState: State<Float>,
    private val liftState: State<Dp>,
    private val hoverState: State<Float>,
    private val pressState: State<Float>,
    private val ringState: State<Float>,
    val pressed: Boolean,
    val hovered: Boolean,
) {
    val scale: Float get() = scaleState.value
    val lift: Dp get() = liftState.value
    val hoverShade: Float get() = hoverState.value
    val pressShade: Float get() = pressState.value
    val ring: Float get() = ringState.value
}

// How a button answers the pointer, the finger and the keyboard, from its
// interactions: pressed, it shrinks to `pressScale` and darkens; under the
// pointer, it rises by `lift` and lightens; focused by the keyboard, it
// shows the accent ring. All of it follows the motion scale.
@Composable
fun rememberButtonLook(
    interaction: InteractionSource,
    pressScale: Float = OctoPress.Scale,
    lift: Dp = OctoPress.LiftSmall,
): ButtonLook {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = motionScale()
    val scale = animateFloatAsState(
        if (pressed) motion.scale(pressScale) else 1f,
        octoTween(motion, OctoDuration.Press),
        label = "button press",
    )
    val rise = animateDpAsState(
        if (hovered && !pressed) motion.travel(lift) else 0.dp,
        octoTween(motion, OctoDuration.Hover),
        label = "button lift",
    )
    val hover = animateFloatAsState(if (hovered) 0.10f else 0f, octoTween(motion, OctoDuration.Fill), label = "button hover")
    val press = animateFloatAsState(if (pressed) 0.10f else 0f, octoTween(motion, OctoDuration.Press), label = "button shade")
    val ring = animateFloatAsState(if (focused) 1f else 0f, octoTween(motion, OctoDuration.Neutral), label = "button ring")
    return ButtonLook(scale, rise, hover, press, ring, pressed, hovered)
}

// A large surface held down, like a row or a card: it gives a little, to
// 0.985, and springs back, over the squeeze's time there and back.
@Composable
fun rememberPressSqueeze(interaction: InteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    val motion = motionScale()
    return animateFloatAsState(
        if (pressed) motion.scale(OctoPress.LargeScale) else 1f,
        octoTween(motion, OctoDuration.Squeeze / 2),
        label = "squeeze",
    )
}

// Moves a button by its look: pressed in, risen, and ringed when focused.
fun Modifier.buttonMotion(look: ButtonLook, shape: Shape): Modifier = this
    .graphicsLayer {
        scaleX = look.scale
        scaleY = look.scale
        translationY = -look.lift.toPx()
    }
    .focusRing(shape, { look.ring })

// The hover and press shades over a button's fill: white under the
// pointer, black under the finger.
@Composable
fun BoxScope.ButtonShades(look: ButtonLook, shape: Shape) {
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            // Read while drawing, so the shades fade without recomposing.
            .drawBehind {
                drawRect(Color.White.copy(alpha = look.hoverShade))
                drawRect(Color.Black.copy(alpha = look.pressShade))
            },
    )
}

// A button's words, with an optional icon 8 dp before them, in `color`.
// While `loading` the words keep their room, so the width never jumps, and
// a spinner turns in their place.
@Composable
fun ButtonLabel(
    text: String,
    color: Color,
    size: ButtonSize,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    loading: Boolean = false,
    style: TextStyle = OctoType.label,
) {
    Box(modifier.padding(horizontal = size.padding), contentAlignment = Alignment.Center) {
        Row(
            Modifier.alpha(if (loading) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = Modifier.size(size.iconSize))
            BasicText(text, style = style.copy(color = color, fontSize = size.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) Spinner(size = size.iconSize, color = color)
    }
}
