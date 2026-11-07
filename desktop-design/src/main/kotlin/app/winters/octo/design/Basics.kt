package app.winters.octo.design

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The hairline between rows and sections: one pixel, faint.
val SeparatorColor: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.08f)

// What a row or button shows under the pointer: a faint lift, never a border.
val HoverFill: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.06f)

// A card of settings: on a dark theme a faint glaze of the words' own ink
// off the page, its edge a hairline of the same; on a light one the card
// color, nearly opaque, lifted off the page by the theme's edge line. Read
// while composing, so a new palette redraws them.
val CardFill: Color @Composable @ReadOnlyComposable get() =
    if (LocalOctoSurfaces.current.light) LocalOctoSurfaces.current.card.copy(alpha = 0.92f) else OctoColors.TextPrimary.copy(alpha = 0.045f)
val CardEdge: Color @Composable @ReadOnlyComposable get() =
    if (LocalOctoSurfaces.current.light) OctoColors.Edge else OctoColors.TextPrimary.copy(alpha = 0.085f)

// Text in the app's type, one line and cut with an ellipsis unless told
// otherwise. A page's title and a section's name are headings, so a screen
// reader can jump between them.
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color = OctoColors.TextPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    control: Boolean = false,
) {
    val drawn = if (control) style else weighed(style, color)
    BasicText(
        text,
        modifier = if (isHeading(style)) modifier.semantics { heading() } else modifier,
        style = if (align != null) drawn.copy(color = color, textAlign = align) else drawn.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// A style as this theme draws it: dark words on a light page read thinner
// than light words on a dark one, so on a light theme the semibold styles
// drawn in the main word color (`color` is TextPrimary: titles, emphasis,
// row titles) are drawn one weight up, in bold. Secondary and muted words,
// body words and a control's label (`control` on Txt) stay as they are.
@Composable
@ReadOnlyComposable
fun weighed(style: TextStyle, color: Color): TextStyle =
    if (LocalOctoSurfaces.current.light && style.fontWeight == FontWeight.SemiBold && color == OctoColors.TextPrimary) {
        style.copy(fontWeight = FontWeight.Bold)
    } else {
        style
    }

// Whether words in this style are a heading: a page's title, a section's name.
fun isHeading(style: TextStyle): Boolean =
    style === DesktopType.pageTitle || style === DesktopType.section || style === OctoType.title || style === OctoType.headline || style === OctoType.section

// An icon, in the words' color unless tinted. At one of the IconSize sizes it is drawn on
// whole pixels; `exact` draws it as written whatever the size, for an icon
// whose size is worked out (a share of a cover) rather than a token.
@Composable
fun Glyph(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = IconSize.Transport, tint: Color = OctoColors.TextPrimary, exact: Boolean = false) {
    Image(
        if (exact) exactPainter(icon) else crispPainter(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

// A one-pixel line across, or down when `vertical`.
@Composable
fun Separator(modifier: Modifier = Modifier, vertical: Boolean = false) {
    Box(
        if (vertical) modifier.fillMaxHeight().width(1.dp).background(SeparatorColor)
        else modifier.fillMaxWidth().height(1.dp).background(SeparatorColor),
    )
}

// A faint lift under the pointer, in `shape`, and the hand cursor when the
// thing can be clicked.
fun Modifier.hoverLift(shape: Shape = RectangleShape, clickable: Boolean = true, lifted: Boolean = false): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    this
        .hoverable(interaction)
        .then(if (clickable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clip(shape)
        .then(if (hovered || lifted) Modifier.background(HoverFill) else Modifier)
}
