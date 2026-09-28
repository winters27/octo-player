package app.winters.octo.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role

// A quieter button to sit beside the accent one: the lit glaze with a
// label. It catches more light under the finger or the pointer, and
// shrinks a little when pressed. `tint` lays a fifth of a colour (the
// artwork's, say) into the glass.
@Composable
fun GlazeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Large,
    icon: Painter? = null,
    tint: Color? = null,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val look = rememberButtonLook(interaction)
    Glaze(
        modifier
            .height(size.height)
            .buttonMotion(look, CircleShape)
            .alpha(if (enabled) 1f else OctoInk.DisabledAlpha)
            .hoverable(interaction, enabled = enabled && !loading)
            .pointerHoverIcon(if (enabled && !loading) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            ),
        light = if (look.pressed || look.hovered) GlazeLight.Lifted else GlazeLight.Rest,
        film = if (tint != null) tint.copy(alpha = 0.20f).compositeOver(GlazeTint) else GlazeTint,
    ) {
        ButtonLabel(text, OctoColors.TextPrimary, size, icon = icon, loading = loading)
    }
}
