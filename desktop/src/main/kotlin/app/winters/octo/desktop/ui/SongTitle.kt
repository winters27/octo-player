package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import app.winters.octo.catalog.isExplicit
import app.winters.octo.design.ExplicitMark
import app.winters.octo.design.ExplicitMarkGap
import app.winters.octo.design.OctoColors
import app.winters.octo.subsonic.Song
import kotlin.math.roundToInt

// A song's title and, when the server calls the song explicit, the quiet
// "E" after it. The title gives way first: it is cut or wraps, as `title`
// draws it, in the room the mark leaves, and the mark always shows. The
// mark sits level with the middle of the title's capitals on its first
// line, so it lines up with the words however tall the line is or however
// many lines a long title takes. `style` is the title's text style; `title`
// draws the words with the modifier it is given. Over a picture, such as
// the full player's, `markColor` is that surface's quieter ink.
@Composable
fun MarkedTitle(
    song: Song?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    markColor: Color = OctoColors.TextMuted,
    title: @Composable (Modifier) -> Unit,
) {
    if (song?.isExplicit != true) {
        title(modifier)
        return
    }
    val capsMiddle = with(LocalDensity.current) { (style.fontSize.toPx() * CapShare / 2).roundToInt() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ExplicitMarkGap)) {
        title(Modifier.weight(1f, fill = false).alignBy(FirstBaseline))
        ExplicitMark(Modifier.alignBy { it.measuredHeight / 2 + capsMiddle }, markColor)
    }
}

// How tall a capital is, as a share of the type's size, in the app's type.
private const val CapShare = 0.7f
