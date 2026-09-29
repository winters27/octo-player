package app.winters.octo.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import app.winters.octo.covers.CoverBackground
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.CoverType
import app.winters.octo.covers.CoverTypesetter
import app.winters.octo.covers.CoverWords
import app.winters.octo.covers.Measured
import app.winters.octo.covers.applyVeil
import app.winters.octo.covers.planCover
import app.winters.octo.covers.sampleBackground
import kotlin.math.ceil

// Playlist covers drawn from their design (app.winters.octo.covers), the
// same way on the phone and the desktop: a painted background with the
// colour-keeping veil, and words set in Inter Display (bundled by each app,
// `family`), measured and painted by Compose at the exact size the picture
// is made at. Writing Inter does not cover (Chinese, Arabic,
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
        // Lines sit a whole line height apart, as the design (and its
        // reference) counts them, not rounded to the pixel.
        return Measured(laid.lineCount, widest, laid.lineCount * type.sizePx * type.lineHeight, laid.hasVisualOverflow)
    }

    // The line's own width, not rounded up to the pixel.
    override fun widthOf(text: String, type: CoverType): Float {
        val laid = measurer.measure(AnnotatedString(text), coverTextStyle(type, family), softWrap = false, maxLines = 1)
        return if (laid.lineCount == 0) 0f else laid.getLineRight(0) - laid.getLineLeft(0)
    }
}

// Paints a cover's words into this scope, its top left at the origin.
fun DrawScope.drawCoverWords(words: List<CoverWords>, measurer: TextMeasurer, family: FontFamily) {
    for (w in words) {
        val align = if (w.align == CoverAlign.Left) TextAlign.Left else TextAlign.Right
        val laid = measurer.measure(
            AnnotatedString(w.text),
            coverTextStyle(w.type, family).copy(color = Color(w.ink), textAlign = align),
            overflow = if (w.measured.cut) TextOverflow.Ellipsis else TextOverflow.Visible,
            softWrap = true,
            maxLines = w.type.maxLines,
            constraints = Constraints.fixedWidth(width(w.width)),
        )
        drawText(laid, topLeft = Offset(w.left, w.top))
    }
}

// The veiled background with the words over it, as a picture of its own size.
fun renderCover(background: ImageBitmap, words: List<CoverWords>, measurer: TextMeasurer, family: FontFamily): ImageBitmap {
    if (words.isEmpty()) return background
    val image = ImageBitmap(background.width, background.height)
    val side = Size(background.width.toFloat(), background.height.toFloat())
    CanvasDrawScope().draw(OnePixel, LayoutDirection.Ltr, Canvas(image), side) {
        drawImage(background)
        drawCoverWords(words, measurer, family)
    }
    return image
}

// A text engine for covers, with the system's fonts.
fun coverMeasurer(fonts: FontFamily.Resolver): TextMeasurer = TextMeasurer(fonts, OnePixel, LayoutDirection.Ltr, 0)

// A playlist's cover made `side` pixels square: the words laid out, its
// background (`full`, the library file's pixels and their side) brought
// to size, the veil laid in under the words (`picture` turns ARGB pixels
// into the platform's picture), then the words over it.
fun designCover(
    spec: CoverSpec,
    side: Int,
    measurer: TextMeasurer,
    family: FontFamily,
    full: (CoverBackground) -> Pair<IntArray, Int>,
    picture: (IntArray, Int) -> ImageBitmap,
): ImageBitmap {
    val plan = planCover(spec, side, ComposeCoverTypesetter(measurer, family))
    val (pixels, size) = full(plan.background)
    val veiled = applyVeil(sampleBackground(pixels, size, side), side, plan.veil)
    return renderCover(picture(veiled, side), plan.words, measurer, family)
}
