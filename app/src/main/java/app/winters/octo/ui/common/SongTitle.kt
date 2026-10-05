package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.winters.octo.design.ExplicitMark
import app.winters.octo.design.ExplicitMarkGap
import app.winters.octo.design.OctoColors

// A song's title on one line, with the "E" after it when the song is marked
// explicit. A long title is cut short before the mark, so the mark always
// shows. `leading` goes before the title, such as the album's starred song.
@Composable
fun SongTitle(
    title: String,
    explicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    markColor: Color = OctoColors.TextMuted,
    titleModifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(title, style = style, color = color, maxLines = 1, overflow = overflow, modifier = Modifier.weight(1f, fill = false).then(titleModifier))
        if (explicit) ExplicitMark(Modifier.padding(start = ExplicitMarkGap), markColor)
    }
}
