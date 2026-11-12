package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoInk
import app.winters.octo.design.OctoType

// A small text button for a lesser action beside a title, like "See all"
// or "Clear all": set like the sort line, with no border and no fill.
@Composable
fun QuietButton(text: String, onClick: () -> Unit) = QuietButton(text, enabled = true, onClick = onClick)

// The same, dimmed and out of reach while not `enabled`. `style` sets it in
// the words beside it instead, as under a list row's own lines.
@Composable
fun QuietButton(text: String, enabled: Boolean, style: TextStyle = OctoType.label, onClick: () -> Unit) {
    Text(
        text,
        style = style,
        color = OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = QuietButtonInset, vertical = QuietButtonSpill),
    )
}

// A quiet button's own padding beside and above its words: what a row
// shifts it by to end its words on the content's edge, and what spills
// past its line.
val QuietButtonInset = 10.dp
val QuietButtonSpill = 6.dp

// A quiet button set in a caption line: the caption's size, a label's weight.
val InCaption = OctoType.label.copy(fontSize = OctoType.caption.fontSize, lineHeight = OctoType.caption.lineHeight)
