package app.winters.octo.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.winters.octo.covers.CoverAlign
import app.winters.octo.covers.CoverArt
import app.winters.octo.covers.CoverLayer
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.CoverType
import app.winters.octo.covers.CoverTypesetter
import app.winters.octo.covers.Measured
import app.winters.octo.covers.Stop
import app.winters.octo.covers.composeCover
import kotlin.math.ceil

// Playlist covers drawn from their design (app.winters.octo.covers), the
// same way on the phone and the desktop: set in Inter Display (bundled by
// each app, `family`), measured and painted by Compose at the exact size
// the picture is made at. Writing Inter does not cover (Chinese, Arabic,
// emoji) falls back to the system's fonts. Pixels throughout: the density
// is 1.

private val OnePixel = Density(1f)

// How a cover's words are set, as a Compose text style.
fun coverTextStyle(type: CoverType, family: FontFamily): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = type.sizePx.sp,
    fontWeight = FontWeight(type.weight),
    letterSpacing = type.tracking.em,
    lineHeight = type.lineHeight.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private fun width(of: Float) = ceil(of).toInt().coerceAtLeast(1)

// Compose's text engine, as the design measures words.
class ComposeCoverTypesetter(private val measurer: TextMeasurer, private val family: FontFamily) : CoverTypesetter {
    override fun measure(text: String, type: CoverType, width: Float): Measured {
        val laid = measurer.measure(
            AnnotatedString(text),
            coverTextStyle(type, family),
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = type.maxLines,
            constraints = Constraints(maxWidth = width(width)),
        )
        val widest = (0 until laid.lineCount).maxOfOrNull { laid.getLineRight(it) - laid.getLineLeft(it) } ?: 0f
        return Measured(laid.lineCount, widest, laid.size.height.toFloat(), laid.hasVisualOverflow)
    }

    override fun widthOf(text: String, type: CoverType): Float =
        measurer.measure(AnnotatedString(text), coverTextStyle(type, family), softWrap = false, maxLines = 1).size.width.toFloat()
}

private fun colours(stops: List<Stop>) = stops.map { it.at to Color(it.argb) }.toTypedArray()

// Paints a composed cover into this scope, its top left at the origin.
fun DrawScope.drawCoverArt(art: CoverArt, measurer: TextMeasurer, family: FontFamily) {
    for (layer in art.layers) {
        when (layer) {
            is CoverLayer.Linear -> drawRect(Brush.linearGradient(*colours(layer.stops), start = Offset(layer.x0, layer.y0), end = Offset(layer.x1, layer.y1)))
            is CoverLayer.Radial -> drawRect(Brush.radialGradient(*colours(layer.stops), center = Offset(layer.cx, layer.cy), radius = layer.radius))
        }
    }
    for (words in art.words) {
        val align = if (words.align == CoverAlign.Left) TextAlign.Left else TextAlign.Right
        val laid = measurer.measure(
            AnnotatedString(words.text),
            coverTextStyle(words.type, family).copy(color = Color(words.ink), textAlign = align),
            overflow = if (words.measured.cut) TextOverflow.Ellipsis else TextOverflow.Visible,
            softWrap = true,
            maxLines = words.type.maxLines,
            constraints = Constraints.fixedWidth(width(words.width)),
        )
        drawText(laid, topLeft = Offset(words.left, words.top))
    }
}

// A composed cover as a picture of its own size.
fun renderCoverArt(art: CoverArt, measurer: TextMeasurer, family: FontFamily): ImageBitmap {
    val image = ImageBitmap(art.side, art.side)
    val side = art.side.toFloat()
    CanvasDrawScope().draw(OnePixel, LayoutDirection.Ltr, Canvas(image), Size(side, side)) { drawCoverArt(art, measurer, family) }
    return image
}

// A text engine for covers, with the system's fonts.
fun coverMeasurer(fonts: FontFamily.Resolver): TextMeasurer = TextMeasurer(fonts, OnePixel, LayoutDirection.Ltr, 0)

// A playlist's cover designed and drawn `side` pixels square.
fun designCover(spec: CoverSpec, side: Int, measurer: TextMeasurer, family: FontFamily): ImageBitmap =
    renderCoverArt(composeCover(spec, side, ComposeCoverTypesetter(measurer, family)), measurer, family)
