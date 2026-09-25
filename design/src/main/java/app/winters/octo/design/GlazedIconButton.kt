package app.winters.octo.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

// A lone round control floating over content: the bar's dark glass, lit by
// the glaze across the whole circle, so it matches the bar beside it.
@Composable
fun GlazedIconButton(
    backdrop: HazeState,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    GlassPanelDark(
        backdrop,
        modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Glaze(Modifier.matchParentSize()) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = OctoColors.TextPrimary,
                modifier = Modifier.size(if (size >= 56.dp) 22.dp else 20.dp),
            )
        }
    }
}
