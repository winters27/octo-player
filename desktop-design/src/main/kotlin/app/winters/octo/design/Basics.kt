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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The hairline between rows and sections: one pixel, faint.
val SeparatorColor = Color.White.copy(alpha = 0.08f)

// What a row or button shows under the pointer: a faint lift, never a border.
val HoverFill = Color.White.copy(alpha = 0.06f)

// A card of settings: a faint lift off the page, its edge a hairline.
val CardFill = Color.White.copy(alpha = 0.035f)
val CardEdge = Color.White.copy(alpha = 0.07f)

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
) {
    BasicText(
        text,
        modifier = if (isHeading(style)) modifier.semantics { heading() } else modifier,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// Whether words in this style are a heading: a page's title, a section's name.
fun isHeading(style: TextStyle): Boolean =
    style === DesktopType.pageTitle || style === DesktopType.section || style === OctoType.title || style === OctoType.headline || style === OctoType.section

// An icon, white unless tinted.
@Composable
fun Glyph(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Color.White) {
    Image(
        rememberVectorPainter(icon),
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
