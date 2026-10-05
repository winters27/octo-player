package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared by the phone and the desktop, so an explicit song is marked the
// same way in both.

// The small "E" after the title of a song marked explicit, as Apple Music
// marks one: a muted tile with the letter cut out of it in the page's own
// color. Quiet beside the title, and the same size as a line of small
// text, so it grows with the listener's text size. A clean edit gets no
// mark; screen readers hear "Explicit".
@Composable
fun ExplicitMark(modifier: Modifier = Modifier) {
    val side = with(LocalDensity.current) { MarkSide.toDp() }
    Box(
        modifier
            .size(side)
            .background(OctoColors.TextMuted, RoundedCornerShape(side * CornerShare))
            .clearAndSetSemantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "E",
            style = TextStyle(
                color = OctoColors.Background,
                fontSize = MarkSide * 0.68f,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = MarkSide * 0.68f,
            ),
        )
    }
}

// The tile's side, as text is sized so it follows the text size, and how
// round its corners are: 3 of its 14.
private val MarkSide = 14.sp
private const val CornerShare = 3f / 14f

// The room between a title and its mark.
val ExplicitMarkGap = 6.dp
