package app.winters.octo.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// The body's words: the muted secondary ink, with room between the lines.
private val BodyStyle = OctoType.bodySmall.copy(lineHeight = 21.sp)

private val Heading1 = OctoType.headline.copy(lineHeight = 26.sp)
private val Heading2 = OctoType.body.copy(fontWeight = FontWeight.Bold, lineHeight = 22.sp)
private val Heading3 = OctoType.label.copy(lineHeight = 19.sp)

private val CodeStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = Color.White.copy(alpha = 0.08f))

private val LinkStyle = TextLinkStyles(SpanStyle(color = OctoColors.TextPrimary, textDecoration = TextDecoration.Underline))

private val LineColour = OctoColors.TextPrimary.copy(alpha = 0.08f)

// A page of markdown in Octo's type: headings in white, the body in the
// quieter secondary ink with bold words lit white, soft dots for bullets,
// and hairlines for rules. Links open when tapped.
@Composable
fun MarkdownText(blocks: List<Block>, modifier: Modifier = Modifier) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val first = index == 0
            when (block) {
                is Block.Heading -> {
                    val (style, colour, above) = when (block.level) {
                        1 -> Triple(Heading1, OctoColors.TextPrimary, 28.dp)
                        2 -> Triple(Heading2, OctoColors.TextPrimary, 26.dp)
                        else -> Triple(Heading3, OctoColors.TextSecondary, 18.dp)
                    }
                    Text(
                        annotated(block.text),
                        style = style,
                        color = colour,
                        modifier = Modifier
                            .padding(top = if (first) 0.dp else above, bottom = 8.dp)
                            .semantics { heading() },
                    )
                }
                is Block.Paragraph -> Text(
                    annotated(block.text),
                    style = BodyStyle,
                    color = OctoColors.TextSecondary,
                    modifier = Modifier.padding(top = if (first) 0.dp else 10.dp),
                )
                is Block.Bullets -> Column(
                    Modifier.padding(top = if (first) 0.dp else 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    block.items.forEach { item ->
                        BulletLine(item.text, BodyStyle, dot = 5.dp, dotColour = OctoColors.TextMuted)
                        if (item.children.isNotEmpty()) {
                            Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.children.forEach { child ->
                                    BulletLine(child.text, BodyStyle, dot = 4.dp, dotColour = OctoColors.TextMuted.copy(alpha = 0.35f))
                                }
                            }
                        }
                    }
                }
                Block.Rule -> Box(
                    Modifier
                        .padding(vertical = 18.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LineColour),
                )
            }
        }
    }
}

// A soft dot, lined up with the first line of words, then the words.
@Composable
private fun BulletLine(text: List<Span>, style: TextStyle, dot: Dp, dotColour: Color) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 8.dp, end = 12.dp).size(dot).background(dotColour, CircleShape))
        Text(annotated(text), style = style, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
    }
}

// The runs as styled text: bold is white and heavier, italic slants, code
// is set in a fixed-width face on a faint patch, and links are underlined.
private fun annotated(spans: List<Span>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val style = SpanStyle(
            color = if (span.bold || span.link != null) OctoColors.TextPrimary else Color.Unspecified,
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
        ).let { if (span.code) it.merge(CodeStyle) else it }
        val link = span.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, LinkStyle)) { withStyle(style) { append(span.text) } }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
