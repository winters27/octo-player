package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// The accent mixed into black, so white text stays readable on it.
private val AccentFill = mix(OctoColors.Accent, Color.Black, 0.45f)

// The one filled button on a surface.
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    GlazeInset(
        fill = AccentFill,
        shape = CircleShape,
        modifier = modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        if (pressed) {
            Box(Modifier.matchParentSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.05f)))
        }
        Box(Modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            // The label stays in place while loading so the width never jumps.
            Text(
                text,
                style = OctoType.label,
                color = Color.White,
                modifier = Modifier.alpha(if (loading) 0f else 1f),
            )
            if (loading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
    }
}
