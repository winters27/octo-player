package app.winters.octo.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

// An icon that can light up with the same soft glow the progress line has:
// a wide faint halo and a tighter bright one behind the sharp icon. The
// glow fades in and out as `lit` changes. (Phones before Android 12 cannot
// blur, so there the icon simply shows without a glow.)
@Composable
fun GlowIcon(painter: Painter, tint: Color, lit: Boolean, modifier: Modifier = Modifier) {
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(250), label = "icon glow")
    Box(modifier) {
        if (glow > 0f) {
            Icon(
                painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.matchParentSize().blur(10.dp, BlurredEdgeTreatment.Unbounded).alpha(0.6f * glow),
            )
            Icon(
                painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.matchParentSize().blur(3.dp, BlurredEdgeTreatment.Unbounded).alpha(0.7f * glow),
            )
        }
        Icon(painter, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
    }
}
