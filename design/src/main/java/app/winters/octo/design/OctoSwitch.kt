package app.winters.octo.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private val OffTrack = Color(0xFF4B5563)

// The on/off switch for settings rows: accent when on, grey when off.
@Composable
fun OctoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(
        if (checked) OctoColors.Accent else OffTrack,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "switch track",
    )
    val thumbX by animateDpAsState(
        if (checked) 24.dp else 4.dp,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "switch thumb",
    )
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(track, CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(16.dp)
                .background(Color.White, CircleShape),
        )
    }
}
