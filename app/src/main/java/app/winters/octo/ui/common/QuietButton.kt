package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType

// A small text button for a lesser action beside a title, like "See all"
// or "Clear all": set like the sort line, with no border and no fill.
@Composable
fun QuietButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = OctoType.label,
        color = OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
