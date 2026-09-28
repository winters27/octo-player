package app.winters.octo.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cbrt

// White at an opacity: on the dark background the opacity carries the
// hierarchy, not separate greys. Octo's own text colours (white titles,
// accent subtitles) still lead; these are for what has no Octo colour yet,
// like hover fills, hairlines and disabled text.
object OctoInk {
    const val PrimaryAlpha = 0.85f
    const val SecondaryAlpha = 0.55f
    const val TertiaryAlpha = 0.25f
    const val QuaternaryAlpha = 0.10f
    const val QuinaryAlpha = 0.05f

    // Titles and body.
    val Primary = Color.White.copy(alpha = PrimaryAlpha)

    // Subtitles and details.
    val Secondary = Color.White.copy(alpha = SecondaryAlpha)

    // Disabled words and placeholders.
    val Tertiary = Color.White.copy(alpha = TertiaryAlpha)

    // Hover fills and hairlines.
    val Quaternary = Color.White.copy(alpha = QuaternaryAlpha)

    // Resting fills and alternate rows.
    val Quinary = Color.White.copy(alpha = QuinaryAlpha)

    // How faint a control that cannot be used right now is.
    const val DisabledAlpha = 0.5f
}

// Corner sizes.
object OctoRadius {
    // Buttons and groups in the chrome, and their small size.
    val Chrome: Dp = 10.dp
    val ChromeSmall: Dp = 6.dp

    // List rows.
    val Row: Dp = 12.dp

    // Artwork, from a thumbnail to a hero.
    val ArtXs: Dp = 3.dp
    val ArtS: Dp = 6.dp
    val ArtM: Dp = 8.dp
    val ArtL: Dp = 12.dp

    // Windows and dialogs.
    val Dialog: Dp = 12.dp

    // Anything else.
    val Xs: Dp = 2.dp
    val S: Dp = 4.dp
    val M: Dp = 6.dp
    val L: Dp = 8.dp
}

// The same corners as shapes.
object OctoShapes {
    val Chrome = RoundedCornerShape(OctoRadius.Chrome)
    val ChromeSmall = RoundedCornerShape(OctoRadius.ChromeSmall)
    val Row = RoundedCornerShape(OctoRadius.Row)
    val ArtXs = RoundedCornerShape(OctoRadius.ArtXs)
    val ArtS = RoundedCornerShape(OctoRadius.ArtS)
    val ArtM = RoundedCornerShape(OctoRadius.ArtM)
    val ArtL = RoundedCornerShape(OctoRadius.ArtL)
    val Dialog = RoundedCornerShape(OctoRadius.Dialog)
    val Pill = CircleShape
}

// The height of a menu row: compact for the pointer, taller for a finger.
object MenuRowHeight {
    val Pointer: Dp = 30.dp
    val Touch: Dp = 40.dp
}

// A menu row under the finger or the pointer shows a quiet accent-tinted
// pill, `strength` of the way in (0 to 1), read while drawing.
fun Modifier.menuRowPress(shape: Shape, strength: () -> Float): Modifier = drawBehind {
    val s = strength()
    if (s > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), OctoColors.MenuPress, alpha = s)
}

// A picture or card raised well off the page, like a hero cover.
fun Modifier.raisedShadow(shape: Shape): Modifier =
    dropShadow(shape, Shadow(radius = shadowBlur(40f), color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 8.dp)))

// A small lift, like artwork in a grid.
fun Modifier.liftShadow(shape: Shape): Modifier =
    dropShadow(shape, Shadow(radius = shadowBlur(14f), color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 4.dp)))

// The shadow a chrome button or group casts.
fun Modifier.chromeShadow(shape: Shape): Modifier = this
    .dropShadow(shape, Shadow(radius = shadowBlur(12f), color = Color.Black.copy(alpha = 0.10f), offset = DpOffset(0.dp, 4.dp)))
    .dropShadow(shape, Shadow(radius = shadowBlur(2f), color = Color.Black.copy(alpha = 0.02f), offset = DpOffset(0.dp, 1.dp)))

// How light a colour looks, from 0 (black) to 1 (white), the way the eye
// judges it rather than by its raw channels.
fun perceivedLightness(color: Color): Float {
    val y = color.luminance()
    val l = if (y > 216f / 24389f) 116f * cbrt(y) - 16f else y * 24389f / 27f
    return (l / 100f).coerceIn(0f, 1f)
}

// Where words switch from white to black on a fill.
const val LightFillThreshold = 0.58f

// The colour words take on a fill: black on a light one, white on a dark
// one.
fun contentColorFor(fill: Color): Color =
    if (perceivedLightness(fill) > LightFillThreshold) Color.Black else Color.White

// The focus ring: a line `width` wide just outside the edge, at `strength`
// (0 hidden, 1 full), read while drawing so it fades without recomposing.
fun Modifier.focusRing(
    shape: Shape,
    strength: () -> Float,
    width: Dp = 2.dp,
    color: Color = OctoColors.FocusRing,
): Modifier = drawWithContent {
    drawContent()
    val alpha = strength()
    if (alpha <= 0f) return@drawWithContent
    val w = width.toPx()
    val outline = shape.createOutline(Size(size.width + w, size.height + w), layoutDirection, this)
    translate(-w / 2f, -w / 2f) { drawOutline(outline, color, alpha = alpha.coerceIn(0f, 1f), style = Stroke(w)) }
}
